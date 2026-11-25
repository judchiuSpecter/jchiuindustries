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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    onRetrySource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showAddSource by remember { mutableStateOf(false) }
    var megaTargetId by remember { mutableStateOf<String?>(null) }
    var megaInitialEmail by remember { mutableStateOf("") }
    var showMegaDialogForNew by remember { mutableStateOf(false) }
    var showNasDialog by remember { mutableStateOf(false) }
    var nasTargetId by remember { mutableStateOf<String?>(null) }

    // Storage is the source of truth, and it can change while this screen is
    // backgrounded (a link completing, tokens being cleared), so re-read on show.
    LaunchedEffect(Unit) {
        controller.refresh()
        // Quota is a live, account-wide number: fetch only while this screen is
        // open, not as part of the timeline sync.
        controller.refreshQuotas()
    }

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
                quotaLoading = state.quotaLoading,
                onLink = {
                    when (source.type) {
                        SourceType.MEGA -> {
                            megaTargetId = source.id
                            megaInitialEmail = source.displayName
                                .removePrefix("MEGA (").removeSuffix(")")
                        }
                        SourceType.NAS_ARCHIVE -> nasTargetId = source.id
                        else -> scope.launch { controller.linkDrive(source.id) }
                    }
                },
                onUnlink = { scope.launch { controller.unlinkSource(source.id) } },
                onSetTarget = { controller.setUploadTarget(source.id) },
                onUploadNow = controller::uploadNow,
                onRetry = { onRetrySource(source.id) },
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
            onMega = {
                showAddSource = false
                showMegaDialogForNew = true
                megaTargetId = null
                megaInitialEmail = ""
            },
            onNas = {
                showAddSource = false
                showNasDialog = true
                nasTargetId = null
            },
        )
    }

    if (megaTargetId != null || showMegaDialogForNew) {
        LinkMegaDialog(
            initialEmail = megaInitialEmail,
            busy = state.sources.any {
                it.id == megaTargetId && it.state == LinkState.LINKING
            },
            onDismiss = {
                showMegaDialogForNew = false
                megaTargetId = null
            },
            onSubmit = { email, password, mfa ->
                val target = megaTargetId
                showMegaDialogForNew = false
                megaTargetId = null
                scope.launch { controller.linkMega(email, password, mfa, target) }
            },
        )
    }

    if (nasTargetId != null || showNasDialog) {
        LinkNasDialog(
            busy = state.sources.any {
                it.id == nasTargetId && it.state == LinkState.LINKING
            },
            onDismiss = {
                showNasDialog = false
                nasTargetId = null
            },
            onSubmit = { host, username, password, remotePath ->
                val target = nasTargetId
                showNasDialog = false
                nasTargetId = null
                scope.launch { controller.linkNas(host, username, password, remotePath, sourceId = target) }
            },
        )
    }
}

@Composable
private fun SourceCard(
    account: SourceAccount,
    setupPath: String,
    quotaLoading: Boolean,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
    onSetTarget: () -> Unit,
    onUploadNow: () -> Unit,
    onRetry: () -> Unit,
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
                if (account.syncError != null) {
                    // A plain glyph rather than a material icon: the icon font
                    // extension is not pulled in and this is the one place in
                    // the app that would need it.
                    Text(
                        "  \u26A0",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
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
                        when (account.type) {
                            SourceType.MEGA -> "Not connected. Sign in with your MEGA email and password; " +
                                "only a session key is kept."
                            SourceType.NAS_ARCHIVE -> "Not connected. Connects over SFTP; " +
                                "PhotoHub only reads from the folder you choose."
                            else -> "Not connected. Signing in opens your browser; " +
                                "PhotoHub only gets access to files it creates itself."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onLink) { Text("Connect") }
                }

                account.state == LinkState.LINKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            when (account.type) {
                                SourceType.MEGA -> "  Connecting to MEGA…"
                                SourceType.NAS_ARCHIVE -> "  Connecting to the NAS…"
                                else -> "  Waiting for Google in your browser…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                account.state == LinkState.LINKED -> {
                    Text(
                        if (account.type == SourceType.NAS_ARCHIVE) {
                            "Connected. Read-only: PhotoHub never deletes or writes to the NAS."
                        } else {
                            "Connected. New photos are copied to PhotoHub/YYYY/MM."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (account.type != SourceType.NAS_ARCHIVE) {
                        val quota = account.quota
                        val storageLabel = if (account.type == SourceType.MEGA) {
                            "MEGA account storage"
                        } else {
                            "Google account storage"
                        }
                        when {
                            quota?.usedBytes != null && quota.limitBytes != null &&
                                quota.limitBytes!! > 0 -> {
                                val used = quota.usedBytes!!
                                val limit = quota.limitBytes!!
                                Text(
                                    "$storageLabel: ${formatBytes(used)} of ${formatBytes(limit)} used",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LinearProgressIndicator(
                                    progress = { (used.toFloat() / limit).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            quota?.usedBytes != null -> {
                                Text(
                                    "$storageLabel: ${formatBytes(quota.usedBytes!!)} used",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            quotaLoading -> {
                                Text(
                                    "Checking storage…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (account.canUpload) {
                            if (!account.isUploadTarget) {
                                OutlinedButton(onClick = onSetTarget) { Text("Use for uploads") }
                            } else {
                                // Uploads otherwise wait for the periodic pass, which
                                // needs Wi-Fi and a charged battery; this is the escape
                                // hatch for "send it now".
                                Button(onClick = onUploadNow) { Text("Upload now") }
                            }
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

            // A sync failure (as opposed to a link failure above) is sticky:
            // it stays until the user retries by hand, so it needs its own
            // explanation and action rather than just the title-row glyph.
            account.syncError?.let { syncError ->
                Spacer(Modifier.height(4.dp))
                Text(
                    syncError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "PhotoHub stopped trying automatically so it doesn't keep hitting " +
                        "the same error. Retry once the problem is fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/** The "+" picker. Providers with no implementation yet are shown but disabled. */
@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onGoogleDrive: () -> Unit,
    onMega: () -> Unit,
    onNas: () -> Unit,
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
                        SourceType.MEGA -> SourceChoice(
                            "MEGA",
                            "Sign in with your MEGA account and copy new photos there.",
                            true,
                            onMega,
                        )
                        SourceType.ONEDRIVE -> SourceChoice("OneDrive", "Planned — not implemented yet.", false, {})
                        SourceType.TAKEOUT -> SourceChoice(
                            "Google Photos Takeout",
                            "Import an existing Takeout library — planned.",
                            false,
                            {},
                        )
                        SourceType.NAS_ARCHIVE -> SourceChoice(
                            "NAS archive",
                            "Connect over SFTP and read photos already archived there.",
                            true,
                            onNas,
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

/** Credentials for one MEGA instance. The password lives only in Compose state. */
@Composable
private fun LinkMegaDialog(
    initialEmail: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String?) -> Unit,
) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect MEGA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Only a resumable session is stored. Your password is never saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mfa,
                    onValueChange = { mfa = it },
                    label = { Text("2FA code (if enabled)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(email.trim(), password, mfa.takeIf { it.isNotBlank() })
                },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            ) {
                Text(if (busy) "Connecting…" else "Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

/** Connection details for one NAS instance over SFTP. The password lives only in Compose state. */
@Composable
private fun LinkNasDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (host: String, username: String, password: String, remotePath: String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remotePath by remember { mutableStateOf("/share/Photos") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect NAS archive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Connects over SFTP (port 22). Read-only — PhotoHub never writes to or deletes from the NAS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host or IP") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text("Folder to index") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(host.trim(), username.trim(), password, remotePath.trim())
                },
                enabled = !busy && host.isNotBlank() && username.isNotBlank() &&
                    password.isNotBlank() && remotePath.isNotBlank(),
            ) {
                Text(if (busy) "Connecting…" else "Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
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

/** Human-readable quota sizes; Google reports bytes in decimal units. */
private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    val number = if (unit == 0 || value >= 100.0) {
        value.toLong().toString()
    } else {
        value.toString().take(4)
    }
    return "$number ${units[unit]}"
}
