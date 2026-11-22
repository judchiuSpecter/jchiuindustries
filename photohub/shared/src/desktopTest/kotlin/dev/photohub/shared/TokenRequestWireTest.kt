package dev.photohub.shared

import com.sun.net.httpserver.HttpServer
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.PkceChallenge
import dev.photohub.shared.platform.createHttpClient
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the exact wire format of the token request.
 *
 * MockEngine tests verify what the code *intends* to send; this runs the real
 * desktop HTTP client against a real socket so engine-level behaviour (method,
 * content type, body encoding) is covered too. A hand-rolled `curl` of the same
 * parameters succeeded while the app got HTTP 400, which only a wire-level
 * comparison can explain.
 */
class TokenRequestWireTest {

    @Test
    fun tokenRequestIsAFormPostWithEveryRequiredParameter() = runTest {
        var method: String? = null
        var contentType: String? = null
        var body: String? = null

        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/token") { exchange ->
            method = exchange.requestMethod
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            body = exchange.requestBody.readBytes().decodeToString()
            val json = """{"access_token":"at","refresh_token":"rt","expires_in":3599}"""
            exchange.sendResponseHeaders(200, json.length.toLong())
            exchange.responseBody.use { it.write(json.encodeToByteArray()) }
        }
        server.start()

        try {
            val client = GoogleOAuthClient(
                http = createHttpClient(),
                config = GoogleOAuthConfig(clientId = "cid", clientSecret = "sec"),
                now = { 0L },
                tokenEndpoint = "http://127.0.0.1:${server.address.port}/token",
            )
            val pkce = PkceChallenge(verifier = "ver-123", challenge = "chal")

            val result = client.exchangeCode("the-code", pkce, "http://127.0.0.1:9999")

            assertTrue(result.isSuccess, "exchange should succeed: ${result.exceptionOrNull()}")
            println("WIRE method=$method contentType=$contentType")
            println("WIRE body=$body")

            assertEquals("POST", method)
            assertTrue(
                contentType!!.startsWith("application/x-www-form-urlencoded"),
                "Google rejects anything else: $contentType",
            )

            assertTrue(body!!.isNotEmpty(), "token request body must not be empty")
            val form = body!!.split("&").associate { pair ->
                val parts = pair.split("=", limit = 2)
                parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("the-code", form["code"])
            assertEquals("cid", form["client_id"])
            assertEquals("sec", form["client_secret"], "desktop clients must send the secret")
            assertEquals("ver-123", form["code_verifier"], "PKCE verifier, not the challenge")
            assertEquals("http://127.0.0.1:9999", form["redirect_uri"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun refreshRequestAlsoCarriesItsParameters() = runTest {
        // Same `post` helper as exchangeCode, so the empty-body bug broke token
        // refresh too — which would have surfaced only an hour after sign-in.
        var body: String? = null
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/token") { exchange ->
            body = exchange.requestBody.readBytes().decodeToString()
            val json = """{"access_token":"at2","expires_in":3599}"""
            exchange.sendResponseHeaders(200, json.length.toLong())
            exchange.responseBody.use { it.write(json.encodeToByteArray()) }
        }
        server.start()

        try {
            val client = GoogleOAuthClient(
                http = createHttpClient(),
                config = GoogleOAuthConfig(clientId = "cid", clientSecret = "sec"),
                now = { 0L },
                tokenEndpoint = "http://127.0.0.1:${server.address.port}/token",
            )

            val result = client.refresh("the-refresh-token")

            assertTrue(result.isSuccess, "refresh should succeed: ${result.exceptionOrNull()}")
            assertTrue(body!!.contains("grant_type=refresh_token"), "body=$body")
            assertTrue(body!!.contains("refresh_token=the-refresh-token"), "body=$body")
            assertTrue(body!!.contains("client_secret=sec"), "body=$body")
        } finally {
            server.stop(0)
        }
    }
}
