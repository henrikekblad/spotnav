package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime
import java.time.OffsetDateTime

/** Tapping the in-app graph: which interval a tap picks, and what the readout says. */
class ChartSelectionTest {

    /** The calculation inputs for these widget settings. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)
    private val se4 = RelayFixtures.se4
    private val no1 = RelayFixtures.no1
    private val fi = RelayFixtures.fi

    @Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(se4, no1, fi))
    }

    /** The metrics a 840x440 plan-card graph is drawn with: the real numbers. */
    private val metrics = ChartLayout.metrics(ChartProfile.PLAN_CARD, 840, 440, 2f, hasChargingPlan = false) { it * 6f }

    private val emptyMetrics = ChartMetrics(1f, 0f, 0f, 0f, 0f, 0f, 0f, 10f, 10f)

    private fun at(time: String) = OffsetDateTime.parse(time)

    /** One day of quarter-hour prices from midnight, at [from]. */
    private fun day(from: String, prices: List<Double>) = prices.mapIndexed { index, price ->
        PricePoint(at(from).plusMinutes(index * 15L), price)
    }

    /** The x the renderer draws a given minute of the day at: its own mapping. */
    private fun xOf(minuteOfDay: Float) = metrics.xAt(minuteOfDay)

    @Test
    fun onlyATapInsideThePlotSelectsAnything() {
        val result = PriceResult(day("2026-09-20T00:00:00+02:00", listOf(1.0, 2.0, 3.0, 4.0)), emptyList(), 0)
        val settings = WidgetSettings()

        assertNull("left of the plot", ChartSelection.nearest(0f, metrics, inputs(settings), result))
        assertNull("right of the plot", ChartSelection.nearest(metrics.right + 1f, metrics, inputs(settings), result))
        assertNull("an empty plot has nothing to select", ChartSelection.nearest(0f, emptyMetrics, inputs(settings), result))

        // The edges themselves are inside: the first mark and the last one.
        assertEquals(LocalTime.of(0, 0), ChartSelection.nearest(metrics.left, metrics, inputs(settings), result)!!.time)
        assertEquals(LocalTime.of(0, 45), ChartSelection.nearest(metrics.right, metrics, inputs(settings), result)!!.time)
    }

    @Test
    fun theNearestQuarterWinsAndATieGoesToTheEarlierOne() {
        val result = PriceResult(day("2026-09-20T00:00:00+02:00", listOf(1.0, 2.0, 3.0, 4.0)), emptyList(), 0)
        val settings = WidgetSettings()

        assertEquals(LocalTime.of(0, 15), ChartSelection.nearest(xOf(15f), metrics, inputs(settings), result)!!.time)
        assertEquals("7.4 minutes is nearer 00:15", LocalTime.of(0, 15), ChartSelection.nearest(xOf(22.4f), metrics, inputs(settings), result)!!.time)
        assertEquals("a tie goes to the earlier mark", LocalTime.of(0, 15), ChartSelection.nearest(xOf(22.5f), metrics, inputs(settings), result)!!.time)
        assertEquals("7.6 minutes is nearer 00:30", LocalTime.of(0, 30), ChartSelection.nearest(xOf(22.6f), metrics, inputs(settings), result)!!.time)
    }

    @Test
    fun aSelectionCarriesTheMinuteItsOwnMarkIsDrawnAt() {
        val quarter = PriceResult(day("2026-09-20T00:00:00+02:00", listOf(1.0, 2.0)), emptyList(), 0)
        val readout = ChartSelection.nearest(xOf(15f), metrics, inputs(WidgetSettings()), quarter)!!
        assertEquals(LocalTime.of(0, 15), readout.time)
        assertEquals(15f, readout.markMinute, 0f)

        val points = (0 until 8).map { index ->
            PricePoint(at("2026-09-20T00:00:00+02:00").plusMinutes(index * 15L), 1.0 + index)
        }
        val hourly = ChartSelection.nearest(xOf(30f), metrics, inputs(WidgetSettings(intervalMinutes = 60)), PriceResult(points, emptyList(), 0))!!
        assertEquals(LocalTime.of(0, 0), hourly.time)
        assertEquals(30f, hourly.markMinute, 0f)

        val cycled = ChartSelection.readoutAt(LocalTime.of(0, 0), inputs(WidgetSettings(intervalMinutes = 60)), PriceResult(points, emptyList(), 0))!!
        assertEquals(hourly, cycled)
        assertEquals(0f, cycled.markMinute - hourly.markMinute, 0f)
    }

    @Test
    fun inHourlyModeTheNearestIntervalIsMeasuredAtItsOwnStroke() {
        val points = (0 until 8).map { index ->
            PricePoint(at("2026-09-20T00:00:00+02:00").plusMinutes(index * 15L), 1.0 + index)
        }
        val result = PriceResult(points, emptyList(), 0)
        val settings = WidgetSettings(intervalMinutes = 60)

        assertEquals(LocalTime.of(0, 0), ChartSelection.nearest(xOf(30f), metrics, inputs(settings), result)!!.time)
        assertEquals(LocalTime.of(1, 0), ChartSelection.nearest(xOf(90f), metrics, inputs(settings), result)!!.time)
        assertEquals(LocalTime.of(0, 0), ChartSelection.nearest(xOf(31f), metrics, inputs(settings), result)!!.time)
    }

    @Test
    fun theTieRuleIsTheEarlierMarkAndIsStatedInMinutes() {
        assertEquals(30f, ChartSelection.nearestMark(60f, listOf(30f, 90f))!!, 0f)
        assertEquals(90f, ChartSelection.nearestMark(60.1f, listOf(30f, 90f))!!, 0f)
        assertEquals(30f, ChartSelection.nearestMark(59.9f, listOf(30f, 90f))!!, 0f)
        assertEquals(30f, ChartSelection.nearestMark(60f, listOf(90f, 30f))!!, 0f)
        assertEquals(45f, ChartSelection.nearestMark(45f, listOf(30f, 45f, 90f))!!, 0f)
    }

    @Test
    fun theReadoutShowsWhicheverDayHasThatPosition() {
        val today = day("2026-09-20T00:00:00+02:00", listOf(1.0, 2.0))
        val tomorrow = day("2026-09-21T00:00:00+02:00", listOf(3.0, 4.0))
        val settings = WidgetSettings()

        val both = ChartSelection.nearest(xOf(15f), metrics, inputs(settings), PriceResult(today, tomorrow, 0))!!
        assertEquals(LocalTime.of(0, 15), both.time)
        assertEquals(inputs(settings).apply(2.0), both.today.single().price, 1e-9)
        assertEquals(inputs(settings).apply(4.0), both.tomorrow.single().price, 1e-9)

        val todayOnly = ChartSelection.nearest(xOf(15f), metrics, inputs(settings), PriceResult(today, emptyList(), 0))!!
        assertEquals(inputs(settings).apply(2.0), todayOnly.today.single().price, 1e-9)
        assertTrue(todayOnly.tomorrow.isEmpty())

        val tomorrowOnly = ChartSelection.nearest(xOf(15f), metrics, inputs(settings), PriceResult(emptyList(), tomorrow, 0))!!
        assertTrue(tomorrowOnly.today.isEmpty())
        assertEquals(inputs(settings).apply(4.0), tomorrowOnly.tomorrow.single().price, 1e-9)
    }

    @Test
    fun matchingIsByWallClockSoDifferentOffsetsCannotSwapTheTwoDays() {
        val today = day("2026-09-20T00:00:00+02:00", listOf(1.0, 2.0))
        val tomorrow = day("2026-09-21T00:00:00+01:00", listOf(8.0, 9.0))
        val settings = WidgetSettings()

        val readout = ChartSelection.nearest(xOf(0f), metrics, inputs(settings), PriceResult(today, tomorrow, 0))!!

        assertEquals(LocalTime.of(0, 0), readout.time)
        assertEquals(inputs(settings).apply(1.0), readout.today.single().price, 1e-9)
        assertEquals(inputs(settings).apply(8.0), readout.tomorrow.single().price, 1e-9)
        assertTrue(readout.today != readout.tomorrow)
        assertEquals(25 * 3600L, tomorrow[0].start.toInstant().epochSecond - today[0].start.toInstant().epochSecond)
    }

    @Test
    fun aRepeatedLocalHourKeepsBothOccurrencesInInstantOrder() {
        val today = day("2026-10-25T00:00:00+02:00", (0 until 12).map { 1.0 + it }) + listOf(
            PricePoint(at("2026-10-25T02:15:00+01:00"), 7.0),
            PricePoint(at("2026-10-25T02:30:00+01:00"), 8.0)
        )
        val tomorrow = day("2026-10-26T00:00:00+01:00", (0 until 12).map { 5.0 + it })
        val settings = WidgetSettings()

        val readout = ChartSelection.nearest(xOf(135f), metrics, inputs(settings), PriceResult(today, tomorrow, 0))!!

        assertEquals(LocalTime.of(2, 15), readout.time)
        assertEquals("both of today's values survive", 2, readout.today.size)
        assertEquals(inputs(settings).apply(10.0), readout.today[0].price, 1e-9)
        assertEquals(inputs(settings).apply(7.0), readout.today[1].price, 1e-9)
        // Instant order, and the offsets that tell the two lines apart.
        assertEquals(java.time.ZoneOffset.ofHours(2), readout.today[0].offset)
        assertEquals(java.time.ZoneOffset.ofHours(1), readout.today[1].offset)
        assertTrue("the earlier instant comes first", today[9].start.toInstant() < today[12].start.toInstant())
        assertTrue(readout.todayRepeats)
        assertFalse(readout.tomorrowRepeats)
        assertEquals(1, readout.tomorrow.size)
        assertEquals(inputs(settings).apply(14.0), readout.tomorrow.single().price, 1e-9)
    }

    @Test
    fun anOrdinaryPositionHasOneValuePerDayAndNeedsNoOffset() {
        val today = day("2026-09-20T00:00:00+02:00", listOf(1.0, 2.0))
        val tomorrow = day("2026-09-21T00:00:00+02:00", listOf(3.0, 4.0))

        val readout = ChartSelection.nearest(xOf(15f), metrics, inputs(WidgetSettings()), PriceResult(today, tomorrow, 0))!!

        assertEquals(1, readout.today.size)
        assertEquals(1, readout.tomorrow.size)
        assertFalse("nothing to tell apart, so no offsets in words", readout.todayRepeats)
        assertFalse(readout.tomorrowRepeats)
        assertFalse(readout.isEmpty)
    }

    @Test
    fun pricesAreTheGraphsOwnTransformedValuesInTheMarketsUnit() {
        val settings = WidgetSettings(area = no1.id, vat = true, tax = true)
        val result = PriceResult(day("2026-09-20T00:00:00+02:00", listOf(0.5)), emptyList(), 0)

        val readout = ChartSelection.nearest(xOf(0f), metrics, inputs(settings), result)!!

        assertEquals(inputs(settings).apply(0.5), readout.today.single().price, 1e-9)
        assertTrue("the value is transformed, not raw", readout.today.single().price != 0.5)
        assertEquals("øre/kWh", PriceMarkets.find(no1.id)!!.appliedPriceUnit)
        assertEquals("öre/kWh", PriceMarkets.find(se4.id)!!.appliedPriceUnit)
        assertEquals("cent/kWh", PriceMarkets.find(fi.id)!!.appliedPriceUnit)
    }

    @Test
    fun aNegativePriceIsSelectedAndReadAsItIs() {
        val settings = WidgetSettings()
        val result = PriceResult(day("2026-09-20T00:00:00+02:00", listOf(-0.25, 0.5)), emptyList(), 0)

        val readout = ChartSelection.nearest(xOf(0f), metrics, inputs(settings), result)!!

        assertEquals(inputs(settings).apply(-0.25), readout.today.single().price, 1e-9)
        assertTrue(readout.today.single().price < 0.0)
    }

    @Test
    fun aSelectionOnlySurvivesWhileItsOwnFrameIsStillDrawn() {
        val settings = WidgetSettings(area = se4.id)
        val result = PriceResult(day("2026-09-20T00:00:00+02:00", listOf(1.0)), emptyList(), 0)
        val readout = ChartReadout(LocalTime.of(0, 0), 0f, listOf(ChartOccurrence(1.0, java.time.ZoneOffset.ofHours(2))), emptyList())
        val request = ChartRequests.of(inputs(settings), result, 840, ChartProfile.PLAN_CARD)!!

        assertTrue(ChartSelection.stillValid(readout, request, request))
        // An equal request is the same frame: nothing on screen has moved.
        assertTrue(ChartSelection.stillValid(readout, request, ChartRequests.of(inputs(settings), result, 840, ChartProfile.PLAN_CARD)))

        assertFalse("no selection", ChartSelection.stillValid(null, request, request))
        assertFalse("nothing was selected on", ChartSelection.stillValid(readout, null, request))
        assertFalse(
            "a new area",
            ChartSelection.stillValid(readout, request, ChartRequests.of(inputs(settings.copy(area = no1.id)), result, 840, ChartProfile.PLAN_CARD))
        )
        assertFalse(
            "a new resolution",
            ChartSelection.stillValid(readout, request, ChartRequests.of(inputs(settings.copy(intervalMinutes = 60)), result, 840, ChartProfile.PLAN_CARD))
        )
        assertFalse(
            "new prices",
            ChartSelection.stillValid(
                readout, request,
                ChartRequests.of(inputs(settings), PriceResult(day("2026-09-20T00:00:00+02:00", listOf(2.0)), emptyList(), 0), 840, ChartProfile.PLAN_CARD)
            )
        )
        assertFalse("a resized card", ChartSelection.stillValid(readout, request, ChartRequests.of(inputs(settings), result, 1200, ChartProfile.PLAN_CARD)))
        assertFalse("a different drawing profile", ChartSelection.stillValid(readout, request, ChartRequests.of(inputs(settings), result, 840, ChartProfile.WIDGET)))
    }

    @Test
    fun theDisplayedPositionsAreWallClocksInOrderWithARepeatedOneOnlyOnce() {
        val today = day("2026-10-25T00:00:00+02:00", (0 until 12).map { 1.0 + it }) + listOf(
            PricePoint(at("2026-10-25T02:15:00+01:00"), 7.0)
        )

        val positions = ChartSelection.positions(inputs(WidgetSettings()), PriceResult(today, emptyList(), 0))

        assertEquals(12, positions.size)
        assertEquals(LocalTime.of(0, 0), positions.first())
        assertEquals(LocalTime.of(2, 45), positions.last())
        assertEquals("02:15 appears once in the cycle", 1, positions.count { it == LocalTime.of(2, 15) })
        // The readout at that one position still carries both of its values.
        val repeated = ChartSelection.readoutAt(LocalTime.of(2, 15), inputs(WidgetSettings()), PriceResult(today, emptyList(), 0))!!
        assertEquals(2, repeated.today.size)
    }

    @Test
    fun activationStepsThroughThePositionsInOrderAndWraps() {
        val positions = listOf(LocalTime.of(0, 0), LocalTime.of(0, 15), LocalTime.of(0, 30))

        // Nothing selected yet: activation starts at the first position.
        assertEquals(LocalTime.of(0, 0), ChartSelection.next(null, positions))
        assertEquals(LocalTime.of(0, 15), ChartSelection.next(LocalTime.of(0, 0), positions))
        assertEquals(LocalTime.of(0, 30), ChartSelection.next(LocalTime.of(0, 15), positions))
        // Past the last one it wraps to the first, so activation never dead-ends.
        assertEquals(LocalTime.of(0, 0), ChartSelection.next(LocalTime.of(0, 30), positions))
        // One position (or a stale one) cannot get stuck either.
        assertEquals(LocalTime.of(0, 15), ChartSelection.next(LocalTime.of(0, 15), listOf(LocalTime.of(0, 15))))
        assertEquals(LocalTime.of(0, 0), ChartSelection.next(LocalTime.of(9, 45), positions))
        assertEquals(null, ChartSelection.next(LocalTime.of(9, 45), emptyList()))
    }

    @Test
    fun theCyclingPathAndACoordinateTapAgreeOnTheSamePosition() {
        val today = day("2026-09-20T00:00:00+02:00", listOf(1.0, 2.0, 3.0))
        val tomorrow = day("2026-09-21T00:00:00+02:00", listOf(4.0, 5.0, 6.0))
        val result = PriceResult(today, tomorrow, 0)
        val settings = WidgetSettings()

        val positions = ChartSelection.positions(inputs(settings), result)
        assertEquals(listOf(LocalTime.of(0, 0), LocalTime.of(0, 15), LocalTime.of(0, 30)), positions)
        for (position in positions) {
            val cycled = ChartSelection.readoutAt(position, inputs(settings), result)!!
            val tapped = ChartSelection.nearest(xOf(position.toSecondOfDay() / 60f), metrics, inputs(settings), result)!!
            assertEquals("at $position", cycled, tapped)
        }
    }

    @Test
    fun aSelectionIsValidatedAgainstEverythingIncludingTheFooter() {
        val result = PriceResult(day("2026-09-12T00:00:00+02:00", List(96) { 1.0 }), emptyList(), 0)
        val market = ChartMarket.of(inputs(WidgetSettings(area = "SE4")))
        val date = java.time.LocalDate.of(2026, 9, 12)
        val stockholm = java.time.ZoneId.of("Europe/Stockholm")
        val band = ChartBand(date, 60f, 180f)
        fun request(footer: ChartFooterPlan?) =
            ChartRequests.of(market, listOf(band), result, 1000, ChartProfile.PLAN_CARD, footer)
        val start = date.atTime(1, 0).atZone(stockholm).toOffsetDateTime()
        val readout = ChartSelection.readoutAt(LocalTime.of(1, 0), market, result)!!

        assertTrue("the same frame keeps its selection", ChartSelection.stillValid(readout, request(null), request(null)))
        val described = ChartFooterPlan(start, start.plusHours(3), 2, false, 20.0, 10.0)
        val other = ChartFooterPlan(start, start.plusHours(4), 3, true, 32.5, 14.0)
        assertEquals(request(described)!!.bands, request(other)!!.bands)
        assertFalse(
            "a footer that changed is a different frame",
            ChartSelection.stillValid(readout, request(described), request(other))
        )
        assertFalse("and so is no footer at all", ChartSelection.stillValid(readout, request(described), request(null)))
    }
}
