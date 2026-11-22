package dev.photohub.shared.platform

import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

private val secureRandom by lazy { SecureRandom() }

actual fun randomBytes(count: Int): ByteArray =
    ByteArray(count).also { secureRandom.nextBytes(it) }

actual fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

/**
 * Opens [url] in the user's default browser.
 *
 * `java.awt.Desktop` is unavailable on many Linux setups (headless JREs, some
 * window managers), so `xdg-open` and the macOS/Windows equivalents are used as
 * a fallback rather than failing the sign-in outright.
 */
actual fun openInBrowser(url: String): Boolean {
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            Desktop.getDesktop().browse(URI(url))
            return true
        }
    }

    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        os.contains("mac") -> listOf("open", url)
        os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> listOf("xdg-open", url)
    }
    return runCatching { ProcessBuilder(command).start(); true }.getOrDefault(false)
}
