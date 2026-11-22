package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.DriveLinkCoordinator
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthTokenStore
import dev.photohub.shared.auth.RedirectCapture
import dev.photohub.shared.auth.RedirectResult
import dev.photohub.shared.auth.TokenProvider
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.AccountsController.LinkState
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.source.drive.DriveSourceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the accounts screen's state machine.
 *
 * These matter more than usual here: the UI cannot be observed in this
 * environment, so the controller's state is the only evidence that the screen
 * will show the right thing — particularly the "setup needed" state, which is
 * what a fresh install actually sees.
 */
class AccountsControllerTest {

    private class MemStore : CredentialStore {
        val v = mutableMapOf<String, String>()
        override fun get(key: String) = v[key]
        override fun put(key: String, value: String) { v[key] = value }
        override fun remove(key: String) { v.remove(key) }
        override fun removeAll(keyPrefix: String) {
            v.keys.filter { it.startsWith(keyPrefix) }.forEach(v::remove)
        }
    }

    private fun repo(): MediaRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoHubDatabase.Schema.create(driver)
        return MediaRepository(PhotoHubDatabase(driver))
    }

    private fun build(
        clientId: String = "cid.apps.googleusercontent.com",
        tokenBody: String = """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
        redirect: RedirectResult = RedirectResult("code", "fixed", null),
        repository: MediaRepository = repo(),
    ): Triple<AccountsController, MediaRepository, OAuthTokenStore> {
        val http = HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(tokenBody),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val config = GoogleOAuthConfig(clientId = clientId)
        val oauth = GoogleOAuthClient(http, config, now = { 0L })
        val tokens = OAuthTokenStore(MemStore(), DriveSourceConfig.SOURCE_ID)
        val provider = TokenProvider(oauth, tokens, now = { 0L })
        val capture = object : RedirectCapture {
            override fun begin() = "http://127.0.0.1:1"
            override suspend fun await() = Result.success(redirect)
            override fun cancel() {}
        }
        val link = DriveLinkCoordinator(oauth, provider, capture, generateState = { "fixed" }, launchBrowser = { true })

        return Triple(
            AccountsController(repository, link, config),
            repository,
            tokens,
        )
    }

    @Test
    fun missingClientIdShowsSetupStateNotAnError() {
        val (controller, _, _) = build(clientId = "")

        val drive = controller.state.value.drive
        // A fresh install has no client id. Treating that as a failure would
        // leave the user with an error and no idea what to do.
        assertEquals(LinkState.NOT_CONFIGURED, drive.state)
        assertNull(drive.error)
        // The screen tells them exactly where the file goes.
        assertTrue(drive.setupPath.endsWith("google-oauth.json"), drive.setupPath)
    }

    @Test
    fun linkingWithoutAClientIdExplainsWhatToDo() = runTest {
        val (controller, repository, _) = build(clientId = "")

        controller.linkDrive()

        val drive = controller.state.value.drive
        assertEquals(LinkState.NOT_CONFIGURED, drive.state)
        assertTrue(drive.error!!.contains("client ID"), drive.error!!)
        // No half-created source row: nothing was configured.
        assertNull(repository.sourceById(DriveSourceConfig.SOURCE_ID))
    }

    @Test
    fun configuredButUnlinkedOffersSignIn() {
        val (controller, _, _) = build()
        assertEquals(LinkState.NOT_LINKED, controller.state.value.drive.state)
    }

    @Test
    fun successfulLinkRegistersDriveAsTheUploadTarget() = runTest {
        val (controller, repository, tokens) = build()

        controller.linkDrive()

        assertEquals(LinkState.LINKED, controller.state.value.drive.state)
        assertTrue(controller.state.value.drive.isUploadTarget)
        assertEquals("rt", tokens.load()!!.refreshToken)

        // Connecting a cloud is only useful if photos actually go to it, so the
        // source row must exist and be the upload target.
        val source = repository.sourceById(DriveSourceConfig.SOURCE_ID)!!
        assertTrue(source.enabled)
        assertTrue(source.isUploadTarget)
        assertEquals(DriveSourceConfig.SOURCE_ID, repository.uploadTarget()?.id)
        assertTrue(controller.state.value.message!!.contains("connected", ignoreCase = true))
    }

    @Test
    fun failedLinkLeavesNoSourceAndSurfacesTheReason() = runTest {
        val (controller, repository, tokens) = build(
            redirect = RedirectResult(null, null, "access_denied"),
        )

        controller.linkDrive()

        assertEquals(LinkState.NOT_LINKED, controller.state.value.drive.state)
        assertTrue(controller.state.value.drive.error!!.contains("access_denied"))
        // A denied sign-in must not leave a source that looks connected.
        assertNull(repository.sourceById(DriveSourceConfig.SOURCE_ID))
        assertNull(tokens.load())
    }

    @Test
    fun stateMismatchIsReportedAndNothingIsPersisted() = runTest {
        // The OAuth CSRF defence, surfaced all the way to the screen.
        val (controller, repository, tokens) = build(
            redirect = RedirectResult("evil-code", "wrong-state", null),
        )

        controller.linkDrive()

        assertEquals(LinkState.NOT_LINKED, controller.state.value.drive.state)
        assertTrue(controller.state.value.drive.error!!.contains("state mismatch"))
        assertNull(tokens.load())
        assertNull(repository.sourceById(DriveSourceConfig.SOURCE_ID))
    }

    @Test
    fun unlinkClearsTokensButKeepsIndexedPhotos() = runTest {
        val repository = repo()
        val (controller, _, tokens) = build(repository = repository)
        controller.linkDrive()
        assertEquals(LinkState.LINKED, controller.state.value.drive.state)

        controller.unlinkDrive()

        assertEquals(LinkState.NOT_LINKED, controller.state.value.drive.state)
        assertNull(tokens.load(), "tokens must be wiped on disconnect")
        // The source row survives, disabled. Deleting it would cascade away the
        // indexed rows and make the timeline suddenly lose entries — and nothing
        // is ever removed from the cloud either.
        val source = repository.sourceById(DriveSourceConfig.SOURCE_ID)!!
        assertTrue(!source.enabled)
    }

    @Test
    fun secondSignInIsRefusedRatherThanOverwritingTheFirstAccount() = runTest {
        // Source ids and credential keys are both the constant SOURCE_ID, so a
        // second link would replace the first account's refresh token in place.
        // Until source ids are per-instance, refusing is the only safe answer —
        // silently losing a connected account is far worse than a message.
        val repository = repo()
        val (controller, _, tokens) = build(repository = repository)

        controller.linkDrive()
        val firstRefresh = tokens.load()!!.refreshToken
        controller.linkDrive()

        assertEquals(LinkState.LINKED, controller.state.value.drive.state)
        assertEquals(firstRefresh, tokens.load()!!.refreshToken, "first account's token must survive")
        assertTrue(
            controller.state.value.drive.error!!.contains("already connected"),
            "the refusal must be explained: ${controller.state.value.drive.error}",
        )
        // Still exactly one account, still the upload target.
        assertEquals(1, repository.allSources().count { it.isUploadTarget })
    }

    @Test
    fun relinkingAfterUnlinkRestoresTheUploadTarget() = runTest {
        val repository = repo()
        val (controller, _, _) = build(repository = repository)

        controller.linkDrive()
        controller.unlinkDrive()
        controller.linkDrive()

        assertEquals(LinkState.LINKED, controller.state.value.drive.state)
        assertEquals(DriveSourceConfig.SOURCE_ID, repository.uploadTarget()?.id)
        // Exactly one target, even after the disable/enable round trip.
        assertEquals(1, repository.allSources().count { it.isUploadTarget })
    }

    @Test
    fun consumeMessageClearsBothMessageAndError() = runTest {
        val (controller, _, _) = build(redirect = RedirectResult(null, null, "access_denied"))
        controller.linkDrive()
        assertTrue(controller.state.value.drive.error != null)

        controller.consumeMessage()

        // One-shot notices must not reappear on every recomposition.
        assertNull(controller.state.value.drive.error)
        assertNull(controller.state.value.message)
    }

    @Test
    fun refreshReflectsStorageChangedElsewhere() = runTest {
        val repository = repo()
        val (controller, _, _) = build(repository = repository)
        controller.linkDrive()
        assertEquals(LinkState.LINKED, controller.state.value.drive.state)

        // A background sync renamed the account, as it would after reading the
        // user's email from Drive.
        repository.upsertCloudSource(
            id = DriveSourceConfig.SOURCE_ID,
            type = dev.photohub.shared.model.SourceType.GOOGLE_DRIVE,
            displayName = "Google Drive (jud@example.com)",
        )
        controller.refresh()

        assertEquals("Google Drive (jud@example.com)", controller.state.value.drive.displayName)
        assertEquals(LinkState.LINKED, controller.state.value.drive.state)
    }
}
