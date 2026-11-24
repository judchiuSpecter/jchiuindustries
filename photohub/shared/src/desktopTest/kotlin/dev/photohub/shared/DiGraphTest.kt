package dev.photohub.shared

import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.DriveAccountFactory
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.RedirectCapture
import dev.photohub.shared.auth.RedirectResult
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.CurrentUploadTarget
import dev.photohub.shared.data.SettingsStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.data.UploadManager
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.di.sharedModule
import dev.photohub.shared.source.SourceRegistry
import io.ktor.client.HttpClient
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
        assertNotNull(koin.get<SettingsStore>())
        assertNotNull(koin.get<UploadManager>())
        assertNotNull(koin.get<AccountsController>())

        // The per-account Drive chain is built by the factory rather than being
        // a direct binding; with no account linked there is nothing to build yet.
        assertNotNull(koin.get<DriveAccountFactory>())
        assertNotNull(koin.get<SourceRegistry>())
        assertNotNull(koin.get<GoogleOAuthClient>())

        // The upload target resolves lazily, so it must not require a linked
        // account just to be injected.
        assertNotNull(koin.get<UploadTarget>())
    }

    @Test
    fun singletonsAreSharedRatherThanRebuiltPerInjection() {
        val koin = koinApplication { modules(sharedModule, platformModule) }.koin

        // The factory caches one graph per source id; two factory instances would
        // create two refresh mutexes for the same account.
        assert(koin.get<DriveAccountFactory>() === koin.get<DriveAccountFactory>())
        // One HTTP client, so connections are pooled across sources.
        assert(koin.get<HttpClient>() === koin.get<HttpClient>())
        assert(koin.get<CurrentUploadTarget>() === koin.get<CurrentUploadTarget>())
    }
}
