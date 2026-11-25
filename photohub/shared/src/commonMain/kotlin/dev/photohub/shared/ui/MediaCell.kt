package dev.photohub.shared.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 *
 * The top-left checkbox appears on desktop hover, and stays visible once the
 * user has entered multiselect mode (the only way to select on touch devices).
 */
@Composable
fun MediaCell(
    item: MediaItem,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val model = item.imageModel
    val showCheckbox = selectionMode || hovered

    Surface(
        tonalElevation = 1.dp,
        modifier = modifier
            .aspectRatio(1f)
            .hoverable(hoverInteraction),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (model == null) {
                Placeholder(item)
            } else {
                AsyncImage(
                    model = model,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (item.isVideoItem) {
                // Mark videos so a still frame is not mistaken for a photo.
                Text(
                    text = "▶",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }

            if (showCheckbox) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .size(24.dp),
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
