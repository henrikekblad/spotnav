package se.sensnology.spotnav.planning

import se.sensnology.spotnav.prices.PriceResult
import java.time.OffsetDateTime

/** The plan the main screen last produced, together with the inputs that make it valid. */
data class PlannedCharge(
    val plan: ChargingPlan?,
    val inputs: PlanningInputs,
    val prices: PriceResult
)

/** Which plan the price table marks itself with. */
object PlanHandover {
    fun planFor(
        memo: PlannedCharge?,
        inputs: PlanningInputs,
        prices: PriceResult,
        now: OffsetDateTime = OffsetDateTime.now()
    ): ChargingPlan? =
        if (memo != null && memo.inputs == inputs && samePrices(memo.prices, prices)) memo.plan
        else ChargingPlanner.calculate(prices, inputs, now)

    /** Whether two results hold the same prices. */
    private fun samePrices(one: PriceResult, other: PriceResult): Boolean =
        one.today == other.today && one.tomorrow == other.tomorrow
}
