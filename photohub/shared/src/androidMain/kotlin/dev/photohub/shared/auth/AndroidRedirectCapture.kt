package dev.photohub.shared.auth

import kotlinx.coroutines.CompletableDeferred

/**
 * Android [RedirectCapture] fed by the activity that receives the OAuth
 * redirect intent.
 *
 * Android cannot use a loopback socket the way desktop does, because the
 * browser runs in a different app and app-private ports are unreliable. Instead
 * Google redirects to a custom scheme registered in the manifest
 * (`<reverse-client-id>:/oauth2redirect`), the system routes that back into the
 * app, and the activity hands the parameters here.
 *
 * Instances are single-use, matching one sign-in attempt.
 */
class AndroidRedirectCapture(
    private val redirectUri: String,
) : RedirectCapture {

    private var pending: CompletableDeferred<RedirectResult>? = null

    override fun begin(): String {
        pending = CompletableDeferred()
        return redirectUri
    }

    override suspend fun await(): Result<RedirectResult> {
        val deferred = pending
            ?: return Result.failure(IllegalStateException("begin() was not called"))
        return runCatching { deferred.await() }
    }

    /** Called by the activity when the redirect intent arrives. */
    fun onRedirect(code: String?, state: String?, error: String?) {
        pending?.complete(RedirectResult(code = code, state = state, error = error))
    }

    override fun cancel() {
        // Completing rather than cancelling lets the coordinator report a clean
        // "cancelled" outcome instead of surfacing a CancellationException.
        pending?.complete(RedirectResult(null, null, "cancelled"))
        pending = null
    }

    companion object {
        /**
         * Builds the redirect URI from the OAuth client id.
         *
         * Google requires the reverse-DNS form of the client id as the scheme
         * for Android clients, e.g.
         * `com.googleusercontent.apps.123-abc:/oauth2redirect`.
         */
        fun redirectUriFor(clientId: String): String {
            val reversed = clientId
                .removeSuffix(".apps.googleusercontent.com")
                .let { "com.googleusercontent.apps.$it" }
            return "$reversed:/oauth2redirect"
        }
    }
}
