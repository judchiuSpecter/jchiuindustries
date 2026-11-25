package dev.photohub.shared.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop thumbnail renderer built on ImageIO.
 *
 * Decoding is subsampled: rather than reading a 4K original into memory and then
 * shrinking it, the reader is told to skip pixels while decoding, which keeps
 * peak memory and CPU proportional to the thumbnail rather than the original.
 */
/**
 * Desktop can use every core: a thumbnail pass is a foreground action the user
 * is waiting on, and leaving one core free keeps the UI responsive.
 */
actual val thumbnailConcurrency: Int =
    (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)

actual suspend fun renderThumbnail(
    sourceRef: String,
    target: Path,
    maxEdge: Int,
): Boolean = withContext(Dispatchers.IO) {
    val file = File(sourceRef)
    if (!file.isFile) return@withContext false

    val scaled = if (HeicDecoder.isHeic(file)) {
        val heic = HeicDecoder.decodePreview(file) ?: HeicDecoder.decodeFull(file)
            ?: return@withContext false
        scaleToFit(heic.toBufferedImage(), maxEdge)
    } else {
        val decoded = decodeSubsampled(file, maxEdge) ?: return@withContext false
        scaleToFit(decoded, maxEdge)
    }

    val out = File(target.toString())
    out.parentFile?.mkdirs()
    // Write to a temp file then move, so a crash mid-write cannot leave a
    // truncated thumbnail that would be treated as valid on the next run.
    val tmp = File(out.absolutePath + ".tmp")
    val ok = runCatching {
        ImageIO.write(scaled, "jpg", tmp) && tmp.renameTo(out)
    }.getOrDefault(false)
    if (!ok) tmp.delete()
    ok
}

/** Decodes [file] at the smallest power-of-two subsampling that still covers [maxEdge]. */
private fun decodeSubsampled(file: File, maxEdge: Int): BufferedImage? = runCatching {
    val stream = ImageIO.createImageInputStream(file) ?: return null
    stream.use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.setInput(input, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)

            var step = 1
            while (minOf(width, height) / (step * 2) >= maxEdge) step *= 2

            val params = reader.defaultReadParam.apply {
                if (step > 1) setSourceSubsampling(step, step, 0, 0)
            }
            reader.read(0, params)
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()

/** Scales [src] so its longest edge is [maxEdge], preserving aspect ratio. */
private fun scaleToFit(src: BufferedImage, maxEdge: Int): BufferedImage {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxEdge) return src.toRgb()

    val ratio = maxEdge.toDouble() / longest
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)

    // TYPE_INT_RGB because JPEG has no alpha channel; PNGs with transparency
    // would otherwise write out with corrupted colors.
    return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().run {
            setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY,
            )
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
    }
}

/** Converts Openize's Rgba32 bytes into a Java2D image for scaling/encoding. */
private fun HeicDecoder.Rgba.toBufferedImage(): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = pixels[i++].toInt() and 0xff
                val g = pixels[i++].toInt() and 0xff
                val b = pixels[i++].toInt() and 0xff
                i++ // alpha; JPEG output is opaque
                setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
    }

private fun BufferedImage.toRgb(): BufferedImage {
    if (type == BufferedImage.TYPE_INT_RGB) return this
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().run {
            drawImage(this@toRgb, 0, 0, null)
            dispose()
        }
    }
}
