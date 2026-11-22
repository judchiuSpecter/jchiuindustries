package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.MediaIndexer
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.LocalSourceConfig
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.source.LocalDirectorySource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDirectorySourceTest {

    private fun newDb(): PhotoHubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return PhotoHubDatabase(driver)
    }

    private fun sourceFor(root: java.nio.file.Path): SourceConfig = SourceConfig(
        id = LocalSourceConfig.SOURCE_ID,
        type = SourceType.LOCAL,
        displayName = "This device",
        enabled = true,
        isUploadTarget = false,
        configJson = Json.encodeToString(
            LocalSourceConfig(roots = listOf(root.toAbsolutePath().toString())),
        ),
    )

    @Test
    fun findsSupportedMediaAndSkipsOtherFiles() = runTest {
        val root = Files.createTempDirectory("photohub-test")
        root.resolve("photo.jpg").writeText("x")
        root.resolve("clip.mp4").writeText("x")
        root.resolve("notes.txt").writeText("x")
        root.resolve("archive.zip").writeText("x")

        val page = LocalDirectorySource(sourceFor(root)).list(null).getOrThrow()
        val names = page.items.map { it.displayName }.toSet()

        assertEquals(setOf("photo.jpg", "clip.mp4"), names)
        assertTrue(page.items.single { it.displayName == "clip.mp4" }.isVideo)
        assertTrue(!page.items.single { it.displayName == "photo.jpg" }.isVideo)
    }

    @Test
    fun walksNestedDirectories() = runTest {
        val root = Files.createTempDirectory("photohub-test")
        root.resolve("2026/01").createDirectories()
        root.resolve("2026/01/nested.png").writeText("x")

        val page = LocalDirectorySource(sourceFor(root)).list(null).getOrThrow()
        assertEquals(listOf("nested.png"), page.items.map { it.displayName })
    }

    @Test
    fun excludesVideosWhenConfiguredTo() = runTest {
        val root = Files.createTempDirectory("photohub-test")
        root.resolve("photo.jpg").writeText("x")
        root.resolve("clip.mp4").writeText("x")

        val config = sourceFor(root).copy(
            configJson = Json.encodeToString(
                LocalSourceConfig(
                    roots = listOf(root.toAbsolutePath().toString()),
                    includeVideos = false,
                ),
            ),
        )

        val page = LocalDirectorySource(config).list(null).getOrThrow()
        assertEquals(listOf("photo.jpg"), page.items.map { it.displayName })
    }

    @Test
    fun indexerWritesRowsThatTheTimelineCanRead() = runTest {
        val root = Files.createTempDirectory("photohub-test")
        root.resolve("a.jpg").writeText("x")
        root.resolve("b.jpg").writeText("x")

        val db = newDb()
        val repo = MediaRepository(db)
        repo.ensureLocalSource()

        val stats = MediaIndexer(db)
            .index(LocalDirectorySource(sourceFor(root)))
            .getOrThrow()

        assertEquals(2, stats.indexed)
        assertEquals(0, stats.removed)
        assertEquals(2L, repo.totalCount())
    }

    @Test
    fun reindexingAfterDeletionPrunesTheMissingRow() = runTest {
        val root = Files.createTempDirectory("photohub-test")
        val keep = root.resolve("keep.jpg").also { it.writeText("x") }
        val remove = root.resolve("remove.jpg").also { it.writeText("x") }

        val db = newDb()
        val repo = MediaRepository(db)
        repo.ensureLocalSource()
        val indexer = MediaIndexer(db)
        val source = LocalDirectorySource(sourceFor(root))

        indexer.index(source).getOrThrow()
        assertEquals(2L, repo.totalCount())

        Files.delete(remove)
        val stats = indexer.index(source).getOrThrow()

        assertEquals(1, stats.indexed)
        assertEquals(1, stats.removed)
        assertEquals(1L, repo.totalCount())
        assertEquals(
            listOf("keep.jpg"),
            repo.timelinePage(limit = 50, offset = 0).map { it.displayName },
        )
        assertTrue(Files.exists(keep))
    }

    @Test
    fun reindexingIsIdempotent() = runTest {
        val root = Files.createTempDirectory("photohub-test")
        root.resolve("a.jpg").writeText("x")

        val db = newDb()
        val repo = MediaRepository(db)
        repo.ensureLocalSource()
        val indexer = MediaIndexer(db)
        val source = LocalDirectorySource(sourceFor(root))

        indexer.index(source).getOrThrow()
        indexer.index(source).getOrThrow()

        // Re-indexing the same file must update the row, not add a second one.
        assertEquals(1L, repo.totalCount())
    }
}
