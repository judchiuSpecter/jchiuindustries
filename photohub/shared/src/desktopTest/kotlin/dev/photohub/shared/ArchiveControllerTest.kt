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
import dev.photohub.shared.data.ArchiveController
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.UploadTarget
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the destructive half of the archiver: a cloud original must survive
 * every failure, and must only be deleted after a verified NAS copy exists.
 */
class ArchiveControllerTest {

    private class FakeCloudSource(
        override val config: SourceConfig,
        private val items: List<RemoteMedia>,
        private val failCopyDownload: Boolean = false,
    ) : MediaSource {
        var deletedIds = emptyList<String>()
            private set
        var downloads = 0
            private set

        override val canDelete: Boolean get() = true

        override suspend fun list(cursor: String?): Result<MediaPage> =
            Result.success(MediaPage(items, null))

        override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
            error("not used")

        override suspend fun downloadOriginal(
            item: RemoteMedia,
            target: Path,
            onProgress: (Float) -> Unit,
        ): Result<Path> = if (failCopyDownload) {
            Result.failure(IllegalStateException("download failed"))
        } else {
            downloads++
            FileSystem.SYSTEM.createDirectories(target.parent!!)
            FileSystem.SYSTEM.write(target) { writeUtf8("original bytes") }
            Result.success(target)
        }

        override suspend fun delete(item: RemoteMedia): Result<Unit> {
            deletedIds = deletedIds + item.remoteId
            return Result.success(Unit)
        }
    }

    private class FakeMegas(private val source: MediaSource) : MegaAccounts {
        override fun isLinked(sourceId: String) = true
        override suspend fun link(sourceId: String, email: String, password: String, mfa: String?) =
            Result.success("test")
        override suspend fun unlink(sourceId: String) = Unit
        override suspend fun quota(sourceId: String): StorageQuota? = null
        override suspend fun sourceFor(config: SourceConfig): MediaSource = source
        override suspend fun targetFor(config: SourceConfig): UploadTarget = error("not used")
    }

    private class FakeNas(
        private val copyFails: Boolean = false,
        private val existingSize: Long? = null,
    ) : NasAccounts {
        var latestRemotePath: String? = null
            private set
        var copyCalls = 0
            private set

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
        override suspend fun archivedFileSize(config: SourceConfig, remotePath: String): Result<Long?> =
            Result.success(existingSize)
        override suspend fun copyToArchive(config: SourceConfig, local: Path, remotePath: String): Result<Unit> {
            latestRemotePath = remotePath
            copyCalls++
            return if (copyFails) Result.failure(IllegalStateException("checksum mismatch"))
            else Result.success(Unit)
        }
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

    private fun harness(
        cloudSource: MediaSource,
        nas: NasAccounts,
        networkAllowed: () -> Boolean = { true },
    ): ArchiveController {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        val repository = MediaRepository(PhotoHubDatabase(driver))
        repository.upsertCloudSource(
            id = "mega_test",
            type = SourceType.MEGA,
            displayName = "MEGA (test)",
        )
        repository.upsertCloudSource(
            id = "nas_test",
            type = SourceType.NAS_ARCHIVE,
            displayName = "NAS",
            configJson = """{"host":"nas.test","port":22,"username":"u","remotePath":"/share/Photos"}""",
        )
        // The bulk archiver now follows the one designated archive target.
        repository.setArchiveTarget("nas_test")
        return ArchiveController(
            repository = repository,
            sources = SourceRegistry(
                drives = unusedDriveFactory(),
                megas = FakeMegas(cloudSource),
                nas = nas,
            ),
            drives = unusedDriveFactory(),
            megas = FakeMegas(cloudSource),
            nas = nas,
            networkAllowed = networkAllowed,
        )
    }

    private fun media(id: String, date: Long) = RemoteMedia(
        remoteId = id,
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        isVideo = false,
        sizeBytes = 14L,
        dateTakenEpochMs = date,
    )

    @Test
    fun archivesItemsOlderThanCutoffAndOnlyThenDeletesThem() = runTest {
        val source = FakeCloudSource(
            config = SourceConfig("mega_test", SourceType.MEGA, "MEGA", true, false, "{}"),
            items = listOf(media("old", 100L), media("new", 9_000L)),
        )
        val nas = FakeNas()
        val controller = harness(source, nas)

        val result = controller.run(cutoffEpochMs = 5_000L)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().archived)
        assertEquals(listOf("old"), source.deletedIds)
        assertEquals(1, nas.copyCalls)
        assertTrue(nas.latestRemotePath!!.startsWith("/share/Photos/PhotoHubArchive/MEGA/"))
    }

    @Test
    fun aFailedCopyKeepsTheCloudOriginal() = runTest {
        val source = FakeCloudSource(
            config = SourceConfig("mega_test", SourceType.MEGA, "MEGA", true, false, "{}"),
            items = listOf(media("old", 100L)),
        )
        val controller = harness(source, FakeNas(copyFails = true))

        val result = controller.run(cutoffEpochMs = 5_000L)

        assertEquals(1, result.getOrThrow().failed)
        assertTrue(source.deletedIds.isEmpty())
    }

    @Test
    fun anExistingVerifiedCopyDeletesTheCloudItemWithoutDownloadingItAgain() = runTest {
        val source = FakeCloudSource(
            config = SourceConfig("mega_test", SourceType.MEGA, "MEGA", true, false, "{}"),
            items = listOf(media("old", 100L)),
        )
        val nas = FakeNas(existingSize = 14L)
        val controller = harness(source, nas)

        controller.run(cutoffEpochMs = 5_000L)

        assertEquals(0, source.downloads)
        assertEquals(0, nas.copyCalls)
        assertEquals(listOf("old"), source.deletedIds)
    }

    @Test
    fun meteredNetworkRefusesToRun() = runTest {
        val source = FakeCloudSource(
            config = SourceConfig("mega_test", SourceType.MEGA, "MEGA", true, false, "{}"),
            items = listOf(media("old", 100L)),
        )
        val nas = FakeNas()
        val controller = harness(source, nas, networkAllowed = { false })

        val result = controller.run(cutoffEpochMs = 5_000L)

        assertFalse(result.isSuccess)
        assertEquals(0, nas.copyCalls)
        assertTrue(source.deletedIds.isEmpty())
    }
}
