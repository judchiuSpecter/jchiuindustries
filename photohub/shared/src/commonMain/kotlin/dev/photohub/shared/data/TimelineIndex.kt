package dev.photohub.shared.data

import kotlinx.datetime.LocalDate

/**
 * Position lookup for the date scrubber.
 *
 * The timeline grid is a flat lazy list where each day contributes one header
 * plus its items, so the grid's item index is not the photo index. This
 * precomputes the mapping in both directions once per timeline change, because
 * the scrubber needs it on every drag frame and a scan per frame would stutter
 * on a library of thousands.
 *
 * Positions are weighted by item count rather than by section, so a day with
 * 200 photos occupies proportionally more of the scrubber than a day with one.
 * Weighting by section instead would make a long holiday the same size as a
 * single screenshot and the scrubber would not track the thumb.
 */
class TimelineIndex(sections: List<TimelineSection>) {

    /** One entry per day section, in timeline order (newest first). */
    data class Entry(
        val date: LocalDate,
        /** Index of this day's header in the lazy grid. */
        val gridIndex: Int,
        /** Start of this day's span of the scrubber, 0f..1f. */
        val startFraction: Float,
        /** End of this day's span, 0f..1f. */
        val endFraction: Float,
    )

    val entries: List<Entry> = buildEntries(sections)

    /** True when there is nothing to scrub through. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * The day shown at scrubber position [fraction].
     *
     * Returns null only for an empty timeline, so callers can render the
     * scrubber unconditionally without a special case per frame.
     */
    fun entryAt(fraction: Float): Entry? {
        if (entries.isEmpty()) return null
        val clamped = fraction.coerceIn(0f, 1f)
        // A linear scan is fine: entries are days, not photos, so even a decade
        // of daily photos is a few thousand — and a drag touches one per frame.
        return entries.firstOrNull { clamped < it.endFraction } ?: entries.last()
    }

    /** The day that owns grid position [gridIndex], for the label while scrolling. */
    fun entryForGridIndex(gridIndex: Int): Entry? =
        entries.lastOrNull { it.gridIndex <= gridIndex } ?: entries.firstOrNull()

    /** Scrubber position to show when the grid is resting at [gridIndex]. */
    fun fractionForGridIndex(gridIndex: Int): Float =
        entryForGridIndex(gridIndex)?.startFraction ?: 0f

    /**
     * Distinct months in timeline order, for tick marks on the track.
     *
     * Each month maps to its first (newest) day, so tapping a tick lands at the
     * top of that month rather than in the middle of it.
     */
    fun monthMarkers(): List<Entry> {
        val seen = HashSet<Int>()
        return entries.filter { entry ->
            // year * 100 + month is a cheap stable key and avoids allocating.
            seen.add(entry.date.year * 100 + entry.date.monthNumber)
        }
    }

    /**
     * Distinct years in timeline order, for the fixed year labels drawn
     * directly on the track. Unlike [monthMarkers], these stay visible all
     * the time rather than only while dragging, since the drag bubble itself
     * only has room for day + month.
     */
    fun yearMarkers(): List<Entry> {
        val seen = HashSet<Int>()
        return entries.filter { entry -> seen.add(entry.date.year) }
    }

    private fun buildEntries(sections: List<TimelineSection>): List<Entry> {
        val totalItems = sections.sumOf { it.items.size }
        if (totalItems == 0) return emptyList()

        var gridIndex = 0
        var itemsSoFar = 0

        return sections.map { section ->
            val start = itemsSoFar.toFloat() / totalItems
            itemsSoFar += section.items.size
            val end = itemsSoFar.toFloat() / totalItems

            val entry = Entry(
                date = section.date,
                gridIndex = gridIndex,
                startFraction = start,
                endFraction = end,
            )
            // This day consumed its header plus one slot per photo.
            gridIndex += 1 + section.items.size
            entry
        }
    }
}
