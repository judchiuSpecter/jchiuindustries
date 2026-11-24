package dev.photohub.shared.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Streams the file through SHA-256 in fixed-size blocks.
 *
 * Hashing is done incrementally so memory use stays constant regardless of file
 * size — a 4 GB video costs the same as a photo.
 */
actual suspend fun sha256OfFile(ref: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(ref).inputStream().use { stream ->
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHexString()
    }.getOrNull()
}

actual fun fileSize(ref: String): Long? =
    File(ref).takeIf { it.isFile }?.length()

/** Desktop references are already real paths; no copy is needed. */
actual suspend fun materializeLocalFile(ref: String): MaterializedFile =
    MaterializedFile(with(okio.Path.Companion) { ref.toPath() }, false)

private const val HASH_BUFFER = 64 * 1024

internal fun ByteArray.toHexString(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) {
        for (b in this@toHexString) {
            append(hex[(b.toInt() shr 4) and 0xF])
            append(hex[b.toInt() and 0xF])
        }
    }
}

/** Desktop references are always real paths, so this is a plain seek + read. */
actual suspend fun readChunk(ref: String, offset: Long, length: Int): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            java.io.RandomAccessFile(java.io.File(ref), "r").use { file ->
                file.seek(offset)
                val buffer = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    val read = file.read(buffer, filled, length - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == length) buffer else buffer.copyOf(filled)
            }
        }.getOrNull()
    }

actual fun displayName(ref: String): String = java.io.File(ref).name
