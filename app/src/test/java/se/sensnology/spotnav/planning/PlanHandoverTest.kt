package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime

/**
 * What the price table is marked with: the app's own recommended plan when the main screen left one
 * for exactly these inputs, and a plan calculated from them otherwise.
 */
class PlanHandoverTest {
    private fun day(count: Int, price: Double = 1.0, fetchedAt: Long = 0) = PriceResult(
        (0 until count).map { index ->
            PricePoint(OffsetDateTime.parse("2026-09-12T00:00:00+02:00").plusMinutes(index * 15L), price)
        },
        emptyList(),
        fetchedAt
    )

    private val settings = WidgetSettings(chargingKwh = 4)
    private val now = OffsetDateTime.parse("2026-09-12T10:00:00+02:00")

    /** The calculation inputs for these widget settings, through the one adapter. */
    private fun inputs(settings: WidgetSettings): PlanningInputs = LocalPlanningInputs.of(settings)

    @Test
    fun theRememberedPlanIsReusedForExactlyItsOwnInputs() {
        val prices = day(192)
        val plan = ChargingPlanner.calculate(prices, inputs(settings), now)!!
        val memo = PlannedCharge(plan, inputs(settings), prices)

        assertSame(plan, PlanHandover.planFor(memo, inputs(settings), prices, now))
        // Settings that are equal by value are the same inputs: nothing about the stored plan has
        // changed, so the plan is the same object.
        assertSame(plan, PlanHandover.planFor(memo, inputs(settings.copy()), prices, now))
    }

    @Test
    fun anyChangedPlannerInputRecalculatesInsteadOfReusing() {
        val prices = day(192)
        val plan = ChargingPlanner.calculate(prices, inputs(settings), now)!!
        val memo = PlannedCharge(plan, inputs(settings), prices)

        // Every field the planner is a function of.
        val changed = listOf(
            "area" to inputs(settings).copy(areaId = "NO1"),
            "interval" to inputs(settings).copy(intervalMinutes = 60),
            "energy" to inputs(settings).copy(requestedEnergyKwh = 40.0),
            "amps" to inputs(settings).copy(amps = 16),
            "phases" to inputs(settings).copy(phases = 1),
            "consumption" to inputs(settings).copy(consumptionKwhPer10Km = 3.0),
            "driver" to inputs(settings).copy(driver = PlanDriver.TARGET_SOC),
            "departure on" to inputs(settings).copy(departure = DepartureIntent(true, java.time.LocalTime.of(8, 0))),
            "departure hour" to inputs(settings).copy(departure = DepartureIntent(true, java.time.LocalTime.of(6, 0))),
            "period cap" to inputs(settings).copy(maxPeriods = 2)
        )
        for ((what, changedSettings) in changed) {
            // What the table gets is exactly what the planner says for the changed settings: the
            // memo's plan is never marked on top of inputs it was not made from, and nothing new is
            // invented in between.
            assertEquals(
                "a plan for changed $what",
                ChargingPlanner.calculate(prices, changedSettings, now),
                PlanHandover.planFor(memo, changedSettings, prices, now)
            )
        }

        // A change that leaves the plan computable recalculates rather than hands back the
        // remembered object -- and a change that matters can be seen in the new plan, not merely in
        // its identity.
        val otherArea = PlanHandover.planFor(memo, inputs(settings).copy(areaId = "NO1"), prices, now)
        assertNotNull(otherArea)
        assertNotSame(plan, otherArea)
        val more = PlanHandover.planFor(memo, inputs(settings).copy(requestedEnergyKwh = 40.0), prices, now)!!
        assertFalse(more.energyKwh == plan.energyKwh)
    }

    @Test
    fun differentPricesAreDifferentInputs() {
        val prices = day(192)
        val plan = ChargingPlanner.calculate(prices, inputs(settings), now)!!
        val memo = PlannedCharge(plan, inputs(settings), prices)

        // Same prices, re-loaded later: still the same prices, and the planner is not a function of
        // *when* they were read.
        assertSame(plan, PlanHandover.planFor(memo, inputs(settings), prices.copy(fetchedAt = 12345), now))

        // Different prices are a different plan.
        val dearer = day(192, price = 3.0)
        assertNotSame(plan, PlanHandover.planFor(memo, inputs(settings), dearer, now))
    }

    @Test
    fun aRememberedNoPlanStaysNoPlan() {
        val prices = day(192)
        // The main screen decided there is nothing to charge.
        val memo = PlannedCharge(null, inputs(settings), prices)

        assertEquals(null, PlanHandover.planFor(memo, inputs(settings), prices, now))
        // ...even though the planner on its own would happily produce one.
        assertNotNull(ChargingPlanner.calculate(prices, inputs(settings), now))
    }

    @Test
    fun aTableRestoredWithoutAMemoStillGetsAPlan() {
        val prices = day(192)

        val plan = PlanHandover.planFor(null, inputs(settings), prices, now)
        assertNotNull("a restored table must still be marked", plan)

        // A memo that belongs to different prices is not reused either: the plan is calculated from
        // the prices actually loaded.
        val stale = PlanHandover.planFor(PlannedCharge(plan, inputs(settings), day(192, price = 9.0)), inputs(settings), prices, now)
        assertNotNull(stale)
        assertNotSame(plan, stale)
    }
}
