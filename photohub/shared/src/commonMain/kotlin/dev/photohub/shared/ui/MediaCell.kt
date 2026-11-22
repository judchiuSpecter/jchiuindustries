package dev.photohub.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.photohub.shared.db.MediaItem

/**
 * A single square cell in the timeline grid.
 *
 * Loads the item's thumbnail through Coil when one is available ([imageModel]),
 * and falls back to a labelled placeholder for items that are indexed but not
 * yet cached — which keeps the grid stable while a sync is still filling in.
 */
@Composable
fun MediaCell(item: MediaItem, modifier: Modifier = Modifier) {
    Surface(
        tonalElevation = 1.dp,
        modifier = modifier.aspectRatio(1f),
    ) {
        val model = item.imageModel
        if (model == null) {
            Placeholder(item)
            return@Surface
        }

        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = model,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.isVideoItem) {
                // Mark videos so a still frame is not mistaken for a photo.
                Text(
                    text = "▶",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun Placeholder(item: MediaItem) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Text(
            text = item.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(4.dp),
        )
    }
}
