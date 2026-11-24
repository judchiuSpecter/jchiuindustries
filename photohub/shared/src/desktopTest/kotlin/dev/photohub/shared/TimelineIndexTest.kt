package dev.photohub.shared

import dev.photohub.shared.data.TimelineIndex
import dev.photohub.shared.data.TimelineSection
import dev.photohub.shared.db.MediaItem
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineIndexTest {

    private fun item(id: String) = MediaItem(
        id = id,
        sourceId = "local",
        remoteId = id,
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        isVideo = 0,
        sizeBytes = 1,
        width = null,
        height = null,
        dateTaken = 0L,
        contentHash = null,
        thumbCachePath = null,
        originalCachePath = null,
        syncState = "INDEXED",
        remoteThumbUrl = null,
        indexedAt = 0,
    )

    private fun sections(vararg counts: Int): List<TimelineSection> =
        counts.mapIndexed { i, count ->
            TimelineSection(
                date = LocalDate(2026, 11, 20 - i),
                items = (0 until count).map { item("d$i-$it") },
            )
        }

    @Test
    fun gridIndexAccountsForOneHeaderPerDay() {
        // Day 0: header at 0, photos at 1..3. Day 1: header at 4, photos 5..6.
        val index = TimelineIndex(sections(3, 2))

        assertEquals(0, index.entries[0].gridIndex)
        assertEquals(4, index.entries[1].gridIndex)
    }

    @Test
    fun positionsAreWeightedByPhotoCountNotBySectionCount() {
        // A 90-photo day and a 10-photo day must not get equal track space, or
        // the thumb would not track the grid while dragging.
        val index = TimelineIndex(sections(90, 10))

        assertEquals(0f, index.entries[0].startFraction)
        assertEquals(0.9f, index.entries[0].endFraction)
        assertEquals(0.9f, index.entries[1].startFraction)
        assertEquals(1f, index.entries[1].endFraction)
    }

    @Test
    fun entryAtMapsFractionBackToTheRightDay() {
        val index = TimelineIndex(sections(90, 10))

        assertEquals(index.entries[0].date, index.entryAt(0f)?.date)
        assertEquals(index.entries[0].date, index.entryAt(0.5f)?.date)
        assertEquals(index.entries[1].date, index.entryAt(0.95f)?.date)
        // The very end must resolve, not fall off: `fraction < endFraction`
        // is false for every entry at exactly 1f.
        assertEquals(index.entries[1].date, index.entryAt(1f)?.date)
    }

    @Test
    fun outOfRangeFractionsAreClampedRatherThanCrashing() {
        val index = TimelineIndex(sections(5, 5))

        // A drag can overshoot the track bounds by a few pixels.
        assertEquals(index.entries[0].date, index.entryAt(-0.5f)?.date)
        assertEquals(index.entries[1].date, index.entryAt(1.7f)?.date)
    }

    @Test
    fun gridIndexResolvesToTheDayThatOwnsIt() {
        val index = TimelineIndex(sections(3, 2))

        // Grid 0..3 belong to day 0 (header + 3 photos).
        assertEquals(index.entries[0].date, index.entryForGridIndex(0)?.date)
        assertEquals(index.entries[0].date, index.entryForGridIndex(3)?.date)
        // Grid 4 is day 1's header.
        assertEquals(index.entries[1].date, index.entryForGridIndex(4)?.date)
        assertEquals(index.entries[1].date, index.entryForGridIndex(6)?.date)
    }

    @Test
    fun monthMarkersCollapseDaysWithinTheSameMonth() {
        val sections = listOf(
            TimelineSection(LocalDate(2026, 11, 20), listOf(item("a"))),
            TimelineSection(LocalDate(2026, 11, 2), listOf(item("b"))),
            TimelineSection(LocalDate(2026, 10, 30), listOf(item("c"))),
            TimelineSection(LocalDate(2025, 10, 30), listOf(item("d"))),
        )

        val markers = TimelineIndex(sections).monthMarkers()

        // Two days in Nov 2026 collapse; Oct 2026 and Oct 2025 stay distinct
        // so the same month in different years is not merged.
        assertEquals(3, markers.size)
        assertEquals(LocalDate(2026, 11, 20), markers[0].date)
        assertEquals(LocalDate(2026, 10, 30), markers[1].date)
        assertEquals(LocalDate(2025, 10, 30), markers[2].date)
    }

    @Test
    fun emptyTimelineProducesNoEntriesInsteadOfDividingByZero() {
        val index = TimelineIndex(emptyList())

        assertTrue(index.isEmpty)
        assertNull(index.entryAt(0.5f))
        assertEquals(0f, index.fractionForGridIndex(0))
    }

    @Test
    fun daysWithNoItemsDoNotBreakTheWeighting() {
        // Defensive: a section with zero items should not create a zero-width
        // entry that swallows a fraction lookup.
        val index = TimelineIndex(sections(0, 4))

        assertEquals(index.entries[1].date, index.entryAt(0.5f)?.date)
    }
}
