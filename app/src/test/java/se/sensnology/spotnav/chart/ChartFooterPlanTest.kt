package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalDate
import java.time.ZoneId

/** The footer's facts, and why they belong to the graph's identity. */
class ChartFooterPlanTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val settings = WidgetSettings(area = "SE4")
    private val date = LocalDate.of(2026, 9, 12)

    /** The plan's own span, as the planner states it: 22:00 to midnight is two hours of two periods. */
    private val start = date.atTime(22, 0).atZone(stockholm).toOffsetDateTime()
    private val end = start.plusHours(2)

    /** One drawn band: 01:00 to 03:00 of the represented day. */
    private val band = ChartBand(date, 60f, 180f)

    private fun prices(): PriceResult {
        val midnight = date.atStartOfDay(stockholm).toOffsetDateTime()
        return PriceResult(
            (0 until 96).map { index -> PricePoint(midnight.plusMinutes(index * 15L), 0.5) },
            emptyList(),
            0L
        )
    }

    private fun footer(
        periods: Int = 1,
        unpriced: Boolean = false,
        energyKwh: Double = 20.0,
        distanceMil: Double = 10.0
    ): ChartFooterPlan = ChartFooterPlan(start, end, periods, unpriced, energyKwh, distanceMil)

    /** A request with everything but the footer held still. */
    private fun request(footer: ChartFooterPlan?): ChartRequest =
        ChartRequests.of(
            ChartMarket.of(LocalPlanningInputs.of(settings)),
            listOf(band),
            prices(),
            1000,
            ChartProfile.PLAN_CARD,
            footer
        )!!

    @Test
    fun aFooterRefusesWhatAPlanCannotSay() {
        assertThrows(IllegalArgumentException::class.java) { ChartFooterPlan(end, start, 1, false, 20.0, 10.0) }
        assertThrows(IllegalArgumentException::class.java) { ChartFooterPlan(start, start, 1, false, 20.0, 10.0) }
        assertThrows(IllegalArgumentException::class.java) { footer(periods = 0) }
        assertThrows(IllegalArgumentException::class.java) { footer(periods = -1) }
        assertThrows(IllegalArgumentException::class.java) { footer(energyKwh = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { footer(energyKwh = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { footer(distanceMil = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { footer(distanceMil = -0.1) }
        assertEquals(0.0, footer(energyKwh = 0.0, distanceMil = 0.0).energyKwh, 0.0)
    }

    @Test
    fun aFooterIsItsFactsAndEveryOneOfThemChangesIt() {
        assertEquals(footer(), footer())
        assertEquals(footer().hashCode(), footer().hashCode())
        assertNotEquals(footer(), footer(periods = 2))
        assertNotEquals(footer(), footer(unpriced = true))
        assertNotEquals(footer(), footer(energyKwh = 20.1))
        assertNotEquals(footer(), footer(distanceMil = 10.1))
        assertNotEquals(footer(), footer().copy(end = end.plusMinutes(15)))
        assertNotEquals(footer(), footer().copy(start = start.minusMinutes(15)))
    }

    @Test
    fun thePeriodCountOfEqualBandsIsPartOfTheRequest() {
        val one = request(footer(periods = 1))
        val two = request(footer(periods = 2))

        assertEquals(one.bands, two.bands)
        assertNotEquals("a footer naming two periods is a different graph", one, two)
    }

    @Test
    fun theEnergyOfEqualBandsIsPartOfTheRequest() {
        val light = request(footer(energyKwh = 20.0))
        val heavy = request(footer(energyKwh = 32.5))
        assertEquals(light.bands, heavy.bands)
        assertNotEquals("a footer stating another energy is a different graph", light, heavy)
    }

    @Test
    fun theDistanceOfEqualBandsIsPartOfTheRequest() {
        val near = request(footer(distanceMil = 10.0))
        val far = request(footer(distanceMil = 14.0))
        assertEquals(near.bands, far.bands)
        assertNotEquals("a footer stating another distance is a different graph", near, far)
    }

    @Test
    fun theUnpricedFlagOfEqualBandsIsPartOfTheRequest() {
        val measured = request(footer(unpriced = false))
        val unpriced = request(footer(unpriced = true))
        assertEquals(measured.bands, unpriced.bands)
        assertNotEquals("a footer that says the charge carried no price is a different graph", measured, unpriced)
    }

    @Test
    fun touchingPeriodsAndOneContinuousPeriodDrawOneBandAndAreStillDifferentGraphs() {
        fun period(fromHour: Int, toHour: Int) = ChargingPeriod(
            date.atTime(fromHour, 0).atZone(stockholm).toOffsetDateTime(),
            date.atTime(toHour, 0).atZone(stockholm).toOffsetDateTime()
        )

        val touching = ChartOverlay.planned(listOf(period(1, 2), period(2, 3))).bands(prices(), stockholm)
        val continuous = ChartOverlay.planned(listOf(period(1, 3))).bands(prices(), stockholm)
        assertEquals("the union of two touching periods is the one continuous band", continuous, touching)

        val touchingFooter = ChartFooterPlan(start, end, 2, false, 20.0, 10.0)
        val continuousFooter = ChartFooterPlan(start, end, 1, false, 20.0, 10.0)
        val market = ChartMarket.of(LocalPlanningInputs.of(settings))
        val twoBands = ChartRequests.of(market, touching, prices(), 1000, ChartProfile.PLAN_CARD, touchingFooter)!!
        val oneBand = ChartRequests.of(market, continuous, prices(), 1000, ChartProfile.PLAN_CARD, continuousFooter)!!

        assertEquals("same union, same shading", oneBand.bands, twoBands.bands)
        assertNotEquals("one period and two are not one graph", oneBand, twoBands)
    }

    @Test
    fun aFootersInstantsAreThePlansOwnRatherThanTheBands() {
        val request = request(footer())
        assertEquals(start, request.footer!!.start)
        assertEquals(end, request.footer!!.end)
        assertTrue("the plan's span is not the band's", request.footer!!.end.isAfter(end.minusHours(1)))
        assertEquals(60f, request.bands.single().fromMinute, 0f)
    }

    @Test
    fun aGraphWithNothingToDescribeIsStillAGraph() {
        val none = request(null)
        assertNull(none.footer)
        assertEquals(none, request(null))
        assertNotEquals("a described plan draws more than an undescribed one", none, request(footer()))
    }
}
