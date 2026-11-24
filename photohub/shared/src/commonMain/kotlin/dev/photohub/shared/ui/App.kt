package dev.photohub.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.FullscreenImageController
import dev.photohub.shared.data.SettingsStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.db.MediaItem
import kotlinx.coroutines.launch

/** The two screens PhotoHub has. Kept as an enum — a nav library would be overkill. */
private enum class Screen { TIMELINE, ACCOUNTS }

/**
 * Root composable shared by Android and desktop.
 *
 * Renders whatever the [TimelineController] has cached and triggers a refresh
 * on first composition. [refreshOnStart] is false on Android until the media
 * permission has been granted, so the scan is not wasted.
 *
 * [accounts] is nullable so a host can still show the timeline if the accounts
 * graph is unavailable; the settings action is simply hidden in that case.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    controller: TimelineController,
    accounts: AccountsController? = null,
    refreshOnStart: Boolean = true,
    settings: SettingsStore = SettingsStore(),
    fullscreenImages: FullscreenImageController? = null,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.TIMELINE) }

    LaunchedEffect(refreshOnStart) {
        controller.loadCached()
        // Re-arm camera upload if an account is already linked. WorkManager
        // schedules survive restarts but not a reinstall, and a user who linked
        // in an earlier build never got one.
        accounts?.syncBackgroundWork()
        if (refreshOnStart) controller.refresh()
    }

    val prefs by settings.settings.collectAsState()

    /** Item being shown full-screen, or null when on the timeline grid. */
    var selectedItem by remember { mutableStateOf<MediaItem?>(null) }

    PhotoHubTheme(mode = prefs.themeMode) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (screen) {
                                Screen.TIMELINE -> "PhotoHub"
                                Screen.ACCOUNTS -> "Settings"
                            },
                        )
                    },
                    navigationIcon = {
                        if (screen != Screen.TIMELINE) {
                            IconButton(onClick = { screen = Screen.TIMELINE }) {
                                Text("\u2190")
                            }
                        }
                    },
                    actions = {
                        if (screen == Screen.TIMELINE) {
                            if (state.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                                )
                            } else {
                                IconButton(onClick = { scope.launch { controller.refresh() } }) {
                                    Text("\u21BB")
                                }
                            }
                            if (accounts != null) {
                                IconButton(onClick = { screen = Screen.ACCOUNTS }) {
                                    Text("\u2699")
                                }
                            }
                        }
                    },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                Column(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.TIMELINE -> {
                            state.error?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                )
                            }
                            TimelineScreen(
                                items = state.items,
                                modifier = Modifier.fillMaxSize(),
                                onItemClick = { selectedItem = it },
                            )
                        }

                        Screen.ACCOUNTS -> accounts?.let {
                            AccountsScreen(
                                controller = it,
                                themeMode = prefs.themeMode,
                                onThemeModeChange = settings::setThemeMode,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // Fullscreen photo viewer overlay
                if (selectedItem != null) {
                    FullscreenPhotoViewer(
                        item = selectedItem!!,
                        onDismiss = { selectedItem = null },
                        images = fullscreenImages,
                    )
                }
            }
        }
    }
}
