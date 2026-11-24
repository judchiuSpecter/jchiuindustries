package dev.photohub.shared.platform

import androidx.compose.runtime.Composable

/**
 * Platform-native dismissal for the fullscreen viewer.
 *
 * Android uses the system Back button; desktop uses Escape. Keeping this as a
 * platform seam means the shared viewer has no extra button competing with the
 * gesture the OS already gives the user.
 */
@Composable
expect fun FullscreenDismissHandler(onDismiss: () -> Unit)
