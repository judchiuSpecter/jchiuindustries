package dev.photohub.shared.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raised when the stored grant is gone and only the user can fix it. */
class ReauthRequiredException(message: String) : Exception(message)

/**
 * Hands out a valid access token, refreshing when needed.
 *
 * Thumbnail fetches and uploads run concurrently, so without coordination an
 * expired token would trigger one refresh per in-flight request — wasteful, and
 * Google invalidates older access tokens when many are minted at once. The
 * mutex makes it single-flight: the first caller refreshes, the rest wait and
 * reuse the result.
 */
class TokenProvider(
    private val oauth: GoogleOAuthClient,
    private val tokens: OAuthTokenStore,
    private val now: () -> Long,
) {
    private val mutex = Mutex()

    /** True when an account has been linked, regardless of token freshness. */
    fun isLinked(): Boolean = tokens.load()?.refreshToken != null

    /**
     * Returns a usable access token, refreshing first if it has expired.
     *
     * @throws ReauthRequiredException when there is no refresh token, or Google
     *   rejects it — both need the user to re-link, so they must not be retried.
     */
    suspend fun accessToken(): String = mutex.withLock {
        val current = tokens.load()
            ?: throw ReauthRequiredException("No Google account is linked.")

        if (!current.isExpired(now())) return@withLock current.accessToken

        val refreshToken = current.refreshToken
            ?: throw ReauthRequiredException(
                "Access token expired and no refresh token is stored. Re-link the account.",
            )

        val refreshed = oauth.refresh(refreshToken).getOrElse { cause ->
            // invalid_grant means revoked or expired beyond recovery: the stored
            // credentials are useless, so clear them rather than retrying forever.
            // Checked on the structured error code, not the message text.
            if (cause is OAuthErrorException && cause.isInvalidGrant) {
                tokens.clear()
                throw ReauthRequiredException("Google access was revoked. Re-link the account.")
            }
            throw cause
        }

        tokens.save(refreshed)
        refreshed.accessToken
    }

    /** Stores the tokens from a completed sign-in. */
    fun onAuthorized(newTokens: OAuthTokens) = tokens.save(newTokens)

    fun unlink() = tokens.clear()
}
