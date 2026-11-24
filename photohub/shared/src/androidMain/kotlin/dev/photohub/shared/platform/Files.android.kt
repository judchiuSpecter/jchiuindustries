package dev.photohub.shared.platform

import android.net.Uri
import android.provider.OpenableColumns
import dev.photohub.shared.platform.MaterializedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Opens [ref] whether it is a `content://` URI or a filesystem path.
 *
 * MediaStore identifies photos by content URI, and on Android 10+ the
 * underlying file path is often unreadable even with media permissions. So the
 * ContentResolver is the only reliable way to get the bytes, and callers must
 * not assume a path.
 */
private fun openStream(ref: String): InputStream? = runCatching {
    if (ref.startsWith("content://")) {
        AndroidPlatform.appContext.contentResolver.openInputStream(Uri.parse(ref))
    } else {
        File(ref).takeIf { it.isFile }?.inputStream()
    }
}.getOrNull()

/**
 * Streams the bytes through SHA-256 in fixed-size blocks.
 *
 * Camera uploads routinely include multi-gigabyte videos, so the digest is fed
 * incrementally and memory use stays flat instead of scaling with file size.
 */
actual suspend fun sha256OfFile(ref: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        openStream(ref)?.use { stream ->
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return@withContext null
        digest.digest().toHexString()
    }.getOrNull()
}

/**
 * Content URIs have no filesystem path a foreign SDK could open, so copy the
 * bytes into the app's private cache and hand over that path instead.
 */
actual suspend fun materializeLocalFile(ref: String): MaterializedFile =
    withContext(Dispatchers.IO) {
        if (!ref.startsWith("content://")) {
            val path = with(okio.Path.Companion) { ref.toPath() }
            return@withContext MaterializedFile(path, false)
        }

        val input = AndroidPlatform.appContext.contentResolver.openInputStream(Uri.parse(ref))
            ?: error("Cannot open $ref")
        val dir = cacheDir / "upload-tmp"
        fileSystem.createDirectories(dir)
        val target = dir / "upload-${UUID.randomUUID()}.tmp"
        input.use { stream ->
            fileSystem.write(target) {
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    write(buffer, 0, read)
                }
            }
        }
        MaterializedFile(target, true)
    }

/**
 * Size of [ref], via the ContentResolver for content URIs.
 *
 * Falls back to counting the stream when the provider does not report a size,
 * which some providers genuinely do not.
 */
actual fun fileSize(ref: String): Long? {
    if (!ref.startsWith("content://")) {
        return File(ref).takeIf { it.isFile }?.length()
    }

    val resolver = AndroidPlatform.appContext.contentResolver
    val uri = runCatching { Uri.parse(ref) }.getOrNull() ?: return null

    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
    }

    // Last resort: the provider knows the bytes even when it omits the column.
    return runCatching {
        openStream(ref)?.use { stream ->
            var total = 0L
            val buffer = ByteArray(HASH_BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
            }
            total
        }
    }.getOrNull()
}

private const val HASH_BUFFER = 64 * 1024
private const val COPY_BUFFER = 64 * 1024

internal fun ByteArray.toHexString(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) {
        for (b in this@toHexString) {
            append(hex[(b.toInt() shr 4) and 0xF])
            append(hex[b.toInt() and 0xF])
        }
    }
}

/**
 * Reads a byte range, seeking by skipping since a content stream is not
 * seekable. Upload chunks are read in increasing order, so this stays linear
 * overall rather than re-reading the whole file per chunk.
 */
actual suspend fun readChunk(ref: String, offset: Long, length: Int): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            openStream(ref)?.use { stream ->
                var remaining = offset
                while (remaining > 0) {
                    val skipped = stream.skip(remaining)
                    if (skipped <= 0) return@withContext null
                    remaining -= skipped
                }

                val buffer = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    val read = stream.read(buffer, filled, length - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == length) buffer else buffer.copyOf(filled)
            }
        }.getOrNull()
    }

/** Asks the ContentResolver for the real name behind a content URI. */
actual fun displayName(ref: String): String {
    if (!ref.startsWith("content://")) return File(ref).name

    val uri = runCatching { Uri.parse(ref) }.getOrNull() ?: return ref.substringAfterLast('/')
    runCatching {
        AndroidPlatform.appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getString(index)
                }
            }
    }
    return ref.substringAfterLast('/')
}
