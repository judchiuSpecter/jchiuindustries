package dev.photohub.shared.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.AccountsController.LinkState
import kotlinx.coroutines.launch

/**
 * Accounts screen: connect or disconnect the cloud PhotoHub uploads to.
 *
 * Deliberately plain. The design goal is a Google-Photos-shaped timeline
 * without the surrounding product, so settings are a short list of real
 * decisions rather than a browsable tree.
 */
@Composable
fun AccountsScreen(
    controller: AccountsController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Storage is the source of truth, and it can change while this screen is
    // backgrounded (a link completing, tokens being cleared), so re-read on show.
    LaunchedEffect(Unit) { controller.refresh() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)

        Text(
            "PhotoHub uploads a copy of new camera photos to one cloud. " +
                "Your local photos are never deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DriveCard(
            account = state.drive,
            onLink = { scope.launch { controller.linkDrive() } },
            onUnlink = controller::unlinkDrive,
        )

        state.message?.let { message ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // MEGA and OneDrive are later milestones. Showing them as explicitly
        // unavailable is clearer than hiding them and leaving the user unsure
        // whether PhotoHub supports them at all.
        PendingCard("MEGA", "Planned — not implemented yet.")
        PendingCard("OneDrive", "Planned — not implemented yet.")
    }
}

@Composable
private fun DriveCard(
    account: AccountsController.DriveAccount,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.fillMaxWidth(0.02f))
                if (account.isUploadTarget) {
                    Text(
                        "  · upload target",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            when (account.state) {
                // Not an error: a fresh install has no client id, and only the
                // user can supply one. Show exactly what to do and where.
                LinkState.NOT_CONFIGURED -> {
                    Text(
                        "Setup needed. PhotoHub does not ship a Google OAuth client ID — " +
                            "create one (Drive API, \"Desktop app\", scope drive.file) and save it as:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        account.setupPath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        """{ "clientId": "…apps.googleusercontent.com" }""",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Restart PhotoHub after creating the file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LinkState.NOT_LINKED -> {
                    Text(
                        "Not connected. Signing in opens your browser; " +
                            "PhotoHub only gets access to files it creates itself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onLink) { Text("Connect Google Drive") }
                }

                LinkState.LINKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            "  Waiting for Google in your browser…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                LinkState.LINKED -> {
                    Text(
                        "Connected. New photos upload to PhotoHub/YYYY/MM.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onUnlink) { Text("Disconnect") }
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

@Composable
private fun PendingCard(name: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
