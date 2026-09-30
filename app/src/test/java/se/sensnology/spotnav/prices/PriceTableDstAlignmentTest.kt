package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Rows on the two clock-change days, which is the whole reason a row is keyed by a wall-clock
 * position *and its occurrence* instead of by list index.
 */
class PriceTableDstAlignmentTest {
    /** The calculation inputs for these widget settings. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)
    private val area = RelayFixtures.se4 // Europe/Stockholm: +01:00 in winter, +02:00 in summer

    private fun at(time: String) = OffsetDateTime.parse(time)

    /** The quarter-hour points the repository produces for [rows] quarters of a relay day. */
    private fun day(date: String, start: String, rows: Int): List<PricePoint> {
        val parsed = RelayDayParser.parse(
            RelayFixtures.dayBody(area, date, RelayFixtures.prices(rows, base = 0.10), start),
            area.id, area.tz, area.currency, date
        )
        assertTrue("expected a valid document, got $parsed", parsed is DayParse.Ok)
        return RelayDayPoints.points((parsed as DayParse.Ok).document, area.zoneId)
    }

    /** Spring forward: the local hour 02:00-02:59 does not exist. */
    private val shortDay = day("2026-03-29", "2026-03-29T00:00:00+01:00", 92)

    /** The ordinary day after it. */
    private val normalDayAfterSpring = day("2026-03-30", "2026-03-30T00:00:00+02:00", 96)

    /** Fall back: the local hour 02:00-02:59 happens twice. */
    private val longDay = day("2026-10-25", "2026-10-25T00:00:00+02:00", 100)

    /** The ordinary day after that one. */
    private val normalDayAfterFall = day("2026-10-26", "2026-10-26T00:00:00+01:00", 96)

    private fun table(today: List<PricePoint>, tomorrow: List<PricePoint>, now: String, plan: ChargingPlan? = null) =
        PriceTableModels.create(PriceResult(today, tomorrow, 0), inputs(WidgetSettings()), at(now), plan)

    @Test
    fun theFixtureDaysHaveTheShapeTheyClaim() {
        assertEquals(92, shortDay.size)
        assertEquals(96, normalDayAfterSpring.size)
        assertEquals(100, longDay.size)
        assertEquals(96, normalDayAfterFall.size)
        // The short day skips the local hour; the long day repeats it.
        assertTrue(shortDay.none { it.start.toLocalTime().hour == 2 })
        assertEquals(2, longDay.count { it.start.toLocalTime() == LocalTime.of(2, 0) })
    }

    @Test
    fun aTwentyThreeHourDayKeepsItsMissingHourAndStillPairsTheRest() {
        val model = table(shortDay, normalDayAfterSpring, now = "2026-03-29T00:00:00+01:00")

        // 96 wall-clock positions, because the day that has them all decides the list; the four the
        // short day never had exist only in tomorrow's column.
        assertEquals(96, model.rows.size)
        val shortDayMissing = model.rows.filter { it.today == null }
        assertEquals(
            listOf(LocalTime.of(2, 0), LocalTime.of(2, 15), LocalTime.of(2, 30), LocalTime.of(2, 45)),
            shortDayMissing.map { it.position.time }
        )
        assertTrue(shortDayMissing.all { it.tomorrow != null && it.position.occurrence == 1 })
        assertTrue("only the skipped hour is one-sided", model.rows.all { it.tomorrow != null })

        // Later rows pair equal wall times again, each cell keeping its own offset and instant --
        // which is what the marker arithmetic reads.
        val three = model.rows.single { it.position.time == LocalTime.of(3, 0) }
        assertEquals(at("2026-03-29T03:00:00+02:00"), three.today!!.start)
        assertEquals(at("2026-03-30T03:00:00+02:00"), three.tomorrow!!.start)
        assertNotEquals(three.today!!.start.toInstant(), three.tomorrow!!.start.toInstant())
        assertEquals(LocalTime.of(3, 0), three.today!!.start.toLocalTime())
        assertEquals(LocalTime.of(3, 0), three.tomorrow!!.start.toLocalTime())
    }

    @Test
    fun aTwentyFiveHourDayKeepsBothRepeatedHours() {
        val model = table(longDay, normalDayAfterFall, now = "2026-10-25T00:00:00+02:00")

        // The four positions the long day has twice exist only today, so the list is the union --
        // nothing is dropped to make the columns line up.
        assertEquals(100, model.rows.size)
        val repeated = model.rows.filter { it.position.time == LocalTime.of(2, 0) }
        assertEquals(listOf(1, 2), repeated.map { it.position.occurrence })

        // Both occurrences keep their own instant and their own price: neither is overwritten by
        // the other.
        val first = repeated[0].today!!
        val second = repeated[1].today!!
        assertEquals(at("2026-10-25T02:00:00+02:00"), first.start)
        assertEquals(at("2026-10-25T02:00:00+01:00"), second.start)
        assertNotEquals(first.start.toInstant(), second.start.toInstant())
        assertNotEquals(first.price, second.price)

        // Tomorrow has this wall clock once, and that one is the first occurrence: the second is a
        // today-only row.
        assertEquals(1, model.rows.count { it.position.time == LocalTime.of(2, 0) && it.tomorrow != null })
        assertNull(repeated[1].tomorrow)

        // And the later rows realign.
        val three = model.rows.single { it.position.time == LocalTime.of(3, 0) }
        assertEquals(at("2026-10-25T03:00:00+01:00"), three.today!!.start)
        assertEquals(at("2026-10-26T03:00:00+01:00"), three.tomorrow!!.start)
        assertEquals(1, three.position.occurrence)
    }

    @Test
    fun aPlanMarksOnlyTheOccurrenceAndColumnItsInstantsBelongTo() {
        // The *second* 02:00-02:15 of the fall-back day: the one after the clock went back.
        val window = ChargingPeriod(at("2026-10-25T02:00:00+01:00"), at("2026-10-25T02:15:00+01:00"))
        val plan = ChargingPlan(
            start = window.start, end = window.end, powerKw = 11.0, energyKwh = 1.0, distanceMil = 0.0,
            cost = 0.0, unpricedSlots = 0, periods = listOf(window)
        )

        val model = table(longDay, normalDayAfterFall, now = "2026-10-25T00:00:00+02:00", plan = plan)

        val repeated = model.rows.filter { it.position.time == LocalTime.of(2, 0) }
        assertTrue("the first 02:00 is untouched", repeated[0].today!!.coverage.isEmpty)
        assertEquals(1, repeated[1].today!!.coverage.selectedCount)
        assertEquals(
            "tomorrow's 02:00 is a day away and untouched",
            0, model.rows.single { it.position.time == LocalTime.of(2, 0) && it.tomorrow != null }.tomorrow!!.coverage.selectedCount
        )
        // Exactly one cell in the whole table is charged, and it is that occurrence.
        assertEquals(1, model.rows.count { !it.today!!.coverage.isEmpty })
        assertTrue(model.rows.all { it.tomorrow == null || it.tomorrow!!.coverage.isEmpty })
    }

    @Test
    fun theCurrentRowAndTheScrollIndexFollowTheAlignedRows() {
        // Just after the third hour of the short day: the last cell today has reached is 03:15,
        // which sits at row index 13 of the aligned list (eight morning quarters, the four skipped
        // ones, then 03:00) -- not at index 9, which is where it would be in today's own list.
        val spring = table(shortDay, normalDayAfterSpring, now = "2026-03-29T03:20:00+02:00")

        assertEquals(1, spring.rows.count { it.current })
        assertEquals(13, spring.currentIndex)
        val current = spring.rows[spring.currentIndex]
        assertEquals(LocalTime.of(3, 15), current.position.time)
        assertEquals(at("2026-03-29T03:15:00+02:00"), current.today!!.start)
        assertEquals(current, spring.rows.single { it.current })

        // And on the long day the current row is the *second* occurrence: the first one is an hour
        // earlier as an instant.
        val fall = table(longDay, normalDayAfterFall, now = "2026-10-25T02:30:00+01:00")
        val fallCurrent = fall.rows[fall.currentIndex]
        assertEquals(LocalTime.of(2, 30), fallCurrent.position.time)
        assertEquals(2, fallCurrent.position.occurrence)
        assertEquals(at("2026-10-25T02:30:00+01:00"), fallCurrent.today!!.start)
    }
}
