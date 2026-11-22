package dev.photohub.shared

import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.DriveLinkCoordinator
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthTokenStore
import dev.photohub.shared.auth.RedirectCapture
import dev.photohub.shared.auth.RedirectResult
import dev.photohub.shared.auth.TokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore : CredentialStore {
    val v = mutableMapOf<String, String>()
    override fun get(key: String) = v[key]
    override fun put(key: String, value: String) { v[key] = value }
    override fun remove(key: String) { v.remove(key) }
    override fun removeAll(keyPrefix: String) {
        v.keys.filter { it.startsWith(keyPrefix) }.forEach(v::remove)
    }
}

/** Replays a scripted redirect, and records the state the coordinator generated. */
private class ScriptedCapture(
    private val respondWith: (requestedState: String?) -> RedirectResult,
) : RedirectCapture {
    var capturedUrlState: String? = null
    var cancelled = false

    override fun begin(): String = "http://127.0.0.1:9999"
    override suspend fun await(): Result<RedirectResult> =
        Result.success(respondWith(capturedUrlState))
    override fun cancel() { cancelled = true }
}

class DriveLinkCoordinatorTest {

    private fun coordinator(
        capture: RedirectCapture,
        tokenBody: String = """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
        store: MemoryStore = MemoryStore(),
    ): Pair<DriveLinkCoordinator, OAuthTokenStore> {
        val http = HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(tokenBody),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val oauth = GoogleOAuthClient(http, GoogleOAuthConfig("cid"), now = { 0L })
        val tokens = OAuthTokenStore(store, "google_drive")
        val provider = TokenProvider(oauth, tokens, now = { 0L })
        return DriveLinkCoordinator(oauth, provider, capture, launchBrowser = { true }) to tokens
    }

    @Test
    fun browserThatWillNotOpenSurfacesTheUrlAndReleasesTheListener() = runTest {
        // Headless machines and broken xdg-open setups must not leave the
        // loopback listener bound; the user needs the URL to paste manually.
        var cancelled = false
        val capture = object : RedirectCapture {
            override fun begin() = "http://127.0.0.1:1410/callback"
            override suspend fun await() =
                Result.failure<RedirectResult>(IllegalStateException("must not be awaited"))
            override fun cancel() { cancelled = true }
        }
        val (_, tokens) = coordinator(capture)
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val oauth = GoogleOAuthClient(http, GoogleOAuthConfig("cid"), now = { 0L })
        val coordinator = DriveLinkCoordinator(
            oauth,
            TokenProvider(oauth, tokens, now = { 0L }),
            capture,
            launchBrowser = { false },
        )

        val outcome = coordinator.link()

        val reason = (outcome as DriveLinkCoordinator.Outcome.Failed).reason
        assertTrue(reason.contains("accounts.google.com"), "should include the URL to open by hand: $reason")
        assertTrue(cancelled, "listener must be cancelled when the browser never opens")
        assertNull(tokens.load(), "no token may be stored when sign-in never started")
    }

    @Test
    fun rejectsRedirectWithMismatchedState() = runTest {
        // An attacker-supplied code arriving with the wrong state must not be
        // redeemed — this is the OAuth CSRF defence.
        val capture = ScriptedCapture { RedirectResult(code = "evil-code", state = "wrong", error = null) }
        val (coordinator, tokens) = coordinator(capture)

        val outcome = coordinator.link()

        assertTrue(outcome is DriveLinkCoordinator.Outcome.Failed)
        assertTrue((outcome as DriveLinkCoordinator.Outcome.Failed).reason.contains("state mismatch"))
        assertNull(tokens.load(), "no token may be stored for a mismatched state")
        assertTrue(capture.cancelled, "the listener must be released")
    }

    @Test
    fun reportsGoogleSideDenial() = runTest {
        val capture = ScriptedCapture { RedirectResult(null, null, "access_denied") }
        val (coordinator, tokens) = coordinator(capture)

        val outcome = coordinator.link()

        assertTrue(outcome is DriveLinkCoordinator.Outcome.Failed)
        assertTrue((outcome as DriveLinkCoordinator.Outcome.Failed).reason.contains("access_denied"))
        assertNull(tokens.load())
    }

    /** Builds a coordinator whose state is known, so the real flow can run. */
    private fun linked(
        tokenBody: String,
        state: String = "fixed-state",
    ): Triple<DriveLinkCoordinator, OAuthTokenStore, ScriptedCapture> {
        val capture = ScriptedCapture { RedirectResult("code-ok", state, null) }
        val http = HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(tokenBody),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val oauth = GoogleOAuthClient(http, GoogleOAuthConfig("cid"), now = { 0L })
        val tokens = OAuthTokenStore(MemoryStore(), "google_drive")
        val provider = TokenProvider(oauth, tokens, now = { 0L })
        return Triple(
            DriveLinkCoordinator(oauth, provider, capture, generateState = { state }, launchBrowser = { true }),
            tokens,
            capture,
        )
    }

    @Test
    fun missingRefreshTokenFailsLoudlyRatherThanHalfLinking() = runTest {
        // Without a refresh token, background upload dies at the first expiry.
        // Reporting success would make that look like a random failure later.
        val (coordinator, tokens, _) = linked("""{"access_token":"at","expires_in":3600}""")

        val outcome = coordinator.link()

        assertTrue(outcome is DriveLinkCoordinator.Outcome.Failed)
        assertTrue((outcome as DriveLinkCoordinator.Outcome.Failed).reason.contains("refresh token"))
        assertNull(tokens.load(), "a half-linked account must not be persisted")
    }

    @Test
    fun successfulLinkStoresTokensAndReleasesTheListener() = runTest {
        val (coordinator, tokens, capture) = linked(
            """{"access_token":"at-ok","refresh_token":"rt-ok","expires_in":3600}""",
        )

        assertEquals(DriveLinkCoordinator.Outcome.Success, coordinator.link())
        assertEquals("at-ok", tokens.load()!!.accessToken)
        assertEquals("rt-ok", tokens.load()!!.refreshToken)
        // Expiry is absolute, so a restart cannot mistake it for fresh.
        assertEquals(3_600_000L, tokens.load()!!.expiresAtEpochMs)
        assertTrue(capture.cancelled, "the redirect listener must always be closed")
    }
}
