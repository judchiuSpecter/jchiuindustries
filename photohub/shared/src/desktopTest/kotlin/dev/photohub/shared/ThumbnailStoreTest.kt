package dev.photohub.shared

import dev.photohub.shared.platform.renderThumbnail
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThumbnailStoreTest {

    private fun writeImage(file: File, w: Int, h: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (x * 7 + y * 13) or 0x202020)
        ImageIO.write(img, "jpg", file)
    }

    @Test
    fun downscalesLargeImageToTheRequestedMaxEdge() = runTest {
        val dir = Files.createTempDirectory("thumb-test").toFile()
        val src = File(dir, "big.jpg").also { writeImage(it, 4000, 3000) }
        val out = File(dir, "out.jpg")

        val ok = renderThumbnail(src.absolutePath, out.absolutePath.toPath(), maxEdge = 384)

        assertTrue(ok)
        assertTrue(out.isFile)
        val result = ImageIO.read(out)
        assertEquals(384, maxOf(result.width, result.height))
        // Aspect ratio preserved: 4000x3000 is 4:3, so 384x288.
        assertEquals(288, minOf(result.width, result.height))
        // The whole point: the thumbnail must be far smaller on disk.
        assertTrue(out.length() < src.length() / 4, "thumb=${out.length()} src=${src.length()}")
    }

    @Test
    fun doesNotUpscaleSmallImages() = runTest {
        val dir = Files.createTempDirectory("thumb-test").toFile()
        val src = File(dir, "small.jpg").also { writeImage(it, 100, 80) }
        val out = File(dir, "out.jpg")

        assertTrue(renderThumbnail(src.absolutePath, out.absolutePath.toPath(), maxEdge = 384))

        val result = ImageIO.read(out)
        assertEquals(100, result.width)
        assertEquals(80, result.height)
    }

    @Test
    fun returnsFalseForUnreadableFiles() = runTest {
        val dir = Files.createTempDirectory("thumb-test").toFile()
        val notAnImage = File(dir, "notes.txt").also { it.writeText("hello") }
        val out = File(dir, "out.jpg")

        assertFalse(renderThumbnail(notAnImage.absolutePath, out.absolutePath.toPath(), 384))
        assertFalse(renderThumbnail(File(dir, "missing.jpg").absolutePath, out.absolutePath.toPath(), 384))
        // A failed render must not leave a partial file behind.
        assertFalse(out.exists())
    }

    @Test
    fun handlesTransparentPngWithoutCorruptingOutput() = runTest {
        val dir = Files.createTempDirectory("thumb-test").toFile()
        val src = File(dir, "alpha.png")
        val img = BufferedImage(1200, 1200, BufferedImage.TYPE_INT_ARGB)
        ImageIO.write(img, "png", src)
        val out = File(dir, "out.jpg")

        assertTrue(renderThumbnail(src.absolutePath, out.absolutePath.toPath(), maxEdge = 384))
        assertEquals(384, ImageIO.read(out).width)
    }
}
