package dev.photohub.shared.di

import dev.photohub.shared.auth.DriveLinkCoordinator
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthConfigLoader
import dev.photohub.shared.auth.OAuthTokenStore
import dev.photohub.shared.auth.TokenProvider
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.CameraUploadScanner
import dev.photohub.shared.data.MediaIndexer
import dev.photohub.shared.data.MediaRepository
import dev.photohub.shared.data.ThumbnailStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.data.UploadManager
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.platform.BackgroundScheduler
import dev.photohub.shared.platform.createDatabaseDriver
import dev.photohub.shared.platform.createHttpClient
import dev.photohub.shared.source.drive.DriveApi
import dev.photohub.shared.source.drive.DriveSourceConfig
import dev.photohub.shared.source.drive.DriveUploadTarget
import dev.photohub.shared.source.drive.DriveUploader
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

val sharedModule = module {
    single { PhotoHubDatabase(createDatabaseDriver()) }
    single { MediaRepository(get()) }
    single { MediaIndexer(get()) }
    single { ThumbnailStore(get()) }
    single { TimelineController(get(), get(), get()) }
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
    single { OAuthTokenStore(get(), DriveSourceConfig.SOURCE_ID) }
    single { TokenProvider(get(), get(), now = { nowEpochMs() }) }
    single { DriveApi(get(), get()) }
    single { DriveUploader(get(), get(), get()) }
    single { DriveLinkCoordinator(get(), get(), get()) }
    single { UploadManager(get(), now = { nowEpochMs() }) }
    single { AccountsController(get(), get(), get()) }
    single { CameraUploadScanner(get(), get(), get()) }

    // The queue drives an UploadTarget; Drive is the only implementation so far,
    // and M4/M5 add MEGA/OneDrive behind the same interface.
    single<UploadTarget> { DriveUploadTarget(get()) }
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
