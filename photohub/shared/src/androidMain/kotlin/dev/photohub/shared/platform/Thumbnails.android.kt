package dev.photohub.shared.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
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
 *
 * HEIC is special: BitmapFactory cannot read it. Embedded previews are decoded
 * by the shared pure-Java helper; for files without one, Android's own hardware
 * decoder is tried before falling back to the much more expensive grid decode.
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
        ?: decodeFallback(sourceRef, maxEdge)
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

private suspend fun decodeFallback(sourceRef: String, maxEdge: Int): Bitmap? {
    if (sourceRef.startsWith("content://")) {
        val staged = stageContentUri(sourceRef) ?: return decodeSubsampled(sourceRef, maxEdge)
        return try {
            if (HeicDecoder.isHeic(staged)) decodeHeic(staged, maxEdge)
            else decodeSubsampled(sourceRef, maxEdge)
        } finally {
            staged.delete()
        }
    }

    val file = File(sourceRef)
    if (file.isFile && HeicDecoder.isHeic(file)) return decodeHeic(file, maxEdge)
    return decodeSubsampled(sourceRef, maxEdge)
}

/**
 * Openize needs random access to the ISO-BMFF boxes, so a content:// URI has to
 * be copied into the app-private cache before it can be inspected. The copy is
 * always deleted, including when decoding fails.
 */
private fun stageContentUri(sourceRef: String): File? {
    val uri = Uri.parse(sourceRef)
    val dir = File(AndroidPlatform.appContext.cacheDir, "photohub/heic-in")
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val staged = File(dir, "thumb-${System.currentTimeMillis()}-${(0..Int.MAX_VALUE).random()}.heic")
    val ok = runCatching {
        AndroidPlatform.appContext.contentResolver.openInputStream(uri)?.use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        } != null
    }.getOrDefault(false)
    return staged.takeIf { ok && it.length() > 0 } ?: run { staged.delete(); null }
}

private suspend fun decodeHeic(file: File, maxEdge: Int): Bitmap? {
    HeicDecoder.decodePreview(file)?.let { return it.toBitmap().fit(maxEdge) }

    // Platform decoder uses the hardware HEVC codec and is dramatically cheaper
    // than expanding the full-resolution grid ourselves.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(file, maxEdge)?.let { return it }
    }

    HeicDecoder.decodeFull(file)?.let { return it.toBitmap().fit(maxEdge) }
    return null
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(file: File, maxEdge: Int): Bitmap? = runCatching {
    val source = ImageDecoder.createSource(file)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        val width = info.size.width
        val height = info.size.height
        val ratio = maxEdge.toFloat() / maxOf(width, height)
        decoder.setTargetSize(
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
        )
    }
}.getOrNull()

private fun HeicDecoder.Rgba.toBitmap(): Bitmap {
    val colors = IntArray(width * height)
    var i = 0
    var p = 0
    while (p < colors.size) {
        val r = pixels[i++].toInt() and 0xff
        val g = pixels[i++].toInt() and 0xff
        val b = pixels[i++].toInt() and 0xff
        i++ // alpha; JPEG output is opaque
        colors[p++] = (0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
}

private fun Bitmap.fit(maxEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    val scaled = Bitmap.createScaledBitmap(
        this,
        (width * ratio).toInt().coerceAtLeast(1),
        (height * ratio).toInt().coerceAtLeast(1),
        true,
    )
    recycle()
    return scaled
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
