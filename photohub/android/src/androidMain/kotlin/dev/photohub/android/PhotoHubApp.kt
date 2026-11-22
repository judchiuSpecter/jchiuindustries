package dev.photohub.android

import android.app.Application
import dev.photohub.shared.auth.AndroidCredentialStore
import dev.photohub.shared.auth.AndroidRedirectCapture
import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.RedirectCapture
import dev.photohub.shared.di.initKoin
import dev.photohub.shared.platform.AndroidPlatform
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class PhotoHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.init(this)
        initKoin {
            androidContext(this@PhotoHubApp)
            // Platform-specific bindings the shared module declares but cannot
            // construct: the credential store is keystore-backed here.
            modules(
                module {
                    single<CredentialStore> { AndroidCredentialStore(this@PhotoHubApp) }
                    // Shared across the sign-in flow so the activity receiving
                    // the redirect intent can hand it to the waiting coroutine.
                    single {
                        AndroidRedirectCapture(
                            AndroidRedirectCapture.redirectUriFor(
                                get<GoogleOAuthConfig>().clientId,
                            ),
                        )
                    }
                    single<RedirectCapture> { get<AndroidRedirectCapture>() }
                },
            )
        }
    }
}
