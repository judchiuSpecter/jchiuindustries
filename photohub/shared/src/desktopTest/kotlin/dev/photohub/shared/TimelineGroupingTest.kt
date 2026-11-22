package dev.photohub.shared

import dev.photohub.shared.data.dedupeByContent
import dev.photohub.shared.data.groupByDay
import dev.photohub.shared.db.MediaItem
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineGroupingTest {

    private fun item(
        id: String,
        dateTaken: Long,
        sourceId: String = "local",
        contentHash: String? = null,
    ) = MediaItem(
        id = id,
        sourceId = sourceId,
        remoteId = id,
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        isVideo = 0,
        sizeBytes = 1,
        width = null,
        height = null,
        dateTaken = dateTaken,
        contentHash = contentHash,
        thumbCachePath = null,
        originalCachePath = null,
        syncState = "INDEXED",
        remoteThumbUrl = null,
        indexedAt = 0,
    )

    /** 2026-01-02T00:00:00Z in epoch millis, used as a stable day boundary. */
    private val jan2 = 1_767_312_000_000L
    private val oneDay = 24 * 60 * 60 * 1000L

    @Test
    fun itemsOnSameDayShareOneSection() {
        val items = listOf(
            item("a", jan2 + 5 * 60 * 60 * 1000),
            item("b", jan2 + 6 * 60 * 60 * 1000),
        )
        val sections = items.groupByDay(TimeZone.UTC)
        assertEquals(1, sections.size)
        assertEquals(2, sections.single().items.size)
    }

    @Test
    fun itemsOnDifferentDaysSplitAndKeepNewestFirst() {
        // Input is newest-first, as timelinePage returns.
        val items = listOf(
            item("newer", jan2 + oneDay + 60_000),
            item("older", jan2 + 60_000),
        )
        val sections = items.groupByDay(TimeZone.UTC)
        assertEquals(2, sections.size)
        assertEquals("newer.jpg", sections.first().items.single().displayName)
        assertEquals("older.jpg", sections.last().items.single().displayName)
    }

    @Test
    fun dedupePrefersTheLocalCopyOverTheCloudCopy() {
        val items = listOf(
            item("drive:x", jan2, sourceId = "drive", contentHash = "hash-1"),
            item("local:x", jan2, sourceId = "local", contentHash = "hash-1"),
        )
        val deduped = items.dedupeByContent(preferredSourceIds = listOf("local", "drive"))
        assertEquals(1, deduped.size)
        assertEquals("local", deduped.single().sourceId)
    }

    @Test
    fun dedupeKeepsItemsWithoutAHash() {
        // Two unhashed items cannot be proven duplicates, so both must survive.
        val items = listOf(item("a", jan2), item("b", jan2))
        val deduped = items.dedupeByContent(preferredSourceIds = listOf("local"))
        assertEquals(2, deduped.size)
    }

    @Test
    fun dedupeKeepsDistinctHashes() {
        val items = listOf(
            item("a", jan2, contentHash = "hash-1"),
            item("b", jan2, contentHash = "hash-2"),
        )
        val deduped = items.dedupeByContent(preferredSourceIds = listOf("local"))
        assertEquals(2, deduped.size)
    }
}
