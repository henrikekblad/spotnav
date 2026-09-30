package se.sensnology.spotnav.chart

import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceResult
import java.time.LocalTime

/** Test-side adapters from calculation inputs to the chart core's arguments. */
internal fun ChartRequests.of(
    inputs: PlanningInputs,
    prices: PriceResult?,
    widthPx: Int,
    profile: ChartProfile,
    selectionMinute: Float? = null,
    showPlan: Boolean = true,
    nowMinute: Float? = null
): ChartRequest? {
    val chart = LocalCharts.of(inputs, prices, showPlan)
    return of(
        market = chart.market,
        bands = chart.bands,
        prices = prices,
        widthPx = widthPx,
        profile = profile,
        footer = chart.footer,
        selectionMinute = selectionMinute,
        nowMinute = nowMinute
    )
}

internal fun ChartSelection.nearest(x: Float, metrics: ChartMetrics, inputs: PlanningInputs, result: PriceResult): ChartReadout? =
    nearest(x, metrics, ChartMarket.of(inputs), result)

internal fun ChartSelection.positions(inputs: PlanningInputs, result: PriceResult): List<LocalTime> =
    positions(ChartMarket.of(inputs), result)

internal fun ChartSelection.readoutAt(time: LocalTime, inputs: PlanningInputs, result: PriceResult): ChartReadout? =
    readoutAt(time, ChartMarket.of(inputs), result)
