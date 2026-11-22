package dev.photohub.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout

actual fun createHttpClient(): HttpClient = HttpClient(Java) {
    // Failures are surfaced as responses and classified by DriveApi, which
    // knows which statuses are retryable; throwing would lose that detail.
    expectSuccess = false

    install(HttpTimeout) {
        // Long enough for a large chunk on a slow link, short enough that a
        // dead connection does not stall a sync indefinitely.
        requestTimeoutMillis = 5 * 60 * 1000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
    }
}
