package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** A touch on the in-app chart reaches the time drawn under the finger, whatever size the view is now. */
class ChartTouchTest {
    private val profile = ChartProfile.PLAN_CARD
    private val zone = ZoneId.of("Europe/Stockholm")

    /** From now, 13:00 today to the end of tomorrow: 35 hours. */
    private val axis = ChartTimeAxis.Range(
        Instant.parse("2026-10-14T11:00:00Z"), Instant.parse("2026-10-15T22:00:00Z"), zone
    )

    private fun metrics(width: Int) =
        ChartLayout.metrics(profile, width, ChartRequests.heightFor(width, profile), 2f, false, axis.spanMinutes) { it * 6f }

    /** The minute a touch at view ([x], [y]) selects on a view [viewW] x [viewH] showing the frame of [frame]. */
    private fun minuteAt(x: Float, y: Float, viewW: Int, viewH: Int, frameW: Int, frameH: Int, frame: ChartMetrics): Float? {
        val point = ChartTouch.toFrame(x, y, viewW, viewH, frameW, frameH) ?: return null
        return frame.minuteAt(point.x)
    }

    @Test
    fun aViewTheFrameFitsExactlyMapsOneToOne() {
        val w = 1000
        val h = ChartRequests.heightFor(w, profile)
        assertEquals(FramePoint(321f, 123f), ChartTouch.toFrame(321f, 123f, w, h, w, h))
    }

    @Test
    fun aTouchAfterTheViewWidenedReachesTheInstantDrawnUnderIt() {
        // Drawn folded at W1, then the screen unfolds to W2 before (or without) a new frame of that width:
        // the view shows the W1 frame scaled to W2, and the touch is read on that.
        val w1 = 1000
        val h1 = ChartRequests.heightFor(w1, profile)
        val w2 = 1600
        val h2 = ChartRequests.heightFor(w2, profile)
        val frame = metrics(w1)
        val tomorrowNoon = axis.minuteOf(Instant.parse("2026-10-15T10:00:00Z"))
        val scale = w2 / w1.toFloat()
        val touchX = frame.xAt(tomorrowNoon) * scale
        val touchY = (frame.top + frame.bottom) / 2f * scale

        assertEquals(tomorrowNoon, minuteAt(touchX, touchY, w2, h2, w1, h1, frame)!!, 0.5f)
        // Read with the old width, the same touch lands hours later: the fold bug.
        assertNotEquals(tomorrowNoon, frame.minuteAt(touchX) ?: -1f, 60f)
    }

    @Test
    fun aFrameNarrowerThanTheViewIsCentredAndTheTouchFollowsIt() {
        // An unfolded screen wider than the bitmap cap: the frame is shown centred at its own size.
        val viewW = 2150
        val viewH = ChartRequests.heightFor(viewW, profile)
        val frameW = 1400
        val frame = metrics(frameW)
        val offset = (viewW - frameW) / 2f
        val minute = axis.minuteOf(Instant.parse("2026-10-14T18:00:00Z"))

        assertEquals(minute, minuteAt(frame.xAt(minute) + offset, viewH / 2f, viewW, viewH, frameW, viewH, frame)!!, 0.5f)
    }

    @Test
    fun nothingIsMappedWithoutASize() {
        assertNull(ChartTouch.toFrame(1f, 1f, 0, 100, 100, 100))
        assertNull(ChartTouch.toFrame(1f, 1f, 100, 100, 0, 100))
    }
}
