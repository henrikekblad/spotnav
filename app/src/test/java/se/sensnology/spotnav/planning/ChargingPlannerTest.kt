package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime

class ChargingPlannerTest {
    /** The calculation inputs for these widget settings. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)
    @Test fun powerCalculationsUseSelectedPhaseCount() {
        assertEquals(3.68, ChargingPlanner.powerKw(16, 1), 0.001)
        assertEquals(11.085, ChargingPlanner.powerKw(16, 3), 0.001)
    }

    @Test fun selectsCheapestContiguousSlotsBeforeDeparture() {
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        val prices = (listOf(4.0, 3.0, 0.5, 0.4, 2.0, 3.0) + listOf(3.0, 3.0)).mapIndexed { index, price ->
            PricePoint(start.plusMinutes(index * 15L), price)
        }
        val settings = WidgetSettings(chargingPhases = 1, chargingAmps = 10, chargingKwh = 1,
            useDepartureTime = true, departureHour = 20, departureMinute = 0)
        val plan = ChargingPlanner.calculate(PriceResult(prices, emptyList(), 0), inputs(settings), start)
        assertNotNull(plan)
        assertEquals(start.plusMinutes(30), plan!!.start)
        assertEquals(0, plan.unpricedSlots)
    }

    @Test fun splitsChargingIntoConfiguredMaximumNumberOfPeriods() {
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        val prices = (listOf(9.0, 1.0, 8.0, 0.5, 7.0) + listOf(7.0, 7.0, 7.0)).mapIndexed { index, price ->
            PricePoint(start.plusMinutes(index * 15L), price)
        }
        val settings = WidgetSettings(
            chargingPhases = 1, chargingAmps = 10, chargingKwh = 1,
            maxChargingPeriods = 2, useDepartureTime = true,
            departureHour = 20, departureMinute = 0
        )

        val plan = ChargingPlanner.calculate(PriceResult(prices, emptyList(), 0), inputs(settings), start)!!

        assertEquals(2, plan.periods.size)
        assertEquals(start.plusMinutes(15), plan.periods[0].start)
        assertEquals(start.plusMinutes(45), plan.periods[1].start)
    }

    @Test fun equalPricesChargeInTheLatestSlotsBeforeTheDeparture() {
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        val prices = List(8) { index -> PricePoint(start.plusMinutes(index * 15L), 2.0) }
        // Two slots of 2.3 kW over 15 minutes: 1 kWh needs two slots.
        val settings = WidgetSettings(chargingPhases = 1, chargingAmps = 10, chargingKwh = 1,
            useDepartureTime = true, departureHour = 20, departureMinute = 0)

        val plan = ChargingPlanner.calculate(PriceResult(prices, emptyList(), 0), inputs(settings), start)!!

        assertEquals(1, plan.periods.size)
        assertEquals(start.plusMinutes(90), plan.start)
        assertEquals(start.plusMinutes(120), plan.end)
    }

    @Test fun twoEquallyCheapValleysTakeTheLaterOne() {
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        val prices = listOf(5.0, 1.0, 1.0, 5.0, 5.0, 1.0, 1.0, 5.0).mapIndexed { index, price ->
            PricePoint(start.plusMinutes(index * 15L), price)
        }
        val settings = WidgetSettings(chargingPhases = 1, chargingAmps = 10, chargingKwh = 1,
            maxChargingPeriods = 1, useDepartureTime = true, departureHour = 20, departureMinute = 0)

        val plan = ChargingPlanner.calculate(PriceResult(prices, emptyList(), 0), inputs(settings), start)!!

        assertEquals(start.plusMinutes(75), plan.start)
    }

    @Test fun aTiedSplitPrefersTheLatestSlotsSlotByTheLastOneFirst() {
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        // One cheap slot is needed twice over two periods; four slots cost the same.
        val prices = listOf(1.0, 9.0, 1.0, 9.0, 1.0, 9.0, 1.0, 9.0).mapIndexed { index, price ->
            PricePoint(start.plusMinutes(index * 15L), price)
        }
        val settings = WidgetSettings(chargingPhases = 1, chargingAmps = 10, chargingKwh = 1,
            maxChargingPeriods = 2, useDepartureTime = true, departureHour = 20, departureMinute = 0)

        val plan = ChargingPlanner.calculate(PriceResult(prices, emptyList(), 0), inputs(settings), start)!!

        assertEquals(listOf(start.plusMinutes(60), start.plusMinutes(90)), plan.periods.map { it.start })
    }
}
