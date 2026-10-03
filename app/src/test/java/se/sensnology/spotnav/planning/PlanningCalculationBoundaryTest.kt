package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ChartSelection
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.prices.PriceTableModels
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime
import java.time.OffsetDateTime

/** The calculation boundary itself: one effective-price series for every consumer. */
class PlanningCalculationBoundaryTest {
    private val start = OffsetDateTime.parse("2026-09-12T00:00:00+02:00")

    private val settings = WidgetSettings(
        area = "SE4",
        vat = true,
        tax = true,
        transfer = true,
        taxMinorUnit = 39.0,
        gridFeeMinorUnit = 25.0,
        chargingAmps = 16,
        chargingPhases = 3,
        chargingKwh = 20.0,
        consumptionKwhPerMil = 2.0
    )

    @Test fun theChartTheTableAndThePlannerShareOneEffectivePriceSeries() {
        val inputs = LocalPlanningInputs.of(settings)
        val result = PriceResult(
            (0 until 96).map { index ->
                PricePoint(start.plusMinutes(index * 15L), 1.0 + (index % 4) * 0.25)
            },
            emptyList(),
            0L
        )

        // The table's own cells, the chart's own readout at a position, and the one fiscal formula
        // -- for the same published price, all three are one number.
        val model = PriceTableModels.create(result, inputs, start, plan = null)
        val cell = model.rows.first { it.position.time == LocalTime.of(0, 15) }.today!!
        val readout = ChartSelection.readoutAt(LocalTime.of(0, 15), ChartMarket.of(inputs), result)!!
        val publishedPrice = result.today.first { it.start == start.plusMinutes(15) }.pricePerKwh

        assertEquals(inputs.apply(publishedPrice), cell.price, 1e-12)
        assertEquals(cell.price, readout.today.single().price, 1e-12)
        assertEquals(inputs.apply(publishedPrice), readout.today.single().price, 1e-12)

        // And the planner charges exactly what that same series says: its cost is recomputed here
        // from the *table's* own cells, at the energy a slot delivers, so planner and table are
        // proven to share one series rather than to agree.
        val plan = ChargingPlanner.calculate(result, inputs, start)!!
        val energyPerSlot = ChargingPlanner.powerKw(inputs.amps, inputs.phases) * 0.25
        val tablePriceByTime = model.rows.mapNotNull { row -> row.today?.let { cell -> row.position.time to cell.price } }
            .toMap()
        val expectedCost = plan.periods.sumOf { period ->
            var minute = period.start
            var sum = 0.0
            while (minute < period.end) {
                sum += energyPerSlot * tablePriceByTime.getValue(minute.toLocalTime())
                minute = minute.plusMinutes(15)
            }
            sum
        } / 100.0
        assertEquals(expectedCost, plan.cost, 1e-9)
        assertTrue(plan.cost > 0.0)
    }
}
