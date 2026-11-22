package dev.photohub.shared.auth

import dev.photohub.shared.platform.openInBrowser

/**
 * Captures the OAuth redirect. Implemented per platform: desktop runs a
 * loopback HTTP listener, Android receives an intent on a custom scheme.
 */
interface RedirectCapture {
    /** Begins listening and returns the redirect URI to send Google to. */
    fun begin(): String

    /** Suspends until the browser redirect arrives. */
    suspend fun await(): Result<RedirectResult>

    fun cancel()
}

data class RedirectResult(
    val code: String?,
    val state: String?,
    val error: String?,
)

/**
 * Runs the full Drive sign-in: build the consent URL, open a browser, capture
 * the redirect, and exchange the code for tokens.
 *
 * Kept platform-agnostic so desktop and Android share the entire flow and only
 * differ in how the redirect comes back.
 */
class DriveLinkCoordinator(
    private val oauth: GoogleOAuthClient,
    private val tokens: TokenProvider,
    private val capture: RedirectCapture,
    /**
     * Overridable only so tests can drive the real flow with a known state.
     * Production always uses a fresh random value.
     */
    private val generateState: () -> String = { PkceChallenge.randomState() },
    /**
     * Injected so tests do not launch a real browser. Without this seam the
     * suite opened a tab per test case on the developer's desktop.
     */
    private val launchBrowser: (String) -> Boolean = ::openInBrowser,
) {
    sealed interface Outcome {
        data object Success : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Performs the sign-in. Returns once tokens are stored or the attempt failed.
     *
     * The `state` value is generated here and verified against what comes back,
     * which is what stops an attacker from feeding us their own authorization
     * code (OAuth CSRF).
     */
    suspend fun link(): Outcome {
        val pkce = PkceChallenge.generate()
        val state = generateState()

        val redirectUri = runCatching { capture.begin() }
            .getOrElse { return Outcome.Failed("Could not start the sign-in listener: ${it.message}") }

        try {
            val url = oauth.authorizationUrl(pkce, state, redirectUri)
            if (!launchBrowser(url)) {
                return Outcome.Failed("Could not open a browser. Visit this URL manually:\n$url")
            }

            val redirect = capture.await().getOrElse {
                return Outcome.Failed("Timed out waiting for Google: ${it.message}")
            }

            redirect.error?.let { return Outcome.Failed("Google denied the request: $it") }

            // A mismatched state means the redirect did not originate from the
            // request we made, so the code must not be redeemed.
            if (redirect.state != state) {
                return Outcome.Failed("Sign-in state mismatch; the request was not completed safely.")
            }

            val code = redirect.code
                ?: return Outcome.Failed("Google did not return an authorization code.")

            val newTokens = oauth.exchangeCode(code, pkce, redirectUri)
                .getOrElse { return Outcome.Failed("Could not exchange the code: ${it.message}") }

            if (newTokens.refreshToken == null) {
                // Without a refresh token, background upload dies at the first
                // token expiry; better to fail loudly now than silently later.
                return Outcome.Failed(
                    "Google did not issue a refresh token. Remove PhotoHub from your " +
                        "Google account permissions and try again.",
                )
            }

            tokens.onAuthorized(newTokens)
            return Outcome.Success
        } finally {
            capture.cancel()
        }
    }

    fun unlink() = tokens.unlink()

    fun isLinked(): Boolean = tokens.isLinked()
}
