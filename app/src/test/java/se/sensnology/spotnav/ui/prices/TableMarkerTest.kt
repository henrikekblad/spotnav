package se.sensnology.spotnav.ui.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.prices.ChargeCoverage
import java.time.OffsetDateTime

/** Where the price table's charging mark is drawn, and in which order its parts are filled. */
class TableMarkerTest {
    private fun at(time: String) = OffsetDateTime.parse(time)

    private fun period(from: String, to: String) = ChargingPeriod(at(from), at(to))

    @Test
    fun todaysMarkBelongsToTheLeftEdgeAndTomorrowsToTheRight() {
        assertFalse("today's rail is its cell's left edge", TableColumn.TODAY.markOnRightEdge)
        assertTrue("tomorrow's rail is its cell's right edge", TableColumn.TOMORROW.markOnRightEdge)
    }

    @Test
    fun aQuarterHourCellIsOneSegmentWhichIsTheWholeOfItsEdge() {
        // 15-minute presentation:
        val selected = ChargeCoverage.forInterval(
            at("2026-09-12T10:00:00+02:00"), 15, listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T10:15:00+02:00"))
        )
        assertEquals(listOf(true), TableMarker.segmentsTopDown(selected))

        val untouched = ChargeCoverage.forInterval(at("2026-09-12T10:15:00+02:00"), 15, emptyList())
        assertEquals(listOf(false), TableMarker.segmentsTopDown(untouched))
        assertTrue(untouched.isEmpty)
    }

    @Test
    fun anHourlyCellsQuartersRunDownwardsInTimeOrder() {
        val hour = at("2026-09-12T10:00:00+02:00")

        // The first and second quarters of the hour:
        val firstHalf = ChargeCoverage.forInterval(
            hour, 60, listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T10:30:00+02:00"))
        )
        assertEquals(listOf(true, true, false, false), TableMarker.segmentsTopDown(firstHalf))
        assertEquals(2, TableMarker.segmentsTopDown(firstHalf).count { it })
        assertEquals(firstHalf.selectedCount, TableMarker.segmentsTopDown(firstHalf).count { it })

        // The hour's middle two quarters are the middle two segments.
        val middle = ChargeCoverage.forInterval(
            hour, 60, listOf(period("2026-09-12T10:15:00+02:00", "2026-09-12T10:45:00+02:00"))
        )
        assertEquals(listOf(false, true, true, false), TableMarker.segmentsTopDown(middle))

        val last = ChargeCoverage.forInterval(
            hour, 60, listOf(period("2026-09-12T10:45:00+02:00", "2026-09-12T11:00:00+02:00"))
        )
        assertEquals(listOf(false, false, false, true), TableMarker.segmentsTopDown(last))
        // The quarters *are* in time order, which is what the rail's top-down reading claims:
        assertTrue(last.isSelected(3))
        assertFalse(last.isSelected(0))
    }

    @Test
    fun aWholeHourIsFourFilledSegments() {
        val whole = ChargeCoverage.forInterval(
            at("2026-09-12T10:00:00+02:00"), 60,
            listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T11:00:00+02:00"))
        )

        assertEquals(listOf(true, true, true, true), TableMarker.segmentsTopDown(whole))
        assertTrue(whole.isWhole)
        assertEquals(whole.segmentCount, TableMarker.segmentsTopDown(whole).size)
    }
}
