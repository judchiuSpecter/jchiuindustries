package dev.photohub.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.DesktopCredentialStore
import dev.photohub.shared.auth.DesktopRedirectCapture
import dev.photohub.shared.auth.RedirectCapture
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.FullscreenImageController
import dev.photohub.shared.data.SettingsStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.di.initKoin
import dev.photohub.shared.platform.appDataDirFile
import dev.photohub.shared.ui.App
import org.koin.core.context.GlobalContext
import org.koin.dsl.module

fun main() {
    initKoin {
        // Platform-specific bindings the shared module declares but cannot
        // construct: desktop encrypts credentials to a key file and captures the
        // OAuth redirect on a loopback socket.
        modules(
            module {
                single<CredentialStore> { DesktopCredentialStore(appDataDirFile()) }
                factory<RedirectCapture> { DesktopRedirectCapture() }
            },
        )
    }
    val koin = GlobalContext.get()
    val controller = koin.get<TimelineController>()
    val accounts = koin.get<AccountsController>()
    val settings = koin.get<SettingsStore>()
    val fullscreenImages = koin.get<FullscreenImageController>()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "PhotoHub",
            icon = painterResource("icon.png"),
            state = rememberWindowState(width = 1100.dp, height = 800.dp),
        ) {
            // Desktop has no permission gate, so scan on launch.
            App(
                    controller = controller,
                    accounts = accounts,
                    refreshOnStart = true,
                    settings = settings,
                    fullscreenImages = fullscreenImages,
                )
        }
    }
}
