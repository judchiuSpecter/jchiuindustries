package dev.photohub.shared.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.photohub.shared.data.TimelineIndex
import dev.photohub.shared.data.TimelineSection
import dev.photohub.shared.data.groupByDay
import dev.photohub.shared.db.MediaItem
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char

/**
 * The single timeline grid: one adaptive grid of square thumbnails, split into
 * day sections with a header per day — the Google Photos shape, without the
 * Discover/Memories surfaces.
 *
 * Selection is hoisted so the app can run Delete/Archive on the chosen items and
 * clear the selection when the action completes. Hover reveals a checkbox on
 * desktop; long-press is the touch fallback for entering multiselect mode.
 */
@Composable
fun TimelineScreen(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    selectedIds: Set<String> = emptySet(),
    onSelectionChange: (Set<String>) -> Unit = {},
    onItemClick: (MediaItem) -> Unit = {},
    onDeleteSelected: (List<MediaItem>) -> Unit = {},
    onArchiveSelected: (List<MediaItem>) -> Unit = {},
    canArchive: (MediaItem) -> Boolean = { true },
    busy: Boolean = false,
) {
    if (items.isEmpty()) {
        EmptyTimeline(modifier)
        return
    }

    // Grouping is derived state: recompute only when the item list changes.
    val sections = remember(items) { items.groupByDay() }
    val index = remember(sections) { TimelineIndex(sections) }
    val selectedItems = remember(items, selectedIds) { items.filter { it.id in selectedIds } }
    val selectionMode = selectedIds.isNotEmpty()

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    fun toggle(id: String) {
        onSelectionChange(if (id in selectedIds) selectedIds - id else selectedIds + id)
    }

    // Where the rail's thumb should rest, derived from the grid rather than
    // tracked separately, so a normal flick moves the thumb too.
    val scrollFraction by remember(index) {
        derivedStateOf { index.fractionForGridIndex(gridState.firstVisibleItemIndex) }
    }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (selectionMode) 76.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (section in sections) {
                item(
                    key = "header-${section.date}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    DayHeader(
                        section = section,
                        selectionMode = selectionMode,
                        allSelected = section.items.all { it.id in selectedIds },
                        onToggleAll = {
                            val ids = section.items.map { it.id }.toSet()
                            onSelectionChange(
                                if (ids.all { it in selectedIds }) selectedIds - ids
                                else selectedIds + ids,
                            )
                        },
                    )
                }
                items(section.items, key = { it.id }) { media ->
                    MediaCell(
                        item = media,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (selectionMode) toggle(media.id) else onItemClick(media)
                            },
                            onLongClick = { toggle(media.id) },
                        ),
                        selectionMode = selectionMode,
                        selected = media.id in selectedIds,
                        onToggleSelection = { toggle(media.id) },
                    )
                }
            }
        }

        // Only worth showing once the grid is long enough to be tedious.
        if (items.size >= MIN_ITEMS_FOR_RAIL) {
            DateScrubber(
                index = index,
                scrollFraction = scrollFraction,
                onSeek = { entry ->
                    scope.launch {
                        // Jump, not animate: a drag produces a seek per frame and
                        // animations would queue up and lag behind the finger.
                        gridState.scrollToItem(entry.gridIndex)
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (selectionMode) {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${selectedItems.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    TextButton(
                        onClick = { onSelectionChange(emptySet()) },
                        enabled = !busy,
                    ) { Text("Cancel") }
                    Button(
                        onClick = { onArchiveSelected(selectedItems) },
                        enabled = !busy && selectedItems.all(canArchive),
                    ) { Text("Archive") }
                    Button(
                        onClick = { onDeleteSelected(selectedItems) },
                        enabled = !busy,
                    ) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    section: TimelineSection,
    selectionMode: Boolean,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    Surface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(hoverInteraction),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode || hovered) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { onToggleAll() },
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            Text(
                text = section.date.formatHeader(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
    }
}

/** "2 January 2026" — unambiguous and locale-neutral for a personal app. */
private fun LocalDate.formatHeader(): String {
    val fmt = LocalDate.Format {
        day(Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        year()
    }
    return fmt.format(this)
}

@Composable
private fun EmptyTimeline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No photos yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "Grant photo access or add a source to get started.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
