package dev.photohub.shared.auth

import dev.photohub.shared.platform.openInBrowser
import dev.photohub.shared.source.drive.DriveApi
import dev.photohub.shared.source.drive.DriveUploadTarget
import dev.photohub.shared.source.drive.DriveUploader
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds and caches the whole Google Drive collaborator graph per source id.
 *
 * Everything that holds mutable state — the refresh token namespace, the
 * single-flight refresh mutex, the resumable upload session state — must be
 * private to one connected account. Koin singletons cannot express that, so the
 * factory owns the per-id instances and hands them out. The cache is important:
 * recreating a [TokenProvider] would create a new mutex and let concurrent
 * thumbnail requests each refresh the same token.
 */
class DriveAccountFactory(
    private val credentials: CredentialStore,
    private val http: HttpClient,
    private val oauth: GoogleOAuthClient,
    private val redirectCapture: RedirectCapture,
    private val now: () -> Long,
    /** Overridable so tests can drive the sign-in without a browser. */
    private val generateState: () -> String = { PkceChallenge.randomState() },
    private val launchBrowser: (String) -> Boolean = ::openInBrowser,
) {
    /** All collaborators for one connected account. */
    data class Account(
        val sourceId: String,
        val tokens: TokenProvider,
        val api: DriveApi,
        val uploader: DriveUploader,
        val target: DriveUploadTarget,
        val link: DriveLinkCoordinator,
    )

    private val cache = mutableMapOf<String, Account>()
    private val mutex = Mutex()

    /** Same id always returns the same graph, so token refresh stays single-flight. */
    suspend fun forSource(sourceId: String): Account = mutex.withLock {
        cache.getOrPut(sourceId) { build(sourceId) }
    }

    /**
     * Whether this source has a stored refresh token.
     *
     * Synchronous and cheap so the accounts screen can render status without a
     * coroutine; it reads the credential store directly rather than building a
     * graph that may never be used.
     */
    fun isLinked(sourceId: String): Boolean =
        OAuthTokenStore(credentials, sourceId).load()?.refreshToken != null

    private fun build(sourceId: String): Account {
        val tokenStore = OAuthTokenStore(credentials, sourceId)
        val tokens = TokenProvider(oauth, tokenStore, now)
        val api = DriveApi(http, tokens)
        val uploader = DriveUploader(http, api, tokens)
        return Account(
            sourceId = sourceId,
            tokens = tokens,
            api = api,
            uploader = uploader,
            target = DriveUploadTarget(uploader, sourceId),
            link = DriveLinkCoordinator(
                oauth = oauth,
                tokens = tokens,
                capture = redirectCapture,
                generateState = generateState,
                launchBrowser = launchBrowser,
            ),
        )
    }
}
