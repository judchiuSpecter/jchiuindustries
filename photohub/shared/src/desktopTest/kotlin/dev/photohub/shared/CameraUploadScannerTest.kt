package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.CameraUploadScanner
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.UploadManager
import dev.photohub.shared.db.PhotoHubDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the camera-upload scan: what gets queued, what is refused, and the
 * conditions under which a scan must do nothing at all.
 */
class CameraUploadScannerTest {

    private fun newDb(): PhotoHubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return PhotoHubDatabase(driver)
    }

    private fun seedLocalSource(db: PhotoHubDatabase) {
        db.sourceQueries.upsert(
            id = "local",
            type = "LOCAL",
            displayName = "This device",
            enabled = 1,
            isUploadTarget = 0,
            configJson = "{}",
            lastSyncedAt = null,
            sortOrder = 0,
            lastSyncError = null,
        )
    }

    private fun seedDrive(db: PhotoHubDatabase, enabled: Long = 1) {
        db.sourceQueries.upsert(
            id = "google_drive",
            type = "GOOGLE_DRIVE",
            displayName = "Google Drive",
            enabled = enabled,
            isUploadTarget = 1,
            configJson = "{}",
            lastSyncedAt = null,
            sortOrder = 1,
            lastSyncError = null,
        )
    }

    /** Writes a real file and indexes it the way MediaIndexer would. */
    private fun indexPhoto(
        db: PhotoHubDatabase,
        dir: File,
        name: String,
        content: String,
        dateTaken: Long = 1_700_000_000_000L,
    ): File {
        val f = File(dir, name).apply { writeText(content) }
        db.mediaItemQueries.upsert(
            id = "local:$name",
            sourceId = "local",
            remoteId = f.absolutePath,
            displayName = name,
            mimeType = "image/jpeg",
            isVideo = 0,
            sizeBytes = content.length.toLong(),
            width = null,
            height = null,
            dateTaken = dateTaken,
            contentHash = null,
            thumbCachePath = null,
            originalCachePath = null,
            syncState = "INDEXED",
            remoteThumbUrl = f.absolutePath,
            indexedAt = 1L,
        )
        return f
    }

    private fun scanner(db: PhotoHubDatabase): CameraUploadScanner =
        CameraUploadScanner(db, MediaRepository(db), UploadManager(db, now = { 1000L }))

    @Test
    fun queuesIndexedLocalPhotosForTheUploadTarget() = runTest {
        val db = newDb().also { seedLocalSource(it); seedDrive(it) }
        val dir = Files.createTempDirectory("cam").toFile()
        indexPhoto(db, dir, "a.jpg", "aaa")
        indexPhoto(db, dir, "b.jpg", "bbb")

        val result = scanner(db).scan()

        assertEquals(2, result.queued)
        assertEquals(2L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
    }

    @Test
    fun doesNothingWhenNoCloudIsTheUploadTarget() = runTest {
        // Before any cloud is linked, a scheduled scan must be a no-op rather
        // than queueing work that can never drain.
        val db = newDb().also { seedLocalSource(it) }
        val dir = Files.createTempDirectory("cam2").toFile()
        indexPhoto(db, dir, "a.jpg", "aaa")

        val result = scanner(db).scan()

        assertEquals(0, result.queued)
        assertTrue(result.reason!!.contains("upload target"), result.reason!!)
        assertEquals(0L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
    }

    @Test
    fun doesNothingWhileTheTargetIsDisconnected() = runTest {
        // Unlink disables the source row rather than deleting it; a scan must
        // respect that and not pile up a queue against a revoked grant.
        val db = newDb().also { seedLocalSource(it); seedDrive(it, enabled = 0) }
        val dir = Files.createTempDirectory("cam3").toFile()
        indexPhoto(db, dir, "a.jpg", "aaa")

        val result = scanner(db).scan()

        assertEquals(0, result.queued)
        assertTrue(result.reason!!.contains("disconnected"), result.reason!!)
    }

    @Test
    fun honoursTheBatchLimitSoOnePassCannotRunForever() = runTest {
        // Enqueue hashes every file, so a first run on a large library must stop
        // and let a later pass continue.
        val db = newDb().also { seedLocalSource(it); seedDrive(it) }
        val dir = Files.createTempDirectory("cam4").toFile()
        repeat(5) { indexPhoto(db, dir, "p$it.jpg", "content-$it") }

        val result = scanner(db).scan(limit = 2)

        assertEquals(2, result.queued)
        assertEquals(2L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
    }

    @Test
    fun alreadyQueuedPhotosAreNotQueuedTwiceAcrossPasses() = runTest {
        val db = newDb().also { seedLocalSource(it); seedDrive(it) }
        val dir = Files.createTempDirectory("cam5").toFile()
        indexPhoto(db, dir, "a.jpg", "aaa")

        val first = scanner(db).scan()
        val second = scanner(db).scan()

        assertEquals(1, first.queued)
        assertEquals(0, second.queued, "a repeated scan must not re-queue the same photo")
        assertEquals(1L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
    }

    @Test
    fun duplicateContentDoesNotStarveLaterPhotosForever() = runTest {
        // Two indexed items with identical bytes dedupe to one queue row by
        // sha256. The second item is therefore never queued — so it must not be
        // re-selected and re-hashed on every future pass, and above all must not
        // consume the batch limit and starve genuinely new photos.
        val db = newDb().also { seedLocalSource(it); seedDrive(it) }
        val dir = Files.createTempDirectory("cam6").toFile()
        // Newest first in dateTaken DESC order, so the duplicates sort ahead.
        indexPhoto(db, dir, "dup-a.jpg", "same", dateTaken = 9_000L)
        indexPhoto(db, dir, "dup-b.jpg", "same", dateTaken = 8_000L)
        indexPhoto(db, dir, "new.jpg", "distinct", dateTaken = 1_000L)

        val s = scanner(db)
        s.scan(limit = 2)
        // Second pass with the same small limit: the un-queueable duplicate must
        // not keep occupying a slot, or "new.jpg" never uploads.
        s.scan(limit = 2)

        val queued = db.uploadQueueQueries.selectByState("QUEUED").executeAsList()
        assertTrue(
            queued.any { it.localPath.endsWith("new.jpg") },
            "distinct photo starved by an un-queueable duplicate; queued=${queued.map { it.localPath.substringAfterLast('/') }}",
        )
    }
}
