package dev.photohub.shared.di

import dev.photohub.shared.auth.DriveAccountFactory
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthConfigLoader
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.CameraUploadScanner
import dev.photohub.shared.data.CurrentUploadTarget
import dev.photohub.shared.data.FullscreenImageController
import dev.photohub.shared.data.MediaIndexer
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.SettingsStore
import dev.photohub.shared.data.ThumbnailStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.data.UploadManager
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.platform.BackgroundScheduler
import dev.photohub.shared.platform.createDatabaseDriver
import dev.photohub.shared.platform.createHttpClient
import dev.photohub.shared.source.SourceRegistry
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

val sharedModule = module {
    single { PhotoHubDatabase(createDatabaseDriver()) }
    single { MediaRepository(get()) }
    single { MediaIndexer(get()) }
    single { SettingsStore() }
    single { ThumbnailStore(get()) }
    single { SourceRegistry(get()) }
    single { TimelineController(get(), get(), get(), get()) }
    single { BackgroundScheduler() }

    // ---- Networking ----
    // One client for the whole app: connection pooling and keep-alive matter on
    // mobile, and each engine spins up its own thread pool.
    single { createHttpClient() }

    // ---- Google Drive (M3) ----
    single { OAuthConfigLoader.load() }
    single {
        GoogleOAuthClient(
            http = get(),
            config = get<GoogleOAuthConfig>(),
            now = { nowEpochMs() },
        )
    }
    // Tokens, API clients and uploaders are per connected account, not app-wide
    // singletons. The factory caches them by source id so each account keeps its
    // own credential namespace and single-flight refresh mutex.
    single {
        DriveAccountFactory(
            credentials = get(),
            http = get(),
            oauth = get(),
            redirectCapture = get(),
            now = { nowEpochMs() },
        )
    }
    single { UploadManager(get(), now = { nowEpochMs() }) }
    single { FullscreenImageController(get(), get()) }
    single { AccountsController(get(), get(), get(), get()) }
    single { CameraUploadScanner(get(), get(), get()) }

    // Resolves the *current* upload target on every use: the user can switch
    // clouds in Settings without restarting the app.
    single { CurrentUploadTarget(get(), get()) }
    single<UploadTarget> { get<CurrentUploadTarget>() }
}

/** Single clock source, so tests can swap it and production stays consistent. */
private fun nowEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/**
 * Starts Koin with the shared module. Hosts pass [extra] to add
 * platform-specific declarations: the Android context, and each platform's
 * `CredentialStore` and `RedirectCapture`, which have no common constructor.
 */
fun initKoin(extra: KoinApplication.() -> Unit = {}) = startKoin {
    extra()
    modules(sharedModule)
}
