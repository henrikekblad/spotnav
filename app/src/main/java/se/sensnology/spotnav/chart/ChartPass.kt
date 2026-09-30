package se.sensnology.spotnav.chart

import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceResult

/**
 * One production chart pass: the plan the screen calculated and the chart drawn from it.
 *
 * The card's text, the table handover and the chart shading all describe **one** plan, computed once
 * from the inputs and prices the pass captured. [chart] goes through `LocalCharts.fromPlan`, which
 * cannot calculate, so the planner runs at most once per pass. [plan] takes the calculation as a
 * parameter so a test can count calls.
 */
internal object ChartPass {
    /**
     * The plan one pass draws with, or `null` when there is none to show: Android does not calculate for
     * this authority, there is nothing to charge, or inputs or prices are missing. [calculate] is called
     * at most once.
     */
    fun plan(
        inputs: PlanningInputs?,
        prices: PriceResult?,
        calculates: Boolean,
        nothingToCharge: Boolean,
        calculate: (PriceResult, PlanningInputs) -> ChargingPlan? = { result, inputs ->
            ChargingPlanner.calculate(result, inputs)
        }
    ): ChargingPlan? = when {
        !calculates || nothingToCharge -> null
        inputs == null || prices == null -> null
        else -> calculate(prices, inputs)
    }

    /**
     * The chart for the plan [plan] just decided. `null` means no market to draw (no inputs); inputs
     * without a plan give the market-only graph, with no bands and no footer.
     */
    fun chart(inputs: PlanningInputs?, prices: PriceResult?, plan: ChargingPlan?): LocalChart? =
        inputs?.let { LocalCharts.fromPlan(it, prices, plan) }
}
