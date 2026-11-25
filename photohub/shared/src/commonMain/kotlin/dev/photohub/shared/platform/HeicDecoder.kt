package dev.photohub.shared.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import openize.heic.decoder.HeicImage
import openize.heic.decoder.PixelFormat
import openize.io.IOMode
import openize.io.IOFileStream
import java.io.File

/**
 * HEIC/HEIF decoding shared by both targets.
 *
 * iPhone HEIC files store a small, non-derived preview image alongside the
 * full-resolution grid. Decoding that preview is two orders of magnitude
 * cheaper than expanding the grid (milliseconds versus ~10 seconds and roughly
 * 800 MB of peak heap), so thumbnails always prefer it. Files without a visible
 * preview fall back to the full image on desktop; [decodeFull] serializes those
 * decodes because even one uses enough memory that two would risk an OOM.
 */
object HeicDecoder {

    /** RGBA pixels in [width]/[height] order, as produced by Openize's Rgba32 format. */
    data class Rgba(val width: Int, val height: Int, val pixels: ByteArray)

    /** Upper bound before a full-grid decode is refused; protects against pathological files. */
    private const val MAX_FULL_PIXELS = 40_000_000

    private val heavyDecodeLock = Mutex()

    /**
     * True when [file] has an ISO-BMFF `ftyp` box with a HEIF-compatible brand.
     * Extension checks are unreliable for camera-roll files copied through
     * content providers, so both targets sniff the header instead.
     */
    fun isHeic(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            isHeicHeader(header, read)
        }
    }.getOrDefault(false)

    fun isHeicHeader(header: ByteArray, length: Int): Boolean {
        if (length < 12) return false
        if (header[4] != 'f'.code.toByte() || header[5] != 't'.code.toByte() ||
            header[6] != 'y'.code.toByte() || header[7] != 'p'.code.toByte()
        ) return false

        val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
        return brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")
    }

    /**
     * Decodes the small embedded preview, or null when the file only has the
     * full-resolution grid. Hidden frames are deliberately excluded: iPhone
     * HID/auxiliary frames can be full-size but show a depth/mask image rather
     * than the photograph.
     */
    fun decodePreview(file: File): Rgba? = runCatching {
        IOFileStream(file, IOMode.READ).use { stream ->
            val image = HeicImage.load(stream)
            val default = image.defaultFrame
            val defaultWidth = default.width

            val preview = image.allFrames.values.asSequence()
                .filter { frame ->
                    frame.isImage && !frame.isDerived && !frame.isHidden &&
                        frame.width in 1 until defaultWidth && frame.height > 0
                }
                // Several files carry both a 240x320 preview and larger
                // auxiliary frames; the largest visible preview is the one
                // intended to represent the photograph.
                .maxByOrNull { it.width * it.height }
                ?: return null

            val bytes = preview.getByteArray(PixelFormat.Rgba32) ?: return null
            val width = preview.width.toInt()
            val height = preview.height.toInt()
            if (bytes.size < width * height * 4) return null
            Rgba(width, height, bytes)
        }
    }.getOrNull()

    /**
     * Decodes the default (usually grid) image as a last resort. The caller
     * must downscale immediately; holding many of these at once exhausts heap.
     */
    suspend fun decodeFull(file: File): Rgba? = heavyDecodeLock.withLock {
        runCatching {
            IOFileStream(file, IOMode.READ).use { stream ->
                val image = HeicImage.load(stream)
                val width = image.width.toInt()
                val height = image.height.toInt()
                if (width <= 0 || height <= 0 || width.toLong() * height > MAX_FULL_PIXELS) return null
                val bytes = image.getByteArray(PixelFormat.Rgba32) ?: return null
                if (bytes.size < width * height * 4) return null
                Rgba(width, height, bytes)
            }
        }.getOrNull()
    }
}
