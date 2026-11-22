package dev.photohub.shared.auth

import dev.photohub.shared.platform.randomBytes
import dev.photohub.shared.platform.sha256

/**
 * PKCE parameters for one authorization attempt (RFC 7636).
 *
 * Installed apps cannot keep a client secret, so Google requires PKCE: the app
 * sends a hash of a random secret up front and the secret itself when redeeming
 * the code, which stops an intercepted redirect from being replayed.
 */
data class PkceChallenge(
    val verifier: String,
    val challenge: String,
) {
    val method: String get() = "S256"

    companion object {
        /**
         * The spec allows a 43–128 character verifier; 32 random bytes encodes to
         * 43 base64url characters, which is the recommended size.
         */
        fun generate(): PkceChallenge {
            val verifier = base64UrlNoPad(randomBytes(32))
            return PkceChallenge(
                verifier = verifier,
                challenge = base64UrlNoPad(sha256(verifier.encodeToByteArray())),
            )
        }

        /** Opaque value echoed through the redirect to detect cross-site forgery. */
        fun randomState(): String = base64UrlNoPad(randomBytes(16))
    }
}

private const val ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * base64url without padding, per RFC 7636 — the standard alphabet's `+` and `/`
 * would be mangled in a URL, and `=` padding is forbidden here.
 */
internal fun base64UrlNoPad(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = (bytes[i].toInt() and 0xFF shl 16) or
            (bytes[i + 1].toInt() and 0xFF shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        out.append(ALPHABET[n ushr 18 and 0x3F])
        out.append(ALPHABET[n ushr 12 and 0x3F])
        out.append(ALPHABET[n ushr 6 and 0x3F])
        out.append(ALPHABET[n and 0x3F])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = bytes[i].toInt() and 0xFF shl 16
            out.append(ALPHABET[n ushr 18 and 0x3F])
            out.append(ALPHABET[n ushr 12 and 0x3F])
        }
        2 -> {
            val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
            out.append(ALPHABET[n ushr 18 and 0x3F])
            out.append(ALPHABET[n ushr 12 and 0x3F])
            out.append(ALPHABET[n ushr 6 and 0x3F])
        }
    }
    return out.toString()
}
