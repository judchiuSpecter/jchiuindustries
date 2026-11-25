package dev.photohub.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.photohub.shared.data.TimelineIndex
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char

/**
 * The Google-Photos-style date rail down the right edge.
 *
 * Dragging the thumb jumps the grid by date rather than by scroll distance,
 * which is what makes a library of thousands navigable: reaching last January
 * is one drag instead of a long flick. Month ticks give the rail a sense of
 * scale, and a label bubble shows the date under the finger while dragging.
 *
 * The rail only appears when it earns its place — below [MIN_ITEMS_FOR_RAIL]
 * photos the grid is already short enough to flick through, and a permanent
 * rail would just cover thumbnails.
 */
@Composable
fun DateScrubber(
    index: TimelineIndex,
    /** Where the grid currently sits, 0f..1f, used when the user is not dragging. */
    scrollFraction: Float,
    onSeek: (TimelineIndex.Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (index.isEmpty) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current

    // While dragging the thumb follows the finger; otherwise it reflects the
    // grid. Without this the thumb would fight the finger, since seeking the
    // grid also updates scrollFraction.
    val fraction = if (dragging) dragFraction else scrollFraction

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
            .onSizeChanged { trackHeightPx = if (it.height > 0) it.height.toFloat() else 0f }
            .pointerInput(index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true

                    fun seek(yPx: Float) {
                        if (trackHeightPx <= 0f) return
                        val f = (yPx / trackHeightPx).coerceIn(0f, 1f)
                        dragFraction = f
                        index.entryAt(f)?.let(onSeek)
                    }

                    seek(down.position.y)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (!pointer.pressed) break
                        // Absolute position, not accumulated delta, so the thumb
                        // stays under the finger even if a frame is dropped.
                        seek(pointer.position.y)
                        pointer.consume()

                    }

                    dragging = false
                }
            },
    ) {
        MonthTicks(
            index = index,
            trackHeightPx = trackHeightPx,
            highlighted = dragging
        )
        YearLabels(
            index = index,
            trackHeightPx = trackHeightPx,
        )

        // Thumb, centred on the position and clamped so it cannot hang off
        // either end of the track. Guard against trackHeightPx < THUMB_HEIGHT
        // which would produce a negative max and crash.
        val maxThumbOffset = with(density) {
            val maxPx = trackHeightPx - THUMB_HEIGHT.toPx()
            if (maxPx < 0f) 0.dp else maxPx.toDp()
        }
        val thumbOffset = with(density) {
            (fraction * trackHeightPx).toDp() - THUMB_HEIGHT / 2
        }.coerceIn(0.dp, maxThumbOffset)

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbOffset)
                .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50),
                ),
        )

        // Date bubble, only while dragging: a permanent label would sit on top
        // of the photos it is meant to help find. Just day + month — the year
        // is already shown by the fixed labels on the track, so repeating it
        // here would only make the bubble wider.
        //
        // The rail itself is only RAIL_WIDTH (24dp) wide, so without
        // wrapContentWidth(unbounded = true) the bubble would be measured
        // against that width before its offset shifts it clear of the rail,
        // clipping the text instead of just rendering beside the rail.
        if (dragging) {
            index.entryAt(fraction)?.let { entry ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .wrapContentWidth(unbounded = true)
                        .offset(
                            x = -RAIL_WIDTH - 4.dp,
                            y = thumbOffset,
                        ),
                ) {
                    Text(
                        text = entry.date.formatBubble(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Month tick marks along the rail.
 *
 * Faint when idle so the rail reads as a hint rather than a control, and
 * brighter while dragging when the user is aiming at them. The track height is
 * passed in and measured once by the parent — measuring per tick would mean a
 * full-height layout node for every month on screen.
 */
@Composable
private fun BoxScope.MonthTicks(
    index: TimelineIndex,
    trackHeightPx: Float,
    highlighted: Boolean,
) {
    if (trackHeightPx <= 0f) return

    val markers = remember(index) { index.monthMarkers() }
    // A tick per month is unreadable past a few years of history, and drawing
    // hundreds of them costs layout on every frame of a drag.
    if (markers.isEmpty() || markers.size > MAX_TICKS) return

    val density = LocalDensity.current
    val alpha = if (highlighted) 0.9f else 0.35f

    for (marker in markers) {
        val y: Dp = with(density) { (marker.startFraction * trackHeightPx).toDp() }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = y)
                .alpha(alpha)
                .size(width = TICK_WIDTH, height = TICK_HEIGHT)
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/**
 * Fixed year labels drawn directly on the track, always visible (unlike the
 * drag bubble, which only appears while dragging). The bubble itself only
 * shows day + month, so the year has to live somewhere that is not
 * accompanying every drag — here, once per year of history, is enough: the
 * user can always see roughly which year they are near without the bubble
 * repeating it on every frame.
 */
@Composable
private fun BoxScope.YearLabels(
    index: TimelineIndex,
    trackHeightPx: Float,
) {
    if (trackHeightPx <= 0f) return

    val markers = remember(index) { index.yearMarkers() }
    if (markers.isEmpty()) return

    val density = LocalDensity.current

    for (marker in markers) {
        val y: Dp = with(density) { (marker.startFraction * trackHeightPx).toDp() }
        Text(
            text = marker.date.year.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .wrapContentWidth(unbounded = true)
                .offset(x = -RAIL_WIDTH, y = y),
        )
    }
}

/** "2 Nov" — day + a 3-letter month; the year is already on the track's fixed labels. */
private fun LocalDate.formatBubble(): String {
    val fmt = LocalDate.Format {
        day(Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
    }
    return fmt.format(this)
}

/** Below this many photos, flicking is easier than aiming at a rail. */
const val MIN_ITEMS_FOR_RAIL = 60

private const val MAX_TICKS = 48
private val RAIL_WIDTH = 24.dp
private val THUMB_WIDTH = 10.dp
private val THUMB_HEIGHT = 36.dp
private val TICK_WIDTH = 8.dp
private val TICK_HEIGHT = 2.dp