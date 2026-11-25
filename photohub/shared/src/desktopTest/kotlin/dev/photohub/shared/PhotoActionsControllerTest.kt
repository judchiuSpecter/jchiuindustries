package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.DriveAccountFactory
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.MegaAccounts
import dev.photohub.shared.auth.NasAccounts
import dev.photohub.shared.auth.RedirectCapture
import dev.photohub.shared.auth.RedirectResult
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.PhotoActionsController
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.model.StorageQuota
import dev.photohub.shared.source.MediaPage
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.SourceRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the explicit Delete/Archive pipeline. The important invariant is that
 * a source copy is never deleted until the destination has been verified, and a
 * failed action leaves the indexed item in place for a safe retry.
 */
class PhotoActionsControllerTest {

    private class FakeSource(
        override val config: SourceConfig,
        private val bytes: String = "original bytes",
        private val deleteAllowed: Boolean = true,
        private val uploadAllowed: Boolean = true,
        var deleteFails: Boolean = false,
        var corruptReadback: Boolean = false,
    ) : MediaSource {
        val deletedIds = mutableListOf<String>()
        val uploaded = mutableMapOf<String, String>()
        var uploads = 0
            private set

        override val canDelete: Boolean get() = deleteAllowed
        override val canUpload: Boolean get() = uploadAllowed

        override suspend fun list(cursor: String?): Result<MediaPage> =
            Result.success(MediaPage(emptyList(), null))

        override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
            Result.failure(UnsupportedOperationException("not used"))

        override suspend fun downloadOriginal(
            item: RemoteMedia,
            target: Path,
            onProgress: (Float) -> Unit,
        ): Result<Path> = runCatching {
            FileSystem.SYSTEM.createDirectories(target.parent!!)
            val recorded = uploaded[item.remoteId]
            FileSystem.SYSTEM.write(target) {
                when {
                    recorded == null -> writeUtf8(bytes)
                    corruptReadback -> writeUtf8("corrupted bytes")
                    else -> writeUtf8(File(recorded).readText())
                }
            }
            onProgress(1f)
            target
        }

        override suspend fun upload(
            local: Path,
            remoteFolder: String,
            onProgress: (Float) -> Unit,
        ): Result<RemoteMedia> = runCatching {
            uploads++
            val id = "upload-$uploads"
            uploaded[id] = local.toString()
            onProgress(1f)
            RemoteMedia(
                remoteId = id,
                displayName = local.name,
                mimeType = "image/jpeg",
                isVideo = false,
                sizeBytes = File(local.toString()).length(),
                dateTakenEpochMs = 0L,
            )
        }

        override suspend fun delete(item: RemoteMedia): Result<Unit> {
            if (deleteFails) return Result.failure(IllegalStateException("delete failed"))
            deletedIds += item.remoteId
            return Result.success(Unit)
        }
    }

    private class FakeMegas(private val byId: Map<String, MediaSource>) : MegaAccounts {
        override fun isLinked(sourceId: String) = true
        override suspend fun link(sourceId: String, email: String, password: String, mfa: String?) =
            Result.success("test")
        override suspend fun unlink(sourceId: String) = Unit
        override suspend fun quota(sourceId: String): StorageQuota? = null
        override suspend fun sourceFor(config: SourceConfig): MediaSource =
            byId[config.id] ?: error("No fake source for ${config.id}")
        override suspend fun targetFor(config: SourceConfig): UploadTarget = error("not used")
    }

    private object FakeNas : NasAccounts {
        override fun isLinked(sourceId: String) = true
        override suspend fun link(
            sourceId: String,
            host: String,
            port: Int,
            username: String,
            password: String,
            remotePath: String,
        ) = Result.success("test")
        override suspend fun unlink(sourceId: String) = Unit
        override suspend fun sourceFor(config: SourceConfig): MediaSource = error("not used")
    }

    private fun unusedDriveFactory(): DriveAccountFactory {
        val store = object : CredentialStore {
            override fun get(key: String) = null
            override fun put(key: String, value: String) = Unit
            override fun remove(key: String) = Unit
            override fun removeAll(keyPrefix: String) = Unit
        }
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        val oauth = GoogleOAuthClient(http, GoogleOAuthConfig(clientId = "unused"), now = { 0L })
        val capture = object : RedirectCapture {
            override fun begin() = "http://127.0.0.1:0"
            override suspend fun await() = Result.success(RedirectResult(null, null, "unused"))
            override fun cancel() {}
        }
        return DriveAccountFactory(store, http, oauth, capture, now = { 0L })
    }

    private class Harness(
        val controller: PhotoActionsController,
        val repository: MediaRepository,
        val db: PhotoHubDatabase,
    ) {
        fun insertItem(id: String = "src:1", sourceId: String = "src") {
            db.mediaItemQueries.upsert(
                id = id,
                sourceId = sourceId,
                remoteId = "src-id",
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                isVideo = 0,
                sizeBytes = 14L,
                width = null,
                height = null,
                dateTaken = 1_000L,
                contentHash = null,
                thumbCachePath = null,
                originalCachePath = null,
                syncState = "INDEXED",
                remoteThumbUrl = null,
                indexedAt = 0L,
            )
        }

        fun item(id: String = "src:1"): MediaItem = repository.itemById(id)!!
    }

    private fun harness(
        source: FakeSource,
        target: FakeSource,
        networkAllowed: () -> Boolean = { true },
        setTarget: Boolean = true,
    ): Harness {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        val db = PhotoHubDatabase(driver)
        val repository = MediaRepository(db)
        repository.upsertCloudSource("src", SourceType.MEGA, "Source account")
        repository.upsertCloudSource("dst", SourceType.MEGA, "Archive account")
        if (setTarget) repository.setArchiveTarget("dst")

        val controller = PhotoActionsController(
            repository = repository,
            sources = SourceRegistry(
                drives = unusedDriveFactory(),
                megas = FakeMegas(mapOf("src" to source, "dst" to target)),
                nas = FakeNas,
            ),
            networkAllowed = networkAllowed,
        )
        return Harness(controller, repository, db)
    }

    private fun config(id: String) = SourceConfig(
        id = id,
        type = SourceType.MEGA,
        displayName = id,
        enabled = true,
        isUploadTarget = false,
        configJson = "{}",
    )

    @Test
    fun deleteRemovesTheSourceCopyAndTheIndexRow() = runTest {
        val source = FakeSource(config("src"))
        val h = harness(source, FakeSource(config("dst")))
        h.insertItem()

        val outcome = h.controller.delete(listOf(h.item())).getOrThrow()

        assertEquals(1, outcome.succeeded)
        assertEquals(listOf("src-id"), source.deletedIds)
        assertNull(h.repository.itemById("src:1"))
    }

    @Test
    fun failedDeleteKeepsTheIndexRowForARetry() = runTest {
        val source = FakeSource(config("src"), deleteFails = true)
        val h = harness(source, FakeSource(config("dst")))
        h.insertItem()

        val outcome = h.controller.delete(listOf(h.item())).getOrThrow()

        assertEquals(1, outcome.failed)
        assertTrue(source.deletedIds.isEmpty())
        assertNotNull(h.repository.itemById("src:1"))
    }

    @Test
    fun archiveCopiesVerifiesThenDeletesTheSource() = runTest {
        val source = FakeSource(config("src"))
        val target = FakeSource(config("dst"))
        val h = harness(source, target)
        h.insertItem()

        val outcome = h.controller.archive(listOf(h.item())).getOrThrow()

        assertEquals(1, outcome.succeeded)
        assertEquals(1, target.uploads)
        assertTrue(target.uploaded.isNotEmpty())
        assertEquals(listOf("src-id"), source.deletedIds)
        assertNull(h.repository.itemById("src:1"))
    }

    @Test
    fun failedVerificationKeepsTheOriginalAndRemovesTheBadCopy() = runTest {
        val source = FakeSource(config("src"))
        val target = FakeSource(config("dst"), corruptReadback = true)
        val h = harness(source, target)
        h.insertItem()

        val outcome = h.controller.archive(listOf(h.item())).getOrThrow()

        assertEquals(1, outcome.failed)
        assertTrue(source.deletedIds.isEmpty(), "the original must survive a bad copy")
        assertEquals(1, target.uploads)
        assertEquals(listOf("upload-1"), target.deletedIds)
        assertNotNull(h.repository.itemById("src:1"))
    }

    @Test
    fun archivingFromTheArchiveTargetItselfIsRejected() = runTest {
        val source = FakeSource(config("src"))
        val target = FakeSource(config("dst"))
        val h = harness(source, target)
        h.insertItem(id = "dst:1", sourceId = "dst")

        assertFalse(h.controller.canArchive(h.item("dst:1")))
        val outcome = h.controller.archive(listOf(h.item("dst:1"))).getOrThrow()

        assertEquals(1, outcome.failed)
        assertTrue(source.deletedIds.isEmpty())
        assertTrue(target.deletedIds.isEmpty())
        assertNotNull(h.repository.itemById("dst:1"))
    }

    @Test
    fun meteredNetworkRefusesToArchive() = runTest {
        val source = FakeSource(config("src"))
        val target = FakeSource(config("dst"))
        val h = harness(source, target, networkAllowed = { false })
        h.insertItem()

        val result = h.controller.archive(listOf(h.item()))

        assertFalse(result.isSuccess)
        assertEquals(0, target.uploads)
        assertTrue(source.deletedIds.isEmpty())
        assertNotNull(h.repository.itemById("src:1"))
    }
}
