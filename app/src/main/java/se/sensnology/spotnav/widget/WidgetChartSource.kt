package se.sensnology.spotnav.widget

import se.sensnology.spotnav.chart.ChartBand
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
    /** Home Assistant's chart, with the plan's [bands] when the widget's own choice shows them. */
    data class Dashboard(val chart: PairedChart, val bands: List<ChartBand>) : WidgetChartSource {
        /** What the chart-boundary appointment is computed from. */
        val need: ChartBoundaryNeed get() = ChartBoundaryNeed(chart.market, chart.prices)

        val areaId: String get() = chart.market.areaId
    }

    /** The plan pass decides (see [WidgetPlanDecision]). */
    data object Pass : WidgetChartSource

    companion object {
        /**
         * The source for a widget bound to [chargerProfileId] whose charger is [chargerKnown], given the
         * dashboard [stored]. [Dashboard] only when it states something to draw; otherwise [Pass].
         */
        fun of(
            chargerProfileId: String?,
            chargerKnown: Boolean,
            settings: WidgetSettings,
            stored: StoredDashboard?,
            now: java.time.Instant = java.time.Instant.now()
        ): WidgetChartSource {
            if (chargerProfileId == null || !chargerKnown || stored == null) return Pass
            val chart = DashboardChart.build(stored.dashboard, now = now) ?: return Pass
            return Dashboard(chart, if (settings.showChargingPlan) chart.bands else emptyList())
        }
    }
}
