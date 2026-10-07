package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime

/** The widget's chart boundary: the pure decision. */
class ChartBoundaryPlanTest {
    private val se4 = RelayFixtures.se4

    @Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(se4))
    }

    private fun market() = ChartMarket.of(LocalPlanningInputs.of(WidgetSettings(area = "SE4")))

    private fun at(time: String) = OffsetDateTime.parse(time)

    private fun day(from: String, hours: Int, minutes: Int) = (0 until hours * 4).map { index ->
        PricePoint(at(from).plusMinutes(index * 15L), 1.0, minutes)
    }

    private fun result(from: String = "2026-09-20T00:00:00+02:00", hours: Int = 24, minutes: Int = 15) =
        PriceResult(day(from, hours, minutes), emptyList(), 0L)

    @Test
    fun theQuarterHourBoundaryIsTheEndOfTheContainingInterval() {
        val now = at("2026-09-20T12:22:00+02:00")
        val need = ChartBoundaryNeed(market(), result())

        val action = ChartBoundaryPlan.action(now, listOf(need))

        assertEquals(
            "eight minutes to the end of the 12:15 interval",
            now.plusMinutes(8).toInstant().toEpochMilli(),
            (action as ChartBoundaryAction.Arm).atMillis
        )
    }

    @Test
    fun theHourlyBoundaryIsTheEndOfTheHourRatherThanTheQuarter() {
        val now = at("2026-09-20T12:22:00+02:00")
        val need = ChartBoundaryNeed(market(), result(minutes = 60))

        val action = ChartBoundaryPlan.action(now, listOf(need))

        assertEquals(
            "thirty-eight minutes to the end of the 12:00 hour",
            now.plusMinutes(38).toInstant().toEpochMilli(),
            (action as ChartBoundaryAction.Arm).atMillis
        )
    }

    @Test
    fun theHalfHourBoundaryIsTheEndOfTheHalfHour() {
        val now = at("2026-09-20T12:22:00+02:00")
        val need = ChartBoundaryNeed(market(), result(minutes = 30))

        val action = ChartBoundaryPlan.action(now, listOf(need))

        assertEquals(
            "eight minutes to the end of the 12:00 half-hour",
            now.plusMinutes(8).toInstant().toEpochMilli(),
            (action as ChartBoundaryAction.Arm).atMillis
        )
    }

    @Test
    fun oneAppointmentServesEveryWidgetAtTheEarliestBoundary() {
        val now = at("2026-09-20T12:22:00+02:00")
        val needs = listOf(
            ChartBoundaryNeed(market(), result()),
            ChartBoundaryNeed(market(), result()),
            ChartBoundaryNeed(market(), result())
        )

        val action = ChartBoundaryPlan.action(now, needs)

        assertEquals(now.plusMinutes(8).toInstant().toEpochMilli(), (action as ChartBoundaryAction.Arm).atMillis)
    }

    @Test
    fun noWidgetsMeansNoAppointment() {
        assertEquals(ChartBoundaryAction.Cancel, ChartBoundaryPlan.action(at("2026-09-20T12:22:00+02:00"), emptyList()))
    }

    @Test
    fun noCurrentIntervalAnywhereMeansNoAppointment() {
        val now = at("2026-09-20T12:22:00+02:00")
        val needs = listOf(
            ChartBoundaryNeed(market(), result(hours = 6)),
            ChartBoundaryNeed(market(), PriceResult(emptyList(), emptyList(), 0L))
        )

        assertEquals(ChartBoundaryAction.Cancel, ChartBoundaryPlan.action(now, needs))
    }
}
