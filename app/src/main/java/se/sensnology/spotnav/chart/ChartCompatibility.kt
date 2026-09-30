package se.sensnology.spotnav.chart

import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceResult

/**
 * A chart adapted from a plan: the market, its bands, and the footer's facts.
 *
 * [LocalCharts.fromPlan] serves a caller that already owns a plan; [LocalCharts.of] calculates one
 * (at most once) and delegates to it, so a screen's graph is the plan its words were written from.
 */
internal data class LocalChart(
    val market: ChartMarket,
    val bands: List<ChartBand>,
    val footer: ChartFooterPlan?,
    /**
     * Prices to draw when they are not the relay prices the screen loaded: a paired charger's chart uses
     * Home Assistant's all-in intervals (see [DashboardChart]). `null` means the screen's own result.
     */
    val drawnPrices: PriceResult? = null,
    val figures: PairedPlanFigures? = null
)

internal object LocalCharts {
    /**
     * The chart for a plan the caller already calculated: the market of [inputs], the plan's periods
     * as bands in the market's zone, and the footer's facts from the same plan. Calls no planner.
     *
     * [showPlan] false, a null plan or null prices all yield empty bands and no footer: the
     * market-only graph.
     */
    fun fromPlan(
        inputs: PlanningInputs,
        prices: PriceResult?,
        plan: ChargingPlan?,
        showPlan: Boolean = true
    ): LocalChart {
        val market = ChartMarket.of(inputs)
        if (!showPlan || plan == null || prices == null) return LocalChart(market, emptyList(), null)
        val zoneId = PriceMarkets.find(market.areaId)?.zoneId ?: java.time.ZoneId.systemDefault()
        return LocalChart(
            market = market,
            bands = ChartOverlay.planned(plan.periods).bands(prices, zoneId),
            footer = ChartFooterPlan(
                start = plan.start,
                end = plan.end,
                periodCount = plan.periods.size,
                unpriced = plan.unpricedSlots > 0,
                energyKwh = plan.energyKwh,
                distanceMil = plan.distanceMil
            )
        )
    }

    /** The chart for a caller without a plan (the widget renderer): calculates once, then [fromPlan]. */
    fun of(inputs: PlanningInputs, prices: PriceResult?, showPlan: Boolean = true): LocalChart {
        val plan = if (!showPlan || prices == null) null else ChargingPlanner.calculate(prices, inputs)
        return fromPlan(inputs, prices, plan, showPlan)
    }
}
