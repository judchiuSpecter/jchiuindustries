package dev.photohub.shared.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Back dismisses the viewer rather than leaving the app. */
@Composable
actual fun FullscreenDismissHandler(onDismiss: () -> Unit) {
    BackHandler(enabled = true, onBack = onDismiss)
}
