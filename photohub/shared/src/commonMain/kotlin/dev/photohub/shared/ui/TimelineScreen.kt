package dev.photohub.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * Items arrive newest-first from the repository; grouping preserves that order.
 */
@Composable
fun TimelineScreen(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    onItemClick: (MediaItem) -> Unit = {},
) {
    if (items.isEmpty()) {
        EmptyTimeline(modifier)
        return
    }

    // Grouping is derived state: recompute only when the item list changes.
    val sections = remember(items) { items.groupByDay() }
    val index = remember(sections) { TimelineIndex(sections) }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

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
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (section in sections) {
                // A single grid cell, not a full-width banner: a day header
                // that always breaks the row wastes most of a row once a day
                // has only a few photos. Left unspanned, the grid flows date
                // chips and thumbnails from several sparse days onto the same
                // row instead, the way a day with plenty of photos already
                // fills a row on its own.
                item(key = "header-${section.date}") {
                    DayHeaderChip(section)
                }
                items(section.items, key = { it.id }) { media ->
                    MediaCell(
                        item = media,
                        modifier = Modifier.clickable { onItemClick(media) },
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
    }
}

/**
 * The date label for one day, sized as a single grid cell rather than a
 * full-width banner so sparse days share a row with their neighbors instead
 * of each claiming a whole row for a handful of photos. Small type and a
 * near-transparent background keep it reading as a label among thumbnails,
 * not another block of solid chrome.
 */
@Composable
private fun DayHeaderChip(section: TimelineSection) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.aspectRatio(1f),
    ) {
        Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            Text(
                text = section.date.formatHeader(),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * "Jan 2 '24" — short enough for a single line in a cell-sized chip, but still
 * carries the year: this library spans 2002-2024, where month/day alone would
 * be ambiguous across more than twenty copies of every date.
 */
private fun LocalDate.formatHeader(): String {
    val fmt = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day(Padding.NONE)
        chars(" '")
        yearTwoDigits(baseYear = 2000)
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
