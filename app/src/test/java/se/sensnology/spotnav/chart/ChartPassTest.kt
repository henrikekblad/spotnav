package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** The production chart pass: one plan, and the chart of that plan. */
class ChartPassTest {
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val date: LocalDate = LocalDate.of(2026, 9, 12)
    private val settings = WidgetSettings(area = "SE4")

    private val inputs: PlanningInputs = LocalPlanningInputs.of(settings)

    /** A market day of quarter hours on a fixed past date. */
    private fun prices(): PriceResult {
        val midnight = date.atStartOfDay(zone).toOffsetDateTime()
        return PriceResult(
            (0 until 96).map { index -> PricePoint(midnight.plusMinutes(index * 15L), 0.4 + (index % 4) * 0.3) },
            emptyList(),
            0L
        )
    }

    /** Prices that always contain a plan: four days of quarter hours from the market's own clock. */
    private fun pricesAroundNow(): PriceResult {
        val from = OffsetDateTime.now(zone).withMinute(0).withSecond(0).withNano(0)
        return PriceResult(
            (0 until 96 * 4).map { index -> PricePoint(from.plusMinutes(index * 15L), 0.4 + (index % 8) * 0.25) },
            emptyList(),
            0L
        )
    }

    private fun period(fromHour: Int, toHour: Int): ChargingPeriod = ChargingPeriod(
        date.atTime(fromHour, 0).atZone(zone).toOffsetDateTime(),
        date.atTime(toHour, 0).atZone(zone).toOffsetDateTime()
    )

    /** A plan whose every fact is distinctive, so a chart built from anything else is visible. */
    private fun plan(
        periods: List<ChargingPeriod> = listOf(period(1, 3)),
        energyKwh: Double = 12.5,
        distanceMil: Double = 7.5,
        unpricedSlots: Int = 3
    ): ChargingPlan = ChargingPlan(
        start = periods.first().start,
        end = periods.last().end,
        powerKw = 6.9,
        energyKwh = energyKwh,
        distanceMil = distanceMil,
        cost = 42.5,
        unpricedSlots = unpricedSlots,
        periods = periods
    )


    @Test
    fun aPassSchedulesOnePlanAndTheChartIsThatExactPlan() {
        val first = plan(periods = listOf(period(1, 3)), energyKwh = 12.5)
        val second = plan(periods = listOf(period(20, 22)), energyKwh = 31.0)
        var calls = 0
        val counting: (PriceResult, PlanningInputs) -> ChargingPlan? = { _, _ ->
            calls++
            if (calls == 1) first else second
        }

        val scheduled = ChartPass.plan(inputs, prices(), calculates = true, nothingToCharge = false, calculate = counting)

        assertEquals("one pass, one calculation", 1, calls)
        assertSame("and the pass keeps the plan it was answered with", first, scheduled)

        // The chart of that plan, adapted without any planner of its own.
        val prices = prices()
        val chart = ChartPass.chart(inputs, prices, scheduled)!!
        assertEquals("the chart is that plan's periods, clipped and unioned", ChartOverlay.planned(first.periods).bands(prices, zone), chart.bands)
        assertNotEquals("and not what a second call would have produced", ChartOverlay.planned(second.periods).bands(prices, zone), chart.bands)
        assertEquals(ChartMarket.of(inputs), chart.market)
        // The footer's facts are the same plan's, fact for fact.
        assertEquals(
            ChartFooterPlan(
                start = first.start,
                end = first.end,
                periodCount = first.periods.size,
                unpriced = true,
                energyKwh = 12.5,
                distanceMil = 7.5
            ),
            chart.footer
        )
        // Still one: drawing the plan is not calculating it.
        assertEquals("the chart added no calculation", 1, calls)
    }

    @Test
    fun theChartDescribesThePlanRatherThanTheUnionOfItsBands() {
        val twoTouch = listOf(period(1, 2), period(2, 3))
        val continuous = listOf(period(1, 3))
        val prices = prices()

        val twoChart = ChartPass.chart(inputs, prices, plan(periods = twoTouch))!!
        val oneChart = ChartPass.chart(inputs, prices, plan(periods = continuous))!!

        assertEquals("the same shading", oneChart.bands, twoChart.bands)
        // The plan is what the chart was built from, so the counts differ even though the bands cannot.
        assertEquals(2, twoChart.footer!!.periodCount)
        assertEquals(1, oneChart.footer!!.periodCount)
        assertNotEquals("two periods and one are not one graph", oneChart.footer, twoChart.footer)
    }

    @Test
    fun theSuppliedPlanWinsOverWhatThePlannerWouldSayNow() {
        val live = pricesAroundNow()
        val today = LocalDate.now(zone)
        val from = today.atTime(1, 7).atZone(zone).toOffsetDateTime()
        val supplied = ChargingPlan(
            start = from,
            end = from.plusHours(2),
            powerKw = 6.9,
            energyKwh = 12.5,
            distanceMil = 7.5,
            cost = 42.5,
            unpricedSlots = 0,
            periods = listOf(ChargingPeriod(from, from.plusHours(2)))
        )

        val chart = ChartPass.chart(inputs, live, supplied)!!
        assertEquals(
            ChartOverlay.planned(supplied.periods).bands(live, zone),
            chart.bands
        )
        val recalculated = ChargingPlanner.calculate(live, inputs)!!
        assertNotEquals(
            "the chart is not what the planner would say now",
            ChartOverlay.planned(recalculated.periods).bands(live, zone),
            chart.bands
        )
        assertEquals(12.5, chart.footer!!.energyKwh, 0.0)
        assertEquals(from, chart.footer!!.start)
    }

    @Test
    fun aPassThatMustNotCalculatePlansNothing() {
        var calls = 0
        val counting: (PriceResult, PlanningInputs) -> ChargingPlan? = { _, _ -> calls++; plan() }

        // Home Assistant owns the plan (Auto), or no authority was resolved: Android calculates nothing.
        assertNull(ChartPass.plan(inputs, prices(), calculates = false, nothingToCharge = false, calculate = counting))
        // Nothing to charge is decided above the planner, and stays there.
        assertNull(ChartPass.plan(inputs, prices(), calculates = true, nothingToCharge = true, calculate = counting))
        // No prices yet, or no inputs at all (a widget whose catalogue has no area): nothing to calculate.
        assertNull(ChartPass.plan(inputs, null, calculates = true, nothingToCharge = false, calculate = counting))
        assertNull(ChartPass.plan(null, prices(), calculates = true, nothingToCharge = false, calculate = counting))
        assertEquals("none of those is a calculation", 0, calls)
    }

    @Test
    fun aNullPlanStillDrawsTheMarketAndNothingElse() {
        val prices = prices()
        // Inputs with no plan: the prices, no shading, no footer -- the market-only graph.
        val marketOnly = ChartPass.chart(inputs, prices, null)!!
        assertEquals(ChartMarket.of(inputs), marketOnly.market)
        assertTrue("nothing is shaded", marketOnly.bands.isEmpty())
        assertNull("and nothing is described", marketOnly.footer)

        // The same when the plan is deliberately not shown.
        val hidden = LocalCharts.fromPlan(inputs, prices, plan(), showPlan = false)
        assertTrue(hidden.bands.isEmpty())
        assertNull(hidden.footer)
        assertEquals(marketOnly, hidden)

        // And with no inputs there is no market to draw at all: the renderer's own unavailable state.
        assertNull(ChartPass.chart(null, prices, plan()))
    }

    @Test
    fun theWidgetsEntryPlansOnceAndCarriesThatPlansBandsAndFooter() {
        val live = pricesAroundNow()
        val chart = LocalCharts.of(inputs, live)

        assertTrue("a plan exists in these prices", chart.bands.isNotEmpty())
        assertTrue("and the footer describes it", chart.footer!!.periodCount >= 1)
        assertEquals(LocalCharts.fromPlan(inputs, live, ChargingPlanner.calculate(live, inputs)), chart)

        val hidden = LocalCharts.of(inputs, live, showPlan = false)
        assertTrue(hidden.bands.isEmpty())
        assertNull(hidden.footer)
    }
}
