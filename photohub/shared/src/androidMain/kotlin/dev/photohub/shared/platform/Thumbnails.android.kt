package dev.photohub.shared.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import java.io.File

/**
 * Android thumbnail renderer.
 *
 * Prefers [android.content.ContentResolver.loadThumbnail], which returns the
 * thumbnail MediaStore has already generated for the camera roll — the same
 * cache the system gallery uses, so for most photos no decoding happens at all.
 * Falls back to a subsampled decode for items MediaStore has no thumbnail for.
 */
/**
 * Capped lower than desktop: phones throttle under sustained all-core load, and
 * most items hit the MediaStore thumbnail cache rather than decoding anyway, so
 * extra workers buy little and cost battery.
 */
actual val thumbnailConcurrency: Int =
    Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

actual suspend fun renderThumbnail(
    sourceRef: String,
    target: Path,
    maxEdge: Int,
): Boolean = withContext(Dispatchers.IO) {
    val bitmap = loadFromMediaStore(sourceRef, maxEdge)
        ?: decodeSubsampled(sourceRef, maxEdge)
        ?: return@withContext false

    val out = File(target.toString())
    out.parentFile?.mkdirs()
    // Temp-then-rename so an interrupted write cannot leave a truncated file
    // that later passes would mistake for a valid thumbnail.
    val tmp = File(out.absolutePath + ".tmp")
    val ok = runCatching {
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(out)
    }.getOrDefault(false)
    if (!ok) tmp.delete()
    bitmap.recycle()
    ok
}

/** Asks MediaStore for its existing thumbnail. Null when there isn't one. */
private fun loadFromMediaStore(sourceRef: String, maxEdge: Int): Bitmap? {
    if (!sourceRef.startsWith("content://")) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        AndroidPlatform.appContext.contentResolver
            .loadThumbnail(Uri.parse(sourceRef), Size(maxEdge, maxEdge), null)
    }.getOrNull()
}

/**
 * Decodes the image at a power-of-two sample size, reading only the header
 * first so the full bitmap is never allocated at original resolution.
 */
private fun decodeSubsampled(sourceRef: String, maxEdge: Int): Bitmap? = runCatching {
    val resolver = AndroidPlatform.appContext.contentResolver
    val uri = if (sourceRef.startsWith("content://")) Uri.parse(sourceRef) else null

    fun open() = if (uri != null) resolver.openInputStream(uri) else File(sourceRef).inputStream()

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    open()?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()
