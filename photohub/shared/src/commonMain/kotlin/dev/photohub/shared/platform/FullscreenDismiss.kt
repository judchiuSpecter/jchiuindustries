package dev.photohub.shared.platform

import androidx.compose.runtime.Composable

/**
 * Platform-native dismissal and navigation for the fullscreen viewer.
 *
 * Android uses the system Back button to dismiss; desktop additionally binds
 * Escape to dismiss and the Left/Right arrow keys to [onPrevious]/[onNext], since
 * a desktop user has a keyboard but no swipe gesture. Keeping this as a platform
 * seam means the shared viewer has no extra buttons competing with gestures the
 * OS (or input device) already gives the user.
 */
@Composable
expect fun FullscreenDismissHandler(
    onDismiss: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
)
