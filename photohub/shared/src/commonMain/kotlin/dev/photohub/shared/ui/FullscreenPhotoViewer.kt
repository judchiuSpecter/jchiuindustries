package dev.photohub.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.photohub.shared.data.FullscreenImageController
import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.platform.FullscreenDismissHandler
import java.io.File

/**
 * Fullscreen photo/video viewer that replaces the timeline grid.
 *
 * - Local items load from their `content://` URI (Android) or file path (desktop).
 * - Cloud items start from their thumbnail URL and download the original through
 *   the owning source so the full image is shown once available.
 * - Dismissal is platform-native: device Back on Android, Escape on desktop.
 *   There is deliberately no on-screen close button. On desktop, Left/Right
 *   arrow keys step through [onPrevious]/[onNext] since there is no swipe
 *   gesture to rely on.
 */
@Composable
fun FullscreenPhotoViewer(
    item: MediaItem,
    onDismiss: () -> Unit,
    images: FullscreenImageController? = null,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    var imageModel by remember(item.id) { mutableStateOf(initialModel(item)) }
    var downloading by remember(item.id) { mutableStateOf(false) }

    // Android Back / desktop Escape + arrow keys. Placed first so the
    // focusable desktop box sits behind the image and cannot intercept clicks.
    FullscreenDismissHandler(onDismiss = onDismiss, onPrevious = onPrevious, onNext = onNext)

    // A cloud item starts with its thumbnail URL. Try to upgrade it to the
    // original through the source that owns this account; failure is non-fatal
    // because the thumbnail is already visible.
    LaunchedEffect(item.id) {
        val current = imageModel
        if (images == null || current !is String || !current.startsWith("https://")) return@LaunchedEffect
        downloading = true
        val original = images.ensureOriginal(item)
        if (original != null) imageModel = File(original)
        downloading = false
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Image not available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (downloading) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text(
                    "  Loading full image…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Videos are not playable yet; at least mark them so a still frame is
        // not mistaken for a photo.
        if (item.isVideo == 1L) {
            Text(
                "▶",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-12).dp, y = (-12).dp),
            )
        }
    }
}

/** Resolves what can be shown without a network request. */
private fun initialModel(item: MediaItem): Any? {
    fun existingFile(path: String?): File? =
        path?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }

    // Content URIs must stay as strings so Coil's UriFetcher handles them.
    return item.remoteThumbUrl?.takeIf { it.startsWith("content://") }
        ?: existingFile(item.originalCachePath)
        ?: existingFile(item.thumbCachePath)
        ?: existingFile(item.remoteId)
        // A Drive thumbnailLink is a plain https URL. Coil loads it directly
        // (the grid does the same), so the image appears immediately while the
        // original is fetched in the background.
        ?: item.remoteThumbUrl?.takeIf { it.startsWith("https://") }
}
