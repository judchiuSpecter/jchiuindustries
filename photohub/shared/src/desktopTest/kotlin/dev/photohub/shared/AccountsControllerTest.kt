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
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.AccountsController.LinkState
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.model.StorageQuota
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.drive.DriveSourceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the sources screen's state machine.
 *
 * These matter more than usual here: the UI cannot be observed in this
 * environment, so the controller's state is the only evidence that the screen
 * will show the right thing — particularly the multiple-account case, where a
 * mistake would silently overwrite one account's refresh token with another's.
 */
class AccountsControllerTest {

    private companion object {
        const val GOOD_ABOUT = """{"user":{"emailAddress":"jud@example.com"},"storageQuota":{"limit":"15000000000","usage":"4230000000","usageInDrive":"2100000000"}}"""
    }

    private class MemStore : CredentialStore {
        val v = mutableMapOf<String, String>()
        override fun get(key: String) = v[key]
        override fun put(key: String, value: String) { v[key] = value }
        override fun remove(key: String) { v.remove(key) }
        override fun removeAll(keyPrefix: String) {
            v.keys.filter { it.startsWith(keyPrefix) }.forEach(v::remove)
        }
    }

    /** Stands in for a real MEGA login; the tests only care about state. */
    private class FakeMegaAccounts : MegaAccounts {
        val linked = mutableSetOf<String>()
        var failLink = false

        override fun isLinked(sourceId: String) = sourceId in linked

        override suspend fun link(
            sourceId: String,
            email: String,
            password: String,
            mfa: String?,
        ): Result<String> {
            if (failLink) return Result.failure(IllegalStateException("Invalid credentials"))
            linked += sourceId
            return Result.success(email)
        }

        override suspend fun unlink(sourceId: String) { linked -= sourceId }

        override suspend fun quota(sourceId: String) = StorageQuota(
            limitBytes = 50_000_000_000L,
            usedBytes = 12_000_000_000L,
        )

        override suspend fun sourceFor(config: SourceConfig): MediaSource =
            error("Not used by these state tests")

        override suspend fun targetFor(config: SourceConfig): UploadTarget =
            error("Not used by these state tests")
    }

    /** Stands in for a real NAS/SFTP connection; the tests only care about state. */
    private class FakeNasAccounts : NasAccounts {
        val linked = mutableSetOf<String>()
        var failLink = false

        override fun isLinked(sourceId: String) = sourceId in linked

        override suspend fun link(
            sourceId: String,
            host: String,
            port: Int,
            username: String,
            password: String,
            remotePath: String,
        ): Result<String> {
            if (failLink) return Result.failure(IllegalStateException("Could not connect"))
            linked += sourceId
            return Result.success(host)
        }

        override suspend fun unlink(sourceId: String) { linked -= sourceId }

        override suspend fun sourceFor(config: SourceConfig): MediaSource =
            error("Not used by these state tests")
    }

    private data class Harness(
        val controller: AccountsController,
        val repository: MediaRepository,
        val store: MemStore,
        val megas: FakeMegaAccounts,
        val nas: FakeNasAccounts = FakeNasAccounts(),
    )

    private fun repo(): MediaRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return MediaRepository(PhotoHubDatabase(driver))
    }

    private fun sourceIds(vararg ids: String): () -> String = ids.iterator()::next

    private fun build(
        clientId: String = "cid.apps.googleusercontent.com",
        tokenBody: String = """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
        redirect: RedirectResult = RedirectResult("code", "fixed", null),
        repository: MediaRepository = repo(),
        generateSourceId: () -> String = sourceIds("google_drive_test"),
        aboutBody: () -> String = { GOOD_ABOUT },
    ): Harness {
        val store = MemStore()
        val http = HttpClient(
            MockEngine { request ->
                // The link flow calls both the token endpoint and Drive's `about`.
                val body = if (request.url.encodedPath.contains("about")) {
                    aboutBody()
                } else {
                    tokenBody
                }
                respond(
                    ByteReadChannel(body),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val config = GoogleOAuthConfig(clientId = clientId)
        val oauth = GoogleOAuthClient(http, config, now = { 0L })
        val capture = object : RedirectCapture {
            override fun begin() = "http://127.0.0.1:1"
            override suspend fun await() = Result.success(redirect)
            override fun cancel() {}
        }
        val factory = DriveAccountFactory(
            credentials = store,
            http = http,
            oauth = oauth,
            redirectCapture = capture,
            now = { 0L },
            generateState = { "fixed" },
            launchBrowser = { true },
        )
        val megas = FakeMegaAccounts()
        val nas = FakeNasAccounts()
        val controller = AccountsController(
            repository = repository,
            drives = factory,
            megas = megas,
            nas = nas,
            oauthConfig = config,
            generateSourceId = generateSourceId,
        )
        return Harness(controller, repository, store, megas, nas)
    }

    private fun Harness.driveSources() =
        repository.allSources().filter { it.type == SourceType.GOOGLE_DRIVE }

    private fun Harness.account(id: String) =
        controller.state.value.sources.first { it.id == id }

    @Test
    fun missingClientIdShowsSetupStateNotAnError() {
        val (controller) = build(clientId = "")
        assert(!controller.state.value.oauthConfigured)
        assertTrue(controller.state.value.setupPath.endsWith("google-oauth.json"))
    }

    @Test
    fun linkingWithoutAClientIdExplainsWhatToDo() = runTest {
        val h = build(clientId = "")
        h.controller.linkDrive()
        assertTrue(h.controller.state.value.message == null)
        assertTrue(h.driveSources().isEmpty(), "no half-created source row")
        assertTrue(h.store.v.isEmpty())
    }

    @Test
    fun freshInstallHasOnlyTheAddAction() = runTest {
        val (controller) = build()
        assertTrue(controller.state.value.sources.isEmpty())
    }

    @Test
    fun successfulLinkRegistersDriveAsTheUploadTarget() = runTest {
        val h = build()

        h.controller.linkDrive()

        val source = h.driveSources().single()
        assertEquals("google_drive_test", source.id)
        assertTrue(source.enabled)
        assertTrue(source.isUploadTarget)
        assertEquals(source.id, h.repository.uploadTarget()?.id)
        assertEquals("rt", h.store.v["${source.id}.refreshToken"])
        assertEquals(LinkState.LINKED, h.account(source.id).state)
        assertTrue(h.controller.state.value.message!!.contains("connected", ignoreCase = true))
        // The account label comes from Drive's `about` response.
        assertTrue(source.displayName.contains("jud@example.com"), source.displayName)
        val config: DriveSourceConfig = Json.decodeFromString(source.configJson)
        assertEquals("jud@example.com", config.accountEmail)
    }

    @Test
    fun failedLinkLeavesNoSourceAndSurfacesTheReason() = runTest {
        val h = build(redirect = RedirectResult(null, null, "access_denied"))

        h.controller.linkDrive()

        assertTrue(h.driveSources().isEmpty())
        assertTrue(h.store.v.isEmpty())
        // The error must be visible even though no Source row was created.
        assertTrue(
            h.controller.state.value.sources.any {
                it.error?.contains("access_denied") == true
            },
        )
    }

    @Test
    fun stateMismatchIsReportedAndNothingIsPersisted() = runTest {
        val h = build(redirect = RedirectResult("evil-code", "wrong-state", null))

        h.controller.linkDrive()

        assertTrue(h.driveSources().isEmpty())
        assertTrue(h.store.v.isEmpty())
    }

    @Test
    fun unlinkClearsTokensButKeepsIndexedPhotos() = runTest {
        val h = build()
        h.controller.linkDrive()
        val id = h.driveSources().single().id

        h.controller.unlinkSource(id)

        assertNull(h.store.v["$id.refreshToken"], "tokens must be wiped on disconnect")
        val source = h.repository.sourceById(id)!!
        assertTrue(!source.enabled)
        assertNull(h.repository.uploadTarget(), "a disconnected target must not stay selected")
        assertEquals(LinkState.NOT_LINKED, h.account(id).state)
    }

    @Test
    fun twoAccountsCoexistWithSeparateTokens() = runTest {
        val h = build(generateSourceId = sourceIds("google_drive_a", "google_drive_b"))

        h.controller.linkDrive()
        h.controller.linkDrive()

        val sources = h.driveSources()
        assertEquals(2, sources.size)
        assertEquals("rt", h.store.v["google_drive_a.refreshToken"])
        assertEquals("rt", h.store.v["google_drive_b.refreshToken"])
        // Disconnecting one must not touch the other's credentials.
        h.controller.unlinkSource("google_drive_a")
        assertNull(h.store.v["google_drive_a.refreshToken"])
        assertEquals("rt", h.store.v["google_drive_b.refreshToken"])
        assertEquals(1, h.driveSources().count { it.enabled })
    }

    @Test
    fun setUploadTargetSwitchesTheDestination() = runTest {
        val h = build(generateSourceId = sourceIds("google_drive_a", "google_drive_b"))
        h.controller.linkDrive()
        h.controller.linkDrive()
        assertEquals("google_drive_a", h.repository.uploadTarget()?.id)

        h.controller.setUploadTarget("google_drive_b")

        assertEquals("google_drive_b", h.repository.uploadTarget()?.id)
        assertEquals(1, h.repository.allSources().count { it.isUploadTarget })
        assertTrue(h.account("google_drive_b").isUploadTarget)
        assertTrue(!h.account("google_drive_a").isUploadTarget)
    }

    @Test
    fun relinkingAfterUnlinkRestoresTheUploadTarget() = runTest {
        val h = build(generateSourceId = sourceIds("google_drive_a", "google_drive_a"))
        h.controller.linkDrive()
        h.controller.unlinkSource("google_drive_a")
        h.controller.linkDrive("google_drive_a")

        assertEquals("google_drive_a", h.repository.uploadTarget()?.id)
        assertTrue(h.account("google_drive_a").isUploadTarget)
        assertEquals(1, h.repository.allSources().count { it.isUploadTarget })
    }

    @Test
    fun quotaIsFetchedOnDemandNotDuringSync() = runTest {
        val h = build()
        h.controller.linkDrive()
        // Linking calls `about` for the email, but quota is only shown when the
        // user opens the sources screen and asks for it explicitly.
        assertNull(h.account("google_drive_test").quota)

        h.controller.refreshQuotas()

        val quota = h.account("google_drive_test").quota!!
        assertEquals(15_000_000_000L, quota.limitBytes)
        assertEquals(4_230_000_000L, quota.usedBytes)
        assertEquals(2_100_000_000L, quota.usedInDriveBytes)
        assertTrue(!h.controller.state.value.quotaLoading)
    }

    @Test
    fun quotaFailureDoesNotEraseTheLastKnownValue() = runTest {
        var offline = false
        val h = build(aboutBody = { if (offline) "not json" else GOOD_ABOUT })
        h.controller.linkDrive()
        h.controller.refreshQuotas()
        assertEquals(4_230_000_000L, h.account("google_drive_test").quota!!.usedBytes)

        // A later offline visit must not blank out the cached number.
        offline = true
        h.controller.refreshQuotas()
        assertEquals(4_230_000_000L, h.account("google_drive_test").quota!!.usedBytes)
        assertTrue(!h.controller.state.value.quotaLoading)
    }

    @Test
    fun linkingMegaCreatesItsOwnSourceAndUploadTarget() = runTest {
        val h = build()
        h.controller.linkMega("jud@mega.example", "secret")

        val source = h.repository.allSources().single { it.type == SourceType.MEGA }
        assertTrue(source.id.startsWith("mega_"))
        assertTrue(source.isUploadTarget)
        assertEquals(LinkState.LINKED, h.account(source.id).state)
        assertTrue(h.account(source.id).canUpload)
        assertTrue(h.megas.isLinked(source.id))
        assertTrue(h.controller.state.value.message!!.contains("MEGA"))
    }

    @Test
    fun megaCanBeSwitchedToAsUploadTargetAndDisconnected() = runTest {
        val h = build()
        h.controller.linkDrive()
        val driveId = h.driveSources().single().id
        h.controller.linkMega("jud@mega.example", "secret")
        val megaId = h.repository.allSources().single { it.type == SourceType.MEGA }.id

        // The first cloud keeps the target when MEGA is added later.
        assertEquals(driveId, h.repository.uploadTarget()?.id)
        h.controller.setUploadTarget(megaId)
        assertEquals(megaId, h.repository.uploadTarget()?.id)

        h.controller.unlinkSource(megaId)
        assertEquals(LinkState.NOT_LINKED, h.account(megaId).state)
        assertTrue(!h.megas.isLinked(megaId))
        assertNull(h.repository.uploadTarget())
        // Indexed rows stay put; only the connection was removed.
        assertTrue(h.repository.sourceById(megaId) != null)
    }

    @Test
    fun megaQuotaIsFetchedOnDemand() = runTest {
        val h = build()
        h.controller.linkMega("jud@mega.example", "secret")
        val id = h.repository.allSources().single { it.type == SourceType.MEGA }.id
        assertNull(h.account(id).quota)

        h.controller.refreshQuotas()
        assertEquals(12_000_000_000L, h.account(id).quota!!.usedBytes)
        assertEquals(50_000_000_000L, h.account(id).quota!!.limitBytes)
    }

    @Test
    fun failedMegaLinkReportsAnErrorWithoutASourceRow() = runTest {
        val h = build()
        h.megas.failLink = true

        h.controller.linkMega("jud@mega.example", "wrong")

        assertTrue(h.repository.allSources().none { it.type == SourceType.MEGA })
        val card = h.controller.state.value.sources.single { it.type == SourceType.MEGA }
        assertTrue(card.error!!.contains("Invalid credentials"))
        assertEquals(LinkState.NOT_LINKED, card.state)
    }

    @Test
    fun consumeMessageClearsTheNotice() = runTest {
        val h = build()
        h.controller.linkDrive()
        assertTrue(h.controller.state.value.message != null)

        h.controller.consumeMessage()

        assertNull(h.controller.state.value.message)
    }

    @Test
    fun refreshReflectsStorageChangedElsewhere() = runTest {
        val h = build()
        h.controller.linkDrive()
        val id = h.driveSources().single().id

        // A background sync renamed the account, as it would after reading the
        // user's email from Drive.
        h.repository.upsertCloudSource(
            id = id,
            type = SourceType.GOOGLE_DRIVE,
            displayName = "Google Drive (other@example.com)",
        )
        h.controller.refresh()

        assertEquals("Google Drive (other@example.com)", h.account(id).displayName)
        assertEquals(LinkState.LINKED, h.account(id).state)
    }
}
