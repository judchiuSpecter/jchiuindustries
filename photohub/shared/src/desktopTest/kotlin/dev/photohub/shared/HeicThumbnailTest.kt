package dev.photohub.shared

import dev.photohub.shared.platform.renderThumbnail
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HEIC fixtures are personal photo data, so they are never committed. Set
 * PHOTOHUB_HEIC_TEST_FILE to a local .heic/.heif file to exercise the decoder;
 * without it the test is a no-op so CI stays hermetic.
 */
class HeicThumbnailTest {

    @Test
    fun rendersAHeicThumbnail() = runTest {
        val source = System.getenv("PHOTOHUB_HEIC_TEST_FILE") ?: return@runTest
        val src = File(source)
        if (!src.isFile) return@runTest

        val dir = Files.createTempDirectory("heic-thumb-test").toFile()
        val out = File(dir, "out.jpg")

        assertTrue(renderThumbnail(src.absolutePath, out.absolutePath.toPath(), maxEdge = 384))
        assertTrue(out.isFile)
        val result: BufferedImage = ImageIO.read(out)
        assertTrue(result.width > 0 && result.height > 0)
        // Small embedded previews are used as-is rather than upscaled.
        assertTrue(maxOf(result.width, result.height) <= 384)
    }
}
