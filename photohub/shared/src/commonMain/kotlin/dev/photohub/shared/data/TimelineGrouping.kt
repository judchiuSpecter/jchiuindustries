package dev.photohub.shared.data

import dev.photohub.shared.db.MediaItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One day's worth of photos in the timeline, newest day first. */
data class TimelineSection(
    val date: LocalDate,
    val items: List<MediaItem>,
)

/**
 * Groups timeline rows into day sections using the device's local time zone, so
 * a photo taken at 11pm stays on the day it was taken rather than shifting in UTC.
 *
 * Input is expected to already be sorted newest-first (as `timelinePage` returns);
 * the grouping preserves that order both across and within sections.
 */
fun List<MediaItem>.groupByDay(
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): List<TimelineSection> =
    groupBy { item ->
        Instant.fromEpochMilliseconds(item.dateTaken).toLocalDateTime(timeZone).date
    }.map { (date, items) -> TimelineSection(date, items) }

/**
 * Collapses rows that are the same physical photo present in more than one
 * source, keyed by `contentHash`. The copy from the most-preferred source wins,
 * which keeps the offline-instant local copy ahead of a cloud duplicate.
 *
 * Rows without a hash are always kept — an unhashed item cannot be proven to be
 * a duplicate, and silently hiding photos would be worse than showing two.
 */
fun List<MediaItem>.dedupeByContent(
    preferredSourceIds: List<String>,
): List<MediaItem> {
    fun rank(sourceId: String): Int =
        preferredSourceIds.indexOf(sourceId).let { if (it < 0) Int.MAX_VALUE else it }

    val bestByHash = HashMap<String, MediaItem>()
    for (item in this) {
        val hash = item.contentHash ?: continue
        val current = bestByHash[hash]
        if (current == null || rank(item.sourceId) < rank(current.sourceId)) {
            bestByHash[hash] = item
        }
    }

    return filter { item ->
        val hash = item.contentHash ?: return@filter true
        bestByHash[hash]?.id == item.id
    }
}
