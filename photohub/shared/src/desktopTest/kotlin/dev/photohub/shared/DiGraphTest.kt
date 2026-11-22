package dev.photohub.shared

import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.DriveLinkCoordinator
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.RedirectCapture
import dev.photohub.shared.auth.RedirectResult
import dev.photohub.shared.auth.TokenProvider
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.data.UploadManager
import dev.photohub.shared.di.sharedModule
import dev.photohub.shared.source.drive.DriveApi
import dev.photohub.shared.source.drive.DriveUploader
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Verifies the Koin graph actually resolves.
 *
 * A missing binding compiles fine and only fails at runtime, when the app
 * crashes on launch — exactly the class of bug that is invisible here without
 * being able to see the screen. These tests instantiate every dependency the
 * hosts request.
 */
class DiGraphTest {

    @AfterTest
    fun tearDown() = stopKoin()

    /** Stands in for the platform bindings each host supplies. */
    private val platformModule = module {
        single<CredentialStore> {
            object : CredentialStore {
                val v = mutableMapOf<String, String>()
                override fun get(key: String) = v[key]
                override fun put(key: String, value: String) { v[key] = value }
                override fun remove(key: String) { v.remove(key) }
                override fun removeAll(keyPrefix: String) {
                    v.keys.filter { it.startsWith(keyPrefix) }.forEach(v::remove)
                }
            }
        }
        factory<RedirectCapture> {
            object : RedirectCapture {
                override fun begin() = "http://127.0.0.1:0"
                override suspend fun await() = Result.success(RedirectResult(null, null, "test"))
                override fun cancel() {}
            }
        }
    }

    @Test
    fun resolvesEveryDependencyTheHostsAskFor() {
        val koin = koinApplication { modules(sharedModule, platformModule) }.koin

        // What MainActivity / Main.kt pull out directly.
        assertNotNull(koin.get<TimelineController>())
        assertNotNull(koin.get<DriveLinkCoordinator>())
        assertNotNull(koin.get<UploadManager>())
        assertNotNull(koin.get<dev.photohub.shared.data.AccountsController>())

        // The Drive chain, where a missing binding would only surface on the
        // first upload attempt.
        assertNotNull(koin.get<DriveApi>())
        assertNotNull(koin.get<DriveUploader>())
        assertNotNull(koin.get<TokenProvider>())
        assertNotNull(koin.get<GoogleOAuthClient>())
    }

    @Test
    fun singletonsAreSharedRatherThanRebuiltPerInjection() {
        val koin = koinApplication { modules(sharedModule, platformModule) }.koin

        // TokenProvider must be a singleton or its single-flight refresh mutex
        // would be per-instance and stop coordinating anything.
        assert(koin.get<TokenProvider>() === koin.get<TokenProvider>())
        // One HTTP client, so connections are pooled across sources.
        assert(koin.get<io.ktor.client.HttpClient>() === koin.get<io.ktor.client.HttpClient>())
    }
}
