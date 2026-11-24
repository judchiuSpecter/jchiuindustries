package dev.photohub.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.AccountsController.LinkState
import dev.photohub.shared.data.AccountsController.SourceAccount
import dev.photohub.shared.data.ThemeMode
import dev.photohub.shared.model.SourceType
import kotlinx.coroutines.launch

/**
 * Sources screen: every configured source instance, an "add source" picker, and
 * the settings that are not tied to a particular source.
 *
 * Deliberately plain. The design goal is a Google-Photos-shaped timeline
 * without the surrounding product, so settings are a short list of real
 * decisions rather than a browsable tree.
 */
@Composable
fun AccountsScreen(
    controller: AccountsController,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddSource by remember { mutableStateOf(false) }

    // Storage is the source of truth, and it can change while this screen is
    // backgrounded (a link completing, tokens being cleared), so re-read on show.
    LaunchedEffect(Unit) { controller.refresh() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sources", style = MaterialTheme.typography.headlineSmall)

        Text(
            "Every connected source appears in one timeline. " +
                "PhotoHub copies new camera photos to the cloud you choose; " +
                "your local photos are never deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.oauthConfigured) {
            SetupNeededCard(state.setupPath)
        }

        for (source in state.sources) {
            SourceCard(
                account = source,
                setupPath = state.setupPath,
                onLink = { scope.launch { controller.linkDrive(source.id) } },
                onUnlink = { scope.launch { controller.unlinkSource(source.id) } },
                onSetTarget = { controller.setUploadTarget(source.id) },
                onUploadNow = controller::uploadNow,
            )
        }

        Button(
            onClick = { showAddSource = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("+ Add source")
        }

        state.message?.let { message ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        AppearanceCard(themeMode = themeMode, onChange = onThemeModeChange)
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onGoogleDrive = {
                showAddSource = false
                scope.launch { controller.linkDrive() }
            },
        )
    }
}

@Composable
private fun SourceCard(
    account: SourceAccount,
    setupPath: String,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onSetTarget: () -> Unit,
    onUploadNow: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                if (account.isUploadTarget) {
                    Text(
                        "  · upload target",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                when (account.type) {
                    SourceType.LOCAL -> "This device"
                    SourceType.GOOGLE_DRIVE -> "Google Drive"
                    SourceType.MEGA -> "MEGA"
                    SourceType.ONEDRIVE -> "OneDrive"
                    SourceType.TAKEOUT -> "Google Photos Takeout"
                    SourceType.NAS_ARCHIVE -> "NAS archive"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                account.type == SourceType.LOCAL -> {
                    Text(
                        "Indexed automatically from this device. " +
                            "You can disable it only by revoking PhotoHub's photo permission.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                account.state == LinkState.UNSUPPORTED -> {
                    Text(
                        "This source type is not implemented yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                account.state == LinkState.NOT_CONFIGURED -> {
                    Text(
                        "Setup needed. Save your Google OAuth client file to:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        setupPath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                account.state == LinkState.NOT_LINKED -> {
                    Text(
                        "Not connected. Signing in opens your browser; " +
                            "PhotoHub only gets access to files it creates itself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onLink) { Text("Connect") }
                }

                account.state == LinkState.LINKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            "  Waiting for Google in your browser…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                account.state == LinkState.LINKED -> {
                    Text(
                        "Connected. New photos are copied to PhotoHub/YYYY/MM.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!account.isUploadTarget) {
                            OutlinedButton(onClick = onSetTarget) { Text("Use for uploads") }
                        } else {
                            // Uploads otherwise wait for the periodic pass, which
                            // needs Wi-Fi and a charged battery; this is the escape
                            // hatch for "send it now".
                            Button(onClick = onUploadNow) { Text("Upload now") }
                        }
                        OutlinedButton(onClick = onUnlink) { Text("Disconnect") }
                    }
                }
            }

            account.error?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The "+" picker. Providers with no implementation yet are shown but disabled. */
@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onGoogleDrive: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a source") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (type in SourceType.entries) {
                    val (label, detail, enabled, onClick) = when (type) {
                        SourceType.LOCAL -> SourceChoice(
                            "This device",
                            "Added automatically — no setup needed.",
                            false,
                            {},
                        )
                        SourceType.GOOGLE_DRIVE -> SourceChoice(
                            "Google Drive",
                            "Sign in and copy new photos into your Drive.",
                            true,
                            onGoogleDrive,
                        )
                        SourceType.MEGA -> SourceChoice("MEGA", "Planned — not implemented yet.", false, {})
                        SourceType.ONEDRIVE -> SourceChoice("OneDrive", "Planned — not implemented yet.", false, {})
                        SourceType.TAKEOUT -> SourceChoice(
                            "Google Photos Takeout",
                            "Import an existing Takeout library — planned.",
                            false,
                            {},
                        )
                        SourceType.NAS_ARCHIVE -> SourceChoice(
                            "NAS archive",
                            "Archive older photos to your NAS — planned.",
                            false,
                            {},
                        )
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One row in the add-source picker. */
private data class SourceChoice(
    val label: String,
    val detail: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SetupNeededCard(setupPath: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google sign-in setup", style = MaterialTheme.typography.titleMedium)
            Text(
                "PhotoHub does not ship a Google OAuth client ID — " +
                    "create one (Drive API, \"Desktop app\", scope drive.file) and save it as:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                setupPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                """{ "clientId": "…apps.googleusercontent.com", "clientSecret": "…" }""",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Restart PhotoHub after creating the file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Light/dark selector.
 *
 * Three explicit options rather than a two-state switch: "System" has to be
 * reachable, otherwise a user who once tapped Dark can never get back to
 * following their phone's night schedule.
 */
@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ThemeMode.entries) {
                    val selected = mode == themeMode
                    // Filled for the active choice, outlined for the rest, so
                    // the current theme is readable at a glance.
                    if (selected) {
                        Button(onClick = { onChange(mode) }) { Text(mode.label()) }
                    } else {
                        OutlinedButton(onClick = { onChange(mode) }) { Text(mode.label()) }
                    }
                }
            }
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
