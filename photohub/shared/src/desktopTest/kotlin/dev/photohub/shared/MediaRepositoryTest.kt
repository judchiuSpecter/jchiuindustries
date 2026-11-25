package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MediaRepositoryTest {

    private fun newDb(): PhotoHubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return PhotoHubDatabase(driver)
    }

    @Test
    fun emptyTimelineIsEmpty() {
        val repo = MediaRepository(newDb())
        assertEquals(0, repo.timelinePage(limit = 50, offset = 0).size)
        assertEquals(0L, repo.totalCount())
    }

    @Test
    fun insertedItemAppearsInTimelineNewestFirst() {
        val db = newDb()
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
        // Two items; the newer dateTaken must come first.
        db.mediaItemQueries.upsert(
            id = "local:1", sourceId = "local", remoteId = "1",
            displayName = "older.jpg", mimeType = "image/jpeg", isVideo = 0,
            sizeBytes = 100, width = null, height = null, dateTaken = 1000,
            contentHash = null, thumbCachePath = null, originalCachePath = null,
            syncState = "INDEXED", remoteThumbUrl = null, indexedAt = 0,
        )
        db.mediaItemQueries.upsert(
            id = "local:2", sourceId = "local", remoteId = "2",
            displayName = "newer.jpg", mimeType = "image/jpeg", isVideo = 0,
            sizeBytes = 100, width = null, height = null, dateTaken = 2000,
            contentHash = null, thumbCachePath = null, originalCachePath = null,
            syncState = "INDEXED", remoteThumbUrl = null, indexedAt = 0,
        )

        val repo = MediaRepository(db)
        val page = repo.timelinePage(limit = 50, offset = 0)
        assertEquals(2, page.size)
        assertEquals("newer.jpg", page.first().displayName)
        assertNotNull(repo.itemById("local:1"))
    }

    @Test
    fun onlyOneArchiveTargetAllowed() {
        val repo = MediaRepository(newDb())
        repo.upsertCloudSource("drive_a", SourceType.GOOGLE_DRIVE, "Drive A")
        repo.upsertCloudSource("drive_b", SourceType.GOOGLE_DRIVE, "Drive B")

        repo.setArchiveTarget("drive_a")
        repo.setArchiveTarget("drive_b")

        assertEquals("drive_b", repo.archiveTarget()?.id)
        assertEquals(
            1,
            repo.allSources().count { it.isArchiveTarget },
            "moving the slot must clear the previous target",
        )
    }

    @Test
    fun anExistingNasIsAdoptedAsArchiveTarget() {
        val repo = MediaRepository(newDb())
        repo.upsertCloudSource(
            id = "nas_legacy",
            type = SourceType.NAS_ARCHIVE,
            displayName = "NAS",
        )

        // Existing installs had no archive-target column; startup must not leave
        // Archive unusable until the user finds the setting.
        repo.ensureLocalSource()

        assertEquals("nas_legacy", repo.archiveTarget()?.id)
    }

    @Test
    fun onlyOneUploadTargetAllowed() {
        val db = newDb()
        db.sourceQueries.upsert(
            id = "drive", type = "GOOGLE_DRIVE", displayName = "Drive",
            enabled = 1, isUploadTarget = 1, configJson = "{}",
            lastSyncedAt = null, sortOrder = 0, lastSyncError = null,
        )
        db.sourceQueries.clearAllUploadTargets()
        db.sourceQueries.upsert(
            id = "mega", type = "MEGA", displayName = "MEGA",
            enabled = 1, isUploadTarget = 1, configJson = "{}",
            lastSyncedAt = null, sortOrder = 1, lastSyncError = null,
        )
        val target = db.sourceQueries.selectUploadTarget().executeAsOneOrNull()
        assertEquals("mega", target?.id)
    }
}
