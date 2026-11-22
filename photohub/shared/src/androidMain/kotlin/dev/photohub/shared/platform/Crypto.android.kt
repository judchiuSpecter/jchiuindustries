package dev.photohub.shared.platform

import android.content.Intent
import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom

private val secureRandom by lazy { SecureRandom() }

actual fun randomBytes(count: Int): ByteArray =
    ByteArray(count).also { secureRandom.nextBytes(it) }

actual fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

/**
 * Opens [url] in the user's browser for OAuth consent.
 *
 * `FLAG_ACTIVITY_NEW_TASK` is required because this is launched from an
 * application context rather than an activity. Google rejects OAuth inside
 * webviews, so a real browser is the only workable option.
 */
actual fun openInBrowser(url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    AndroidPlatform.appContext.startActivity(intent)
    true
}.getOrDefault(false)
