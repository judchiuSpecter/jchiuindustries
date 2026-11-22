package dev.photohub.shared

import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthTokenStore
import dev.photohub.shared.auth.OAuthTokens
import dev.photohub.shared.auth.PkceChallenge
import dev.photohub.shared.auth.ReauthRequiredException
import dev.photohub.shared.auth.TokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory [CredentialStore] so token handling can be tested without a keystore. */
private class FakeCredentialStore : CredentialStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun removeAll(keyPrefix: String) {
        values.keys.filter { it.startsWith(keyPrefix) }.forEach(values::remove)
    }
}

private fun jsonClient(
    status: HttpStatusCode = HttpStatusCode.OK,
    body: String,
    onRequest: (() -> Unit)? = null,
): HttpClient = HttpClient(
    MockEngine { _ ->
        onRequest?.invoke()
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    },
)

class PkceTest {

    @Test
    fun verifierMeetsRfc7636LengthAndAlphabet() {
        val pkce = PkceChallenge.generate()
        // RFC 7636 requires 43..128 characters.
        assertTrue(pkce.verifier.length in 43..128, "length=${pkce.verifier.length}")
        assertEquals("S256", pkce.method)

        // base64url only: a '+', '/' or '=' would be mangled in a URL.
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
        assertTrue(pkce.verifier.all { it in allowed }, pkce.verifier)
        assertTrue(pkce.challenge.all { it in allowed }, pkce.challenge)
    }

    @Test
    fun challengeIsDerivedFromVerifierAndNotEqualToIt() {
        val pkce = PkceChallenge.generate()
        assertNotEquals(pkce.verifier, pkce.challenge)
        // SHA-256 is 32 bytes -> 43 base64url chars.
        assertEquals(43, pkce.challenge.length)
    }

    @Test
    fun eachGenerationIsUnique() {
        val seen = (1..50).map { PkceChallenge.generate().verifier }.toSet()
        assertEquals(50, seen.size, "verifiers must not repeat")
    }

    @Test
    fun knownVectorMatchesRfc7636AppendixB() {
        // The RFC's worked example pins the exact S256 transformation.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val challenge = dev.photohub.shared.auth.base64UrlNoPad(
            dev.photohub.shared.platform.sha256(verifier.encodeToByteArray()),
        )
        assertEquals(expected, challenge)
    }
}

class GoogleOAuthClientTest {

    private val config = GoogleOAuthConfig(clientId = "test-client.apps.googleusercontent.com")

    @Test
    fun authorizationUrlCarriesPkceAndOfflineAccess() {
        val client = GoogleOAuthClient(jsonClient(body = "{}"), config, now = { 0L })
        val pkce = PkceChallenge.generate()
        val url = client.authorizationUrl(pkce, "state123", "http://127.0.0.1:1234")

        assertTrue(url.startsWith(GoogleOAuthClient.AUTH_ENDPOINT))
        assertTrue(url.contains("code_challenge=${pkce.challenge}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state123"))
        // Without offline access Google returns no refresh token and background
        // upload breaks as soon as the access token expires.
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
        // The redirect URI must be percent-encoded, not raw.
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1234"), url)
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"), url)
    }

    @Test
    fun exchangeCodeParsesTokensAndComputesAbsoluteExpiry() = runTest {
        val body = """
            {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"token_type":"Bearer"}
        """.trimIndent()
        val client = GoogleOAuthClient(jsonClient(body = body), config, now = { 1_000_000L })

        val tokens = client.exchangeCode("code-1", PkceChallenge.generate(), "http://127.0.0.1:1")
            .getOrThrow()

        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        // Stored as an absolute instant so a restart cannot make it look fresh.
        assertEquals(1_000_000L + 3_600_000L, tokens.expiresAtEpochMs)
    }

    @Test
    fun errorResponseSurfacesGoogleDescription() = runTest {
        val body = """{"error":"invalid_grant","error_description":"Token has been expired"}"""
        val client = GoogleOAuthClient(
            jsonClient(HttpStatusCode.BadRequest, body),
            config,
            now = { 0L },
        )

        val failure = client.refresh("rt-dead").exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure.message!!.contains("Token has been expired"), failure.message!!)
    }

    @Test
    fun http200WithoutAccessTokenIsTreatedAsFailure() = runTest {
        // A malformed success response must not yield a blank token that then
        // fails confusingly on every API call.
        val client = GoogleOAuthClient(jsonClient(body = """{"token_type":"Bearer"}"""), config, now = { 0L })
        assertTrue(client.refresh("rt").isFailure)
    }
}

class OAuthTokenStoreTest {

    @Test
    fun refreshTokenSurvivesARefreshResponseThatOmitsIt() {
        val store = OAuthTokenStore(FakeCredentialStore(), "google_drive")
        store.save(OAuthTokens("at-1", "rt-1", 1000L))

        // Google omits refresh_token when refreshing; losing it would force the
        // user to re-link on every token expiry.
        store.save(OAuthTokens("at-2", null, 5000L))

        val loaded = store.load()!!
        assertEquals("at-2", loaded.accessToken)
        assertEquals("rt-1", loaded.refreshToken)
        assertEquals(5000L, loaded.expiresAtEpochMs)
    }

    @Test
    fun clearRemovesEveryFieldForTheSource() {
        val backing = FakeCredentialStore()
        val store = OAuthTokenStore(backing, "google_drive")
        store.save(OAuthTokens("at", "rt", 1L))
        backing.put("other_source.accessToken", "keep-me")

        store.clear()

        assertNull(store.load())
        assertEquals("keep-me", backing.get("other_source.accessToken"))
    }

    @Test
    fun expiryAppliesClockSkewMargin() {
        val tokens = OAuthTokens("at", "rt", expiresAtEpochMs = 100_000L)
        // Comfortably valid.
        assertTrue(!tokens.isExpired(0L))
        // Inside the 60s skew window: treated as expired so a request cannot be
        // sent with a token that lapses in flight.
        assertTrue(tokens.isExpired(50_000L))
        assertTrue(tokens.isExpired(100_001L))
    }
}

class TokenProviderTest {

    private val config = GoogleOAuthConfig(clientId = "cid")

    @Test
    fun validTokenIsReturnedWithoutContactingGoogle() = runTest {
        var requests = 0
        val oauth = GoogleOAuthClient(
            jsonClient(body = "{}", onRequest = { requests++ }),
            config,
            now = { 0L },
        )
        val store = OAuthTokenStore(FakeCredentialStore(), "s")
        store.save(OAuthTokens("fresh", "rt", expiresAtEpochMs = 10_000_000L))

        val provider = TokenProvider(oauth, store, now = { 0L })
        assertEquals("fresh", provider.accessToken())
        assertEquals(0, requests, "a valid token must not trigger a refresh")
    }

    @Test
    fun expiredTokenIsRefreshedAndPersisted() = runTest {
        val body = """{"access_token":"at-new","expires_in":3600}"""
        val oauth = GoogleOAuthClient(jsonClient(body = body), config, now = { 500L })
        val backing = FakeCredentialStore()
        val store = OAuthTokenStore(backing, "s")
        store.save(OAuthTokens("stale", "rt-1", expiresAtEpochMs = 100L))

        val provider = TokenProvider(oauth, store, now = { 500L })
        assertEquals("at-new", provider.accessToken())

        // Persisted, so a restart does not refresh again.
        assertEquals("at-new", store.load()!!.accessToken)
        assertEquals("rt-1", store.load()!!.refreshToken)
    }

    @Test
    fun concurrentCallersTriggerOnlyOneRefresh() = runTest {
        var requests = 0
        val body = """{"access_token":"at-new","expires_in":3600}"""
        val oauth = GoogleOAuthClient(
            jsonClient(body = body, onRequest = { requests++ }),
            config,
            now = { 0L },
        )
        val store = OAuthTokenStore(FakeCredentialStore(), "s")
        store.save(OAuthTokens("stale", "rt", expiresAtEpochMs = 0L))
        val provider = TokenProvider(oauth, store, now = { 1000L })

        // Uploads and thumbnail fetches run concurrently; a refresh stampede
        // would waste requests and can invalidate earlier access tokens.
        val results = (1..8).map { async { provider.accessToken() } }.awaitAll()

        assertTrue(results.all { it == "at-new" })
        assertEquals(1, requests, "refresh must be single-flight")
    }

    @Test
    fun revokedGrantClearsCredentialsAndDemandsReauth() = runTest {
        val body = """{"error":"invalid_grant","error_description":"revoked"}"""
        val oauth = GoogleOAuthClient(
            jsonClient(HttpStatusCode.BadRequest, body),
            config,
            now = { 0L },
        )
        val store = OAuthTokenStore(FakeCredentialStore(), "s")
        store.save(OAuthTokens("stale", "rt-revoked", expiresAtEpochMs = 0L))
        val provider = TokenProvider(oauth, store, now = { 1000L })

        assertFailsWith<ReauthRequiredException> { provider.accessToken() }
        // Useless credentials are dropped rather than retried forever.
        assertNull(store.load())
    }

    @Test
    fun missingAccountRequiresReauthRatherThanFailingObscurely() = runTest {
        val oauth = GoogleOAuthClient(jsonClient(body = "{}"), config, now = { 0L })
        val provider = TokenProvider(oauth, OAuthTokenStore(FakeCredentialStore(), "s"), now = { 0L })

        assertFailsWith<ReauthRequiredException> { provider.accessToken() }
        assertTrue(!provider.isLinked())
    }
}
