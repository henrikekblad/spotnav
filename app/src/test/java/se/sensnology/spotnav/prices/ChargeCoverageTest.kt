package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.ChargingPeriod
import java.time.OffsetDateTime

/**
 * The charging coverage value itself: which quarter-hours of one cell a plan charges in, and the
 * facts the table and its description are built from.
 */
class ChargeCoverageTest {
    private fun at(time: String) = OffsetDateTime.parse(time)

    private fun period(from: String, to: String) = ChargingPeriod(at(from), at(to))

    @Test
    fun aQuarterCellIsMarkedWhenAPeriodOverlapsItAndNeverOtherwise() {
        val covering = listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T10:15:00+02:00"))
        val cell = ChargeCoverage.forInterval(at("2026-09-12T10:00:00+02:00"), 15, covering)

        assertEquals(1, cell.segmentCount)
        assertEquals(1, cell.selectedCount)
        assertTrue(cell.isSelected(0))
        assertFalse(cell.isEmpty)
        assertTrue(cell.isWhole)

        // A quarter the plan does not touch is 0/1, not 1/1.
        val untouched = ChargeCoverage.forInterval(at("2026-09-12T10:15:00+02:00"), 15, covering)
        assertEquals(1, untouched.segmentCount)
        assertEquals(0, untouched.selectedCount)
        assertTrue(untouched.isEmpty)
    }

    @Test
    fun anHourlyCellKeepsItsFourQuartersIndividually() {
        val hour = at("2026-09-12T10:00:00+02:00")

        // The first two quarters of the hour.
        val firstHalf = ChargeCoverage.forInterval(
            hour, 60, listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T10:30:00+02:00"))
        )
        assertEquals(listOf(true, true, false, false), (0 until 4).map { firstHalf.isSelected(it) })
        assertEquals(4, firstHalf.segmentCount)
        assertEquals(2, firstHalf.selectedCount)
        assertFalse(firstHalf.isEmpty)
        assertFalse(firstHalf.isWhole)

        // Only the fourth quarter of the hour.
        val lastQuarter = ChargeCoverage.forInterval(
            hour, 60, listOf(period("2026-09-12T10:45:00+02:00", "2026-09-12T11:00:00+02:00"))
        )
        assertEquals(listOf(false, false, false, true), (0 until 4).map { lastQuarter.isSelected(it) })
        assertEquals(1, lastQuarter.selectedCount)

        // All four.
        val whole = ChargeCoverage.forInterval(
            hour, 60, listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T11:00:00+02:00"))
        )
        assertEquals(4, whole.selectedCount)
        assertTrue(whole.isWhole)
    }

    @Test
    fun twoNonContiguousPeriodsInsideOneHourSelectBothOfThem() {
        // One quarter at the start of the hour and one at the end, with the middle hour untouched:
        // what is selected is the first and the fourth quarter, not "two of four" by accident of
        // counting.
        val coverage = ChargeCoverage.forInterval(
            at("2026-09-12T10:00:00+02:00"), 60,
            listOf(
                period("2026-09-12T10:00:00+02:00", "2026-09-12T10:15:00+02:00"),
                period("2026-09-12T10:45:00+02:00", "2026-09-12T11:00:00+02:00")
            )
        )

        assertEquals(listOf(true, false, false, true), (0 until 4).map { coverage.isSelected(it) })
        assertEquals(2, coverage.selectedCount)
    }

    @Test
    fun aPeriodEndingOnASegmentBoundaryDoesNotMarkTheNextSegment() {
        // Half-open [start, end): a window that ends exactly at 10:30 charges the first two
        // quarters and stops there -- the third is not selected merely because the boundary touches
        // it.
        val coverage = ChargeCoverage.forInterval(
            at("2026-09-12T10:00:00+02:00"), 60,
            listOf(period("2026-09-12T09:45:00+02:00", "2026-09-12T10:30:00+02:00"))
        )

        assertEquals(listOf(true, true, false, false), (0 until 4).map { coverage.isSelected(it) })
        assertEquals(2, coverage.selectedCount)

        // And a window that starts exactly at 10:30 charges from the third quarter.
        val fromBoundary = ChargeCoverage.forInterval(
            at("2026-09-12T10:00:00+02:00"), 60,
            listOf(period("2026-09-12T10:30:00+02:00", "2026-09-12T10:45:00+02:00"))
        )
        assertEquals(listOf(false, false, true, false), (0 until 4).map { fromBoundary.isSelected(it) })
    }

    @Test
    fun differingOffsetsAreComparedByInstant() {
        // The plan is written in +02:00 and the price document's cell in UTC: 08:00Z and
        // 10:00+02:00 are the same moment, so the quarter is marked.
        assertTrue(
            ChargeCoverage.overlaps(
                listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T10:15:00+02:00")),
                at("2026-09-12T08:00:00Z").toInstant(),
                at("2026-09-12T08:15:00Z").toInstant()
            )
        )
        // An hour earlier is not the same moment, whatever the clock says.
        assertFalse(
            ChargeCoverage.overlaps(
                listOf(period("2026-09-12T10:00:00+02:00", "2026-09-12T10:15:00+02:00")),
                at("2026-09-12T07:00:00Z").toInstant(),
                at("2026-09-12T07:15:00Z").toInstant()
            )
        )
    }

    @Test
    fun anEmptyPlanCoversNothing() {
        assertTrue(ChargeCoverage.forInterval(at("2026-09-12T10:00:00+02:00"), 60, emptyList()).isEmpty)
        assertTrue(ChargeCoverage.NONE.isEmpty)
        assertEquals(1, ChargeCoverage.NONE.segmentCount)
        assertEquals(0, ChargeCoverage.NONE.selectedCount)
    }

    @Test
    fun theFactsTellOneWholeTwoOfFourAndNoneApart() {
        // The three shapes the accessibility wording has to distinguish: nothing to say, a
        // fraction, and a whole interval.
        val one = ChargeCoverage.quarter(true)
        assertEquals(1, one.selectedCount)
        assertEquals(1, one.segmentCount)
        assertTrue(one.isWhole)

        val two = ChargeCoverage.hour(true, true, false, false)
        assertEquals(2, two.selectedCount)
        assertEquals(4, two.segmentCount)
        assertFalse(two.isEmpty)
        assertFalse(two.isWhole)

        val none = ChargeCoverage.hour(false, false, false, false)
        assertEquals(0, none.selectedCount)
        assertEquals(4, none.segmentCount)
        assertTrue(none.isEmpty)
        // Out of range is never "selected": the table may ask about a segment it does not have
        // without an exception.
        assertFalse(none.isSelected(9))
        assertFalse(none.isSelected(-1))
    }

    @Test
    fun coverageIsAValueAndNotAnIdentity() {
        assertEquals(ChargeCoverage.hour(true, false, true, false), ChargeCoverage.hour(true, false, true, false))
        assertNotEquals(ChargeCoverage.hour(true, false, true, false), ChargeCoverage.hour(true, true, false, false))
        assertEquals("1010", ChargeCoverage.hour(true, false, true, false).toString())
        assertEquals("0", ChargeCoverage.NONE.toString())
    }
}
