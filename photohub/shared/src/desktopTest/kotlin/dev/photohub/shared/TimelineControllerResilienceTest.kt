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
import dev.photohub.shared.data.MediaIndexer
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.ThumbnailStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.LocalSourceConfig
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
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the "don't keep retrying a broken source" contract: a source that
 * throws during sync (MEGA's client throws rather than returning a
 * [Result.failure], e.g. on a blocked account) must not crash the refresh and
 * must not be retried again until the user explicitly asks for it.
 */
class TimelineControllerResilienceTest {

    /** A source whose [list] always throws, like MEGA's client does when blocked. */
    private class ThrowingSource(
        override val config: SourceConfig,
        private val message: String,
    ) : MediaSource {
        override suspend fun list(cursor: String?): Result<MediaPage> =
            throw IllegalStateException(message)

        override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
            error("not used")

        override suspend fun downloadOriginal(
            item: RemoteMedia,
            target: Path,
            onProgress: (Float) -> Unit,
        ): Result<Path> = error("not used")
    }

    /** A source that records how many times it was asked to list, always succeeding. */
    private class CountingSource(override val config: SourceConfig) : MediaSource {
        var listCalls = 0

        override suspend fun list(cursor: String?): Result<MediaPage> {
            listCalls++
            return Result.success(MediaPage(emptyList(), null))
        }

        override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
            error("not used")

        override suspend fun downloadOriginal(
            item: RemoteMedia,
            target: Path,
            onProgress: (Float) -> Unit,
        ): Result<Path> = error("not used")
    }

    private class FakeMegaAccounts(private val source: (SourceConfig) -> MediaSource) : MegaAccounts {
        override fun isLinked(sourceId: String) = true
        override suspend fun link(sourceId: String, email: String, password: String, mfa: String?) =
            error("not used")
        override suspend fun unlink(sourceId: String) = Unit
        override suspend fun quota(sourceId: String): StorageQuota? = null
        override suspend fun sourceFor(config: SourceConfig): MediaSource = source(config)
        override suspend fun targetFor(config: SourceConfig): UploadTarget = error("not used")
    }

    private class FakeNasAccounts(private val source: (SourceConfig) -> MediaSource) : NasAccounts {
        override fun isLinked(sourceId: String) = true
        override suspend fun link(
            sourceId: String,
            host: String,
            port: Int,
            username: String,
            password: String,
            remotePath: String,
        ) = error("not used")
        override suspend fun unlink(sourceId: String) = Unit
        override suspend fun sourceFor(config: SourceConfig): MediaSource = source(config)
    }

    private fun db(): PhotoHubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return PhotoHubDatabase(driver)
    }

    /** A [DriveAccountFactory] that is never exercised: no Drive source is added in these tests. */
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

    private fun harness(source: MediaSource): Triple<TimelineController, MediaRepository, PhotoHubDatabase> {
        val database = db()
        val repository = MediaRepository(database)
        // The desktop local source defaults to the real ~/Pictures folder. Pin it
        // to an empty root so these source-gating tests don't index (or decode
        // thumbnails for) the developer's actual photo library.
        val emptyRoot = java.nio.file.Files.createTempDirectory("photohub-empty-pictures").toString()
        repository.upsertCloudSource(
            id = LocalSourceConfig.SOURCE_ID,
            type = SourceType.LOCAL,
            displayName = "This device",
            configJson = """{"roots":["$emptyRoot"]}""",
        )
        val indexer = MediaIndexer(database)
        val thumbnails = ThumbnailStore(database)
        val registry = SourceRegistry(
            drives = unusedDriveFactory(),
            megas = FakeMegaAccounts { source },
            nas = FakeNasAccounts { source },
        )
        val controller = TimelineController(repository, indexer, thumbnails, registry)
        return Triple(controller, repository, database)
    }

    @Test
    fun aThrowingSourceDoesNotCrashRefreshAndIsRecordedAsAnError() = runTest {
        val (controller, repository) = harness(
            ThrowingSource(
                config = SourceConfig(
                    id = "mega_1",
                    type = SourceType.MEGA,
                    displayName = "MEGA (blocked@example.com)",
                    enabled = true,
                    isUploadTarget = false,
                    configJson = "{}",
                ),
                message = "User blocked",
            ),
        )
        repository.upsertCloudSource("mega_1", SourceType.MEGA, "MEGA (blocked@example.com)")

        // Must not throw.
        controller.refresh()

        val stored = repository.sourceById("mega_1")
        assertEquals("User blocked", stored?.lastSyncError)
    }

    @Test
    fun aSourceWithARecordedErrorIsSkippedOnTheNextAutomaticRefresh() = runTest {
        val config = SourceConfig(
            id = "nas_1",
            type = SourceType.NAS_ARCHIVE,
            displayName = "NAS (terrathunder)",
            enabled = true,
            isUploadTarget = false,
            configJson = "{}",
        )
        val counting = CountingSource(config)
        val (controller, repository) = harness(counting)
        repository.upsertCloudSource("nas_1", SourceType.NAS_ARCHIVE, "NAS (terrathunder)")
        repository.setSyncError("nas_1", "No route to host")

        controller.refresh()

        // The source was never even asked to list — the gate skipped it before
        // the registry built a client, which is what "no auto-retry" means.
        assertEquals(0, counting.listCalls)
        assertEquals("No route to host", repository.sourceById("nas_1")?.lastSyncError)
    }

    @Test
    fun retrySourceBypassesTheGateAndClearsTheErrorOnSuccess() = runTest {
        val config = SourceConfig(
            id = "nas_1",
            type = SourceType.NAS_ARCHIVE,
            displayName = "NAS (terrathunder)",
            enabled = true,
            isUploadTarget = false,
            configJson = "{}",
        )
        val counting = CountingSource(config)
        val (controller, repository) = harness(counting)
        repository.upsertCloudSource("nas_1", SourceType.NAS_ARCHIVE, "NAS (terrathunder)")
        repository.setSyncError("nas_1", "No route to host")

        controller.retrySource("nas_1")

        assertEquals(1, counting.listCalls)
        assertNull(repository.sourceById("nas_1")?.lastSyncError)
    }

    @Test
    fun retryingAStillBrokenSourceReplacesTheErrorRatherThanCrashing() = runTest {
        val (controller, repository) = harness(
            ThrowingSource(
                config = SourceConfig(
                    id = "mega_1",
                    type = SourceType.MEGA,
                    displayName = "MEGA",
                    enabled = true,
                    isUploadTarget = false,
                    configJson = "{}",
                ),
                message = "User blocked",
            ),
        )
        repository.upsertCloudSource("mega_1", SourceType.MEGA, "MEGA")
        repository.setSyncError("mega_1", "User blocked")

        controller.retrySource("mega_1")

        assertEquals("User blocked", repository.sourceById("mega_1")?.lastSyncError)
    }

    @Test
    fun aHealthySourceClearsAPreviousErrorOnTheNextManualRetry() = runTest {
        val config = SourceConfig(
            id = "mega_1",
            type = SourceType.MEGA,
            displayName = "MEGA",
            enabled = true,
            isUploadTarget = false,
            configJson = "{}",
        )
        val counting = CountingSource(config)
        val (controller, repository) = harness(counting)
        repository.upsertCloudSource("mega_1", SourceType.MEGA, "MEGA")
        repository.setSyncError("mega_1", "User blocked")

        controller.retrySource("mega_1")

        assertTrue(counting.listCalls == 1)
        assertNull(repository.sourceById("mega_1")?.lastSyncError)
    }
}
