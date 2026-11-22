package dev.photohub.shared.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Identifies this installation of PhotoHub to Google.
 *
 * A "Desktop app" / installed-app OAuth client is used on both platforms, which
 * has no client secret — installed apps cannot keep one, so PKCE provides the
 * protection instead.
 */
data class GoogleOAuthConfig(
    val clientId: String,
    /**
     * Only set for client types Google still issues a secret for. Installed-app
     * clients leave this null; it is not a security boundary either way.
     */
    val clientSecret: String? = null,
    /**
     * `drive.file` restricts PhotoHub to files it created itself, which is all
     * the camera-upload design needs and avoids Google's restricted-scope review.
     */
    val scopes: List<String> = listOf("https://www.googleapis.com/auth/drive.file"),
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
}

/**
 * A failure returned by Google's token endpoint.
 *
 * [error] is the machine-readable OAuth code (`invalid_grant`,
 * `invalid_client`, ...). It is kept as a field rather than being folded into
 * the message, because callers have to branch on it — `invalid_grant` means the
 * grant is permanently dead and the user must re-link, while other codes may be
 * transient. Matching on human-readable text would break the moment Google
 * reworded a description.
 */
class OAuthErrorException(
    val error: String?,
    val status: Int,
    message: String,
) : Exception(message) {
    /** The grant is revoked/expired beyond recovery; retrying cannot help. */
    val isInvalidGrant: Boolean get() = error == "invalid_grant"
}

/** Raw token response from Google's token endpoint. */
@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("token_type") val tokenType: String = "",
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/**
 * Google's OAuth 2.0 endpoints for installed apps with PKCE.
 *
 * This type only speaks HTTP; capturing the redirect is the platform's job, so
 * the same code serves the desktop loopback listener and Android's custom tab.
 */
class GoogleOAuthClient(
    private val http: HttpClient,
    private val config: GoogleOAuthConfig,
    private val now: () -> Long,
    private val authEndpoint: String = AUTH_ENDPOINT,
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Builds the consent URL to open in the browser.
     *
     * `access_type=offline` is what makes Google return a refresh token, and
     * `prompt=consent` forces one to be re-issued even if the user has already
     * approved the app — without it a re-link can silently yield no refresh
     * token and background upload breaks the next time the access token lapses.
     */
    fun authorizationUrl(
        pkce: PkceChallenge,
        state: String,
        redirectUri: String,
    ): String {
        val params = listOf(
            "client_id" to config.clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to config.scopes.joinToString(" "),
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to pkce.method,
            "state" to state,
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return authEndpoint + "?" + params.joinToString("&") { (k, v) ->
            "${k.urlEncode()}=${v.urlEncode()}"
        }
    }

    /** Redeems an authorization code for tokens. */
    suspend fun exchangeCode(
        code: String,
        pkce: PkceChallenge,
        redirectUri: String,
    ): Result<OAuthTokens> = post {
        append("grant_type", "authorization_code")
        append("code", code)
        append("client_id", config.clientId)
        append("redirect_uri", redirectUri)
        append("code_verifier", pkce.verifier)
        config.clientSecret?.let { append("client_secret", it) }
    }

    /**
     * Mints a fresh access token.
     *
     * Google omits the refresh token here, so the caller must keep the existing
     * one — [OAuthTokenStore.save] already preserves it on null.
     */
    suspend fun refresh(refreshToken: String): Result<OAuthTokens> = post {
        append("grant_type", "refresh_token")
        append("refresh_token", refreshToken)
        append("client_id", config.clientId)
        config.clientSecret?.let { append("client_secret", it) }
    }

    private suspend fun post(addParams: ParametersBuilderScope): Result<OAuthTokens> =
        runCatching {
            // `addParams.invoke(this)` is explicit on purpose. Written as
            // `addParams()` inside this lambda, Kotlin resolves the call to
            // ParametersBuilder's *own* member `build()` instead of invoking the
            // parameter — member functions win over invoking a function-typed
            // parameter of the same name. That silently sent an empty form body
            // and every token request failed with HTTP 400.
            val form = Parameters.build { addParams.invoke(this) }
            check(!form.isEmpty()) { "token request would be sent with no parameters" }

            val response = http.submitForm(url = tokenEndpoint, formParameters = form)
            val text = response.bodyAsText()
            val parsed = runCatching { json.decodeFromString<TokenResponse>(text) }.getOrNull()

            if (response.status != HttpStatusCode.OK || parsed == null || parsed.accessToken.isBlank()) {
                // Google's `error` code is terse ("invalid_grant") and its
                // `error_description` is often just "Bad Request", so neither
                // alone identifies the cause. Keep the raw body: without it a
                // failed exchange is undiagnosable from the UI message.
                val detail = listOfNotNull(
                    parsed?.error,
                    parsed?.errorDescription,
                    text.take(300).takeIf { it.isNotBlank() && parsed?.errorDescription == null },
                ).distinct().joinToString(" — ").ifBlank { "no detail returned" }
                throw OAuthErrorException(
                    error = parsed?.error,
                    status = response.status.value,
                    message = "Google token request failed (${response.status.value}): $detail",
                )
            }

            OAuthTokens(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresAtEpochMs = now() + parsed.expiresIn * 1000,
            )
        }

    companion object {
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    }
}

private typealias ParametersBuilderScope = ParametersBuilder.() -> Unit
private typealias ParametersBuilder = io.ktor.http.ParametersBuilder

/** Percent-encodes a query value; Ktor's builder is not used so the URL stays exact. */
internal fun String.urlEncode(): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    val out = StringBuilder()
    for (byte in encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c in unreserved) {
            out.append(c)
        } else {
            out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
