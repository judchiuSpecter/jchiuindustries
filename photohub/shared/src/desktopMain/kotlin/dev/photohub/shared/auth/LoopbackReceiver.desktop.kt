package dev.photohub.shared.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * One-shot HTTP listener on `127.0.0.1` that captures Google's OAuth redirect.
 *
 * This is Google's prescribed flow for installed apps: the browser is sent to a
 * loopback URL, so the authorization code never leaves the machine and no custom
 * URI scheme registration is needed.
 *
 * Port 0 is requested so the OS assigns a free one — a fixed port would collide
 * with whatever else the user is running. Google permits any port on loopback.
 */
class LoopbackReceiver(
    private val timeoutMs: Int = 5 * 60 * 1000,
) {
    /** The code and state Google sent back, or an error it reported. */
    data class Redirect(
        val code: String?,
        val state: String?,
        val error: String?,
    )

    private var server: ServerSocket? = null

    /** Binds the socket and returns the redirect URI to register with Google. */
    fun start(): String {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = timeoutMs
        server = socket
        return "http://127.0.0.1:${socket.localPort}"
    }

    /**
     * Waits for the browser to hit the loopback URL and returns the parameters.
     *
     * A human-readable page is written back so the user sees confirmation in the
     * browser rather than a connection error, then the socket closes.
     */
    suspend fun awaitRedirect(): Result<Redirect> = withContext(Dispatchers.IO) {
        val socket = server ?: return@withContext Result.failure(
            IllegalStateException("start() must be called before awaitRedirect()"),
        )

        runCatching {
            socket.accept().use { client ->
                val requestLine = client.getInputStream()
                    .bufferedReader()
                    .readLine()
                    ?: error("Empty request on loopback redirect")

                val params = parseQuery(requestLine)
                val redirect = Redirect(
                    code = params["code"],
                    state = params["state"],
                    error = params["error"],
                )

                val message = when {
                    redirect.error != null -> "Sign-in failed: ${redirect.error}"
                    redirect.code != null -> "PhotoHub is connected. You can close this tab."
                    else -> "Unexpected response. Please try again."
                }
                client.getOutputStream().write(htmlResponse(message).toByteArray())
                client.getOutputStream().flush()
                redirect
            }
        }.also { close() }
    }

    fun close() {
        runCatching { server?.close() }
        server = null
    }

    /** Pulls the query string out of `GET /?code=...&state=... HTTP/1.1`. */
    private fun parseQuery(requestLine: String): Map<String, String> {
        val path = requestLine.split(' ').getOrNull(1) ?: return emptyMap()
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()

        return query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = pair.substring(0, idx)
            val value = runCatching {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrNull() ?: return@mapNotNull null
            name to value
        }.toMap()
    }

    private fun htmlResponse(message: String): String {
        val body = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <title>PhotoHub</title></head>
            <body style="font-family:system-ui;text-align:center;padding-top:4rem">
            <h2>$message</h2></body></html>
        """.trimIndent()
        return buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
    }
}
