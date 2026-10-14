package se.sensnology.spotnav.widget

import se.sensnology.spotnav.chart.ChartAxisMode
import se.sensnology.spotnav.chart.ChartBand
import se.sensnology.spotnav.chart.ChartRequests
import se.sensnology.spotnav.chart.ChartTimeAxis
import se.sensnology.spotnav.chart.ChartBoundaryNeed
import se.sensnology.spotnav.chart.DashboardChart
import se.sensnology.spotnav.chart.PairedChart

/**
 * What one widget's chart is drawn from. A widget bound to a charger draws Home Assistant's dashboard
 * (see [DashboardChart]) from the last one stored ([WidgetDashboardStore]); everything else uses the
 * plan pass ([WidgetPlanPass]): the local plan when unpaired, or the last confirmed plan snapshot when
 * paired without a usable dashboard.
 */
internal sealed interface WidgetChartSource {
    /**
     * Home Assistant's chart on [axis], with the plan's [bands] placed on it when the widget's own choice
     * shows them.
     */
    data class Dashboard(
        val chart: PairedChart,
        val bands: List<ChartBand>,
        val axis: ChartTimeAxis = ChartTimeAxis.WallClock
    ) : WidgetChartSource {
        /** What the chart-boundary appointment is computed from. */
        val need: ChartBoundaryNeed get() = ChartBoundaryNeed(chart.market, chart.prices)

        val areaId: String get() = chart.market.areaId
    }

    /** The plan pass decides (see [WidgetPlanDecision]). */
    data object Pass : WidgetChartSource

    companion object {
        /**
         * The source for a widget bound to [chargerProfileId] whose charger is [chargerKnown], given the
         * dashboard [stored]. [Dashboard] only when it states something to draw; otherwise [Pass]. The
         * chart is placed on the time axis [mode] gives at [now], as the in-app chart is.
         */
        fun of(
            chargerProfileId: String?,
            chargerKnown: Boolean,
            settings: WidgetSettings,
            stored: StoredDashboard?,
            now: java.time.Instant = java.time.Instant.now(),
            mode: ChartAxisMode = ChartAxisMode.DEFAULT
        ): WidgetChartSource {
            if (chargerProfileId == null || !chargerKnown || stored == null) return Pass
            val chart = DashboardChart.build(stored.dashboard, now = now) ?: return Pass
            val (axis, bands) = ChartRequests.placed(
                chart.prices, DashboardChart.zone(stored.dashboard), chart.bands, chart.overlays, mode, now
            )
            return Dashboard(chart, if (settings.showChargingPlan) bands else emptyList(), axis)
        }
    }
}
