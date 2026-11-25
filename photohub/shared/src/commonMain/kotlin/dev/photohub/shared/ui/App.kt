package dev.photohub.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.photohub.shared.data.ArchiveController
import dev.photohub.shared.data.FullscreenImageController
import dev.photohub.shared.data.PhotoActionsController
import dev.photohub.shared.data.SettingsStore
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.db.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
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
    photoActions: PhotoActionsController? = null,
    archiver: ArchiveController? = null,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.TIMELINE) }
    val prefs by settings.settings.collectAsState()

    val noActions = remember { MutableStateFlow(PhotoActionsController.State()) }
    val actionState by (photoActions?.state ?: noActions).collectAsState()

    /** Item being shown full-screen, or null when on the timeline grid. */
    var selectedItem by remember { mutableStateOf<MediaItem?>(null) }
    /** Ids selected on the timeline; empty means normal single-item navigation. */
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Non-null while the delete confirmation dialog is on screen. */
    var pendingDelete by remember { mutableStateOf<List<MediaItem>?>(null) }

    LaunchedEffect(refreshOnStart) {
        controller.loadCached()
        // Re-arm camera upload if an account is already linked. WorkManager
        // schedules survive restarts but not a reinstall, and a user who linked
        // in an earlier build never got one.
        accounts?.syncBackgroundWork()
        if (refreshOnStart) controller.refresh()
    }

    fun finishAction() {
        scope.launch {
            controller.refresh()
            selectedIds = emptySet()
            selectedItem = null
        }
    }

    fun performDelete(items: List<MediaItem>) {
        val actions = photoActions ?: return
        if (items.isEmpty()) return
        scope.launch {
            actions.delete(items)
            finishAction()
        }
    }

    fun requestDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (prefs.confirmDelete) pendingDelete = items else performDelete(items)
    }

    fun performArchive(items: List<MediaItem>) {
        val actions = photoActions ?: return
        if (items.isEmpty()) return
        scope.launch {
            actions.archive(items)
            finishAction()
        }
    }

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
                            if (state.isRefreshing || actionState.running) {
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
                            actionState.message?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                )
                            }
                            TimelineScreen(
                                items = state.items,
                                modifier = Modifier.fillMaxSize(),
                                selectedIds = selectedIds,
                                onSelectionChange = { selectedIds = it },
                                onItemClick = { selectedItem = it },
                                onDeleteSelected = { requestDelete(it) },
                                onArchiveSelected = { performArchive(it) },
                                canArchive = { item -> photoActions?.canArchive(item) ?: false },
                                busy = actionState.running,
                            )
                        }

                        Screen.ACCOUNTS -> accounts?.let {
                            AccountsScreen(
                                controller = it,
                                themeMode = prefs.themeMode,
                                onThemeModeChange = settings::setThemeMode,
                                confirmDelete = prefs.confirmDelete,
                                onConfirmDeleteChange = settings::setConfirmDelete,
                                onRetrySource = { id -> scope.launch { controller.retrySource(id) } },
                                archiver = archiver,
                                onArchiveComplete = { scope.launch { controller.refresh() } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // Fullscreen photo viewer overlay
                selectedItem?.let { current ->
                    val index = state.items.indexOfFirst { it.id == current.id }
                    FullscreenPhotoViewer(
                        item = current,
                        onDismiss = { selectedItem = null },
                        images = fullscreenImages,
                        onPrevious = {
                            if (index > 0) selectedItem = state.items[index - 1]
                        },
                        onNext = {
                            if (index in 0 until state.items.lastIndex) {
                                selectedItem = state.items[index + 1]
                            }
                        },
                        onDelete = { requestDelete(listOf(current)) },
                        onArchive = { performArchive(listOf(current)) },
                        canArchive = photoActions?.canArchive(current) ?: false,
                        busy = actionState.running,
                    )
                }

                pendingDelete?.let { items ->
                    AlertDialog(
                        onDismissRequest = { pendingDelete = null },
                        title = {
                            Text(
                                if (items.size == 1) "Delete this photo?" else "Delete ${items.size} photos?",
                            )
                        },
                        text = {
                            Text(
                                "This removes the item(s) from their current source. " +
                                    "This cannot be undone.",
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    pendingDelete = null
                                    performDelete(items)
                                },
                            ) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                        },
                    )
                }
            }
        }
    }
}
