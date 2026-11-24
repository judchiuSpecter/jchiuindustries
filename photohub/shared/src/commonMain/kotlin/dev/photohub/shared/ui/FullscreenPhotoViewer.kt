package dev.photohub.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.photohub.shared.db.MediaItem
import java.io.File

/**
 * Fullscreen photo/video viewer that replaces the timeline grid.
 *
 * - Shows the original-resolution image if available (cached or via content:// URI)
 * - On Android with local source: Coil loads the content:// URI and displays the full image
 * - On Drive: shows the cached thumbnail + TODO download original button (TODO)
 * - On desktop (local source): passes the file path to Coil as a [File] model
 * - Close button dismisses back to the timeline
 */
@Composable
fun FullscreenPhotoViewer(
    item: MediaItem,
    onDismiss: () -> Unit,
) {
    // Resolve the image to display. Priority:
    // 1. Cached full-resolution original (e.g. Drive download) → File model.
    // 2. content:// MediaStore URI (Android local photo) → String model (Coil reads natively).
    // 3. Cached thumbnail → File model (desktop) or URI string.
    // 4. Absolute filesystem path (desktop local source) → File model.
    val imageModel: Any? = remember(item) {
        fun existingFile(path: String?): File? =
            path?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }

        // Content URIs must stay as strings so Coil's UriFetcher handles them.
        item.remoteThumbUrl?.takeIf { it.startsWith("content://") }
            ?: existingFile(item.originalCachePath)
            ?: existingFile(item.thumbCachePath)
            ?: existingFile(item.remoteId)
    }

    val isVideo = item.isVideo == 1L

    // Main content.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
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
                    // Placeholder when no image is resolvable.
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

        // Video indicator.
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 16.dp, y = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "▶",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Close button — dismiss back to the timeline. Universal across platforms.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 16.dp, y = 32.dp),
        ) {
            Button(onClick = onDismiss) {
                Text(
                    "✕",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
