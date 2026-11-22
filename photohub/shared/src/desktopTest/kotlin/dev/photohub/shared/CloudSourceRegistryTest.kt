package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSourceRegistryTest {

    private fun repo(): MediaRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return MediaRepository(PhotoHubDatabase(driver))
    }

    @Test
    fun registersADriveSourceAsTheUploadTarget() {
        val repo = repo()
        repo.ensureLocalSource()

        val drive = repo.upsertCloudSource(
            id = "google_drive",
            type = SourceType.GOOGLE_DRIVE,
            displayName = "Google Drive",
            makeUploadTarget = true,
        )

        assertTrue(drive.isUploadTarget)
        assertEquals("google_drive", repo.uploadTarget()?.id)
        assertEquals(2, repo.allSources().size)
    }

    @Test
    fun onlyOneSourceCanBeTheUploadTarget() {
        val repo = repo()
        repo.upsertCloudSource("google_drive", SourceType.GOOGLE_DRIVE, "Drive", makeUploadTarget = true)
        repo.upsertCloudSource("mega", SourceType.MEGA, "MEGA", makeUploadTarget = true)

        // Two targets would mean every photo uploads twice.
        assertEquals("mega", repo.uploadTarget()?.id)
        assertEquals(1, repo.allSources().count { it.isUploadTarget })
    }

    @Test
    fun relinkingPreservesExistingConfigAndTargetFlag() {
        val repo = repo()
        repo.upsertCloudSource(
            id = "google_drive",
            type = SourceType.GOOGLE_DRIVE,
            displayName = "Drive",
            configJson = """{"rootFolderName":"MyPhotos","rootFolderId":"abc"}""",
            makeUploadTarget = true,
        )

        // Re-link without passing config, as a token refresh flow would.
        val updated = repo.upsertCloudSource(
            id = "google_drive",
            type = SourceType.GOOGLE_DRIVE,
            displayName = "Google Drive (jud@example.com)",
            makeUploadTarget = false,
        )

        // Losing the folder id would make the app create a second PhotoHub
        // folder in Drive; losing the flag would silently stop camera upload.
        assertTrue(updated.configJson.contains("MyPhotos"))
        assertTrue(updated.configJson.contains("abc"))
        assertTrue(updated.isUploadTarget)
        assertEquals("Google Drive (jud@example.com)", updated.displayName)
    }

    @Test
    fun deletingASourceRemovesItsMediaRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        val db = PhotoHubDatabase(driver)
        // Cascade only fires when SQLite's foreign keys are on.
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        val repo = MediaRepository(db)

        repo.upsertCloudSource("google_drive", SourceType.GOOGLE_DRIVE, "Drive")
        db.mediaItemQueries.upsert(
            id = "google_drive:f1",
            sourceId = "google_drive",
            remoteId = "f1",
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = 0,
            sizeBytes = 100,
            dateTaken = 1000,
            width = null,
            height = null,
            contentHash = null,
            remoteThumbUrl = null,
            thumbCachePath = null,
            originalCachePath = null,
            syncState = "INDEXED",
            indexedAt = 1000,
        )
        assertEquals(1L, repo.totalCount())

        repo.deleteSource("google_drive")

        // Orphan rows would show as permanently-broken thumbnails.
        assertEquals(0L, repo.totalCount())
        assertNull(repo.sourceById("google_drive"))
    }
}
