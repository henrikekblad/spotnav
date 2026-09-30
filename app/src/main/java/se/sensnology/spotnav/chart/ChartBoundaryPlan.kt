package se.sensnology.spotnav.chart

import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetChartBoundary
import java.time.OffsetDateTime

/** One widget's chart, as the boundary decision needs it: the market, and the prices it holds. */
internal data class ChartBoundaryNeed(val market: ChartMarket, val result: PriceResult)

/** What the widget boundary owner should do: wait until one instant, or stop waiting. */
internal sealed interface ChartBoundaryAction {
    /** Arm the one appointment for [atMillis] (epoch milliseconds). */
    data class Arm(val atMillis: Long) : ChartBoundaryAction

    /** Stop waiting: no widget, or no current interval anywhere. */
    data object Cancel : ChartBoundaryAction
}

/**
 * Decides the widget's next interval-boundary redraw.
 *
 * Android clamps `updatePeriodMillis` to at least 30 minutes and may defer it, so the widget owns one
 * appointment computed from the same containment rule the now line is drawn by ([ChartNow]). One
 * appointment serves every widget: the earliest boundary among them. Timing is best effort; see
 * [WidgetChartBoundary].
 */
internal object ChartBoundaryPlan {
    /**
     * The single action for a refresh at [now] over every widget's chart.
     *
     * Containment is by instant, so one `now` serves widgets in other zones. A widget with no interval
     * containing `now` contributes nothing; when none contribute, the appointment is cancelled.
     */
    fun action(now: OffsetDateTime, needs: List<ChartBoundaryNeed>): ChartBoundaryAction {
        val delay = needs.mapNotNull { need ->
            ChartNow.untilNextIntervalMillis(need.market, need.result, now)
        }.minOrNull() ?: return ChartBoundaryAction.Cancel
        return ChartBoundaryAction.Arm(now.toInstant().toEpochMilli() + delay)
    }
}
