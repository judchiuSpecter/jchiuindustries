package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.MediaIndexer
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.groupByDay
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.LocalSourceConfig
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.source.LocalDirectorySource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Indexes the real Pictures folder on this machine to confirm the desktop source
 * works against actual files rather than fixtures. Skipped when the folder is
 * absent so the suite stays portable.
 */
class RealPicturesSmokeTest {

    @Test
    fun indexesTheUsersPicturesFolder() = runTest {
        val pictures = File(System.getProperty("user.home"), "Pictures")
        if (!pictures.isDirectory) return@runTest

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        val db = PhotoHubDatabase(driver)
        val repo = MediaRepository(db)
        repo.ensureLocalSource()

        val config = SourceConfig(
            id = LocalSourceConfig.SOURCE_ID,
            type = SourceType.LOCAL,
            displayName = "This device",
            enabled = true,
            isUploadTarget = false,
            configJson = Json.encodeToString(
                LocalSourceConfig(roots = listOf(pictures.absolutePath)),
            ),
        )

        val stats = MediaIndexer(db).index(LocalDirectorySource(config)).getOrThrow()
        val items = repo.timelinePage(limit = 500, offset = 0)

        println("[smoke] indexed=${stats.indexed} rows=${items.size}")
        items.take(5).forEach {
            println("[smoke]   ${it.displayName} ${it.mimeType} ${it.sizeBytes}B taken=${it.dateTaken}")
        }
        println("[smoke] day sections=${items.groupByDay().size}")

        // Non-media files in the folder must not be indexed.
        assertTrue(items.none { it.displayName.endsWith(".csv") })
        // Every indexed row must have a usable type and a real size.
        assertTrue(items.all { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") })
        assertTrue(items.all { it.sizeBytes > 0 })
        // Timeline must come back newest-first.
        assertTrue(items.map { it.dateTaken } == items.map { it.dateTaken }.sortedDescending())
    }
}
