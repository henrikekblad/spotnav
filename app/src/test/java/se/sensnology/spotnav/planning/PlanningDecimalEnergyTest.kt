package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** Decimal energy, end to end. */
class PlanningDecimalEnergyTest {
    private val start = OffsetDateTime.parse("2026-09-12T00:00:00+02:00")
    private val energyPerSlot = ChargingPlanner.powerKw(16, 3) * 0.25

    private val inputs = LocalPlanningInputs.of(
        WidgetSettings(chargingKwh = 20, chargingAmps = 16, chargingPhases = 3)
    )

    private fun prices(count: Int = 96, priceAt: (Int) -> Double = { 1.0 }): PriceResult = PriceResult(
        (0 until count).map { index -> PricePoint(start.plusMinutes(index * 15L), priceAt(index)) },
        emptyList(),
        0L
    )

    @Test fun aDecimalJustEitherSideOfASlotBoundaryDecidesTheSlotCount() {
        val justUnder = inputs.copy(requestedEnergyKwh = 2.77)
        val justOver = inputs.copy(requestedEnergyKwh = 2.78)

        assertEquals(15L, ChargingPlanner.durationMinutes(justUnder))
        assertEquals(30L, ChargingPlanner.durationMinutes(justOver))

        val under = ChargingPlanner.calculate(prices(), justUnder, start)!!
        val over = ChargingPlanner.calculate(prices(), justOver, start)!!
        assertEquals(15L, ChronoUnit.MINUTES.between(under.start, under.end))
        assertEquals(30L, ChronoUnit.MINUTES.between(over.start, over.end))
        assertEquals(energyPerSlot, under.energyKwh, 1e-9)
        assertEquals(2 * energyPerSlot, over.energyKwh, 1e-9)
    }

    @Test fun twentyPointFiveKilowattHoursIsDeliveredInWholeSlotsAndNeverRoundedToTwenty() {
        // A decimal this source-neutral value can carry and the local slider cannot: the whole
        // point of the type is that such a value has one place to live.
        val decimal = inputs.copy(requestedEnergyKwh = 20.5)
        val plan = ChargingPlanner.calculate(prices(), decimal, start)!!

        assertEquals(20.5, decimal.requestedEnergyKwh, 0.0)
        assertEquals(120L, ChargingPlanner.durationMinutes(decimal))
        assertEquals(120L, ChronoUnit.MINUTES.between(plan.start, plan.end))
        // Eight slots of 2.77128… is 22.17…: at least what was asked for, and within one slot of it
        // -- the grid's granularity, stated rather than hidden.
        assertEquals(8 * energyPerSlot, plan.energyKwh, 1e-9)
        assertTrue(plan.energyKwh >= 20.5)
        assertTrue("over-delivery stays under one slot", plan.energyKwh - 20.5 < energyPerSlot)
    }

    @Test fun costPeriodGroupingAndRangeFollowTheSameDecimal() {
        val result = prices { index -> 1.0 + (index % 4) * 0.25 }
        val plan = ChargingPlanner.calculate(result, inputs, start)!!

        // Range: the same delivered energy over the requested consumption, exactly.
        assertEquals(plan.energyKwh / inputs.consumptionKwhPer10Km, plan.distanceMil, 1e-9)

        // Cost: the selected intervals' own effective prices, summed at the same rate the planner
        // charges for a slot.
        val published = result.today.associateBy { it.start.toInstant() }
        val expectedMinorUnits = plan.periods.sumOf { period ->
            var minute = period.start
            var sum = 0.0
            while (minute < period.end) {
                sum += energyPerSlot * inputs.apply(published.getValue(minute.toInstant()).pricePerKwh)
                minute = minute.plusMinutes(15)
            }
            sum
        }
        assertEquals(expectedMinorUnits / 100.0, plan.cost, 1e-9)

        // The selected slots are whole quarter-hours, inside periods that do not lie.
        plan.periods.forEach { period ->
            assertEquals(0L, ChronoUnit.MINUTES.between(period.start, period.end) % 15)
            assertTrue(period.end > period.start)
        }
        // Deterministic tie order: with every price equal there are many equally cheap runs, and
        // what must never happen is a different answer for the same request.
        val uniform = ChargingPlanner.calculate(prices(), inputs, start)!!
        val again = ChargingPlanner.calculate(prices(), inputs, start)!!
        assertEquals(uniform.periods, again.periods)
        assertEquals(1, uniform.periods.size)
        assertEquals(8L, uniform.periods.sumOf { ChronoUnit.MINUTES.between(it.start, it.end) } / 15)
        uniform.periods.forEach { period ->
            assertTrue("a slot must fit before departure", period.end <= start.withHour(8))
        }
    }

    @Test fun handoverReuseIsDecimalExact() {
        val result = prices()
        val justUnder = inputs.copy(requestedEnergyKwh = 2.77)
        val plan = ChargingPlanner.calculate(result, justUnder, start)!!
        val memo = PlannedCharge(plan, justUnder, result)

        // The same decimal is the same inputs: the remembered plan is reused.
        assertSame(plan, PlanHandover.planFor(memo, justUnder, result, start))
        assertSame(plan, PlanHandover.planFor(memo, justUnder.copy(), result, start))

        // 0.01 kWh more crosses the slot boundary, so the memo is stale and a plan is calculated
        // from the new inputs -- and it is visibly a different plan.
        val justOver = justUnder.copy(requestedEnergyKwh = 2.78)
        val recalculated = PlanHandover.planFor(memo, justOver, result, start)!!
        assertNotSame(plan, recalculated)
        assertTrue(recalculated.energyKwh > plan.energyKwh)
        assertEquals(2 * energyPerSlot, recalculated.energyKwh, 1e-9)
    }
}
