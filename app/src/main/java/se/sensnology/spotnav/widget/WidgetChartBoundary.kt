package se.sensnology.spotnav.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chart.ChartBoundaryAction
import se.sensnology.spotnav.chart.DayBoundary
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.chart.ChartBoundaryNeed
import se.sensnology.spotnav.chart.ChartBoundaryPlan
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.prices.PriceRepository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The widget's chart-boundary appointments: one inexact interval alarm and one allow-while-idle local-midnight
 * alarm for the whole installation. The decision is
 * [ChartBoundaryPlan]'s; this owns arming, replacing and cancelling it.
 *
 * The intent identity is fixed, so re-arming replaces the pending alarm. [ACTION] and [REQUEST_CODE]
 * differ from `PriceUpdateScheduler`'s so the two schedules cannot cancel each other.
 *
 * Delivery is not guaranteed: no exact-alarm permission is requested, so `setWindow` is only a request
 * that Doze or app-standby may defer. Other redraws recompute the boundary from fresh data, so the line
 * can lag its interval but not drift.
 */
internal object WidgetChartBoundary {
    /** Distinct from `PriceUpdateScheduler`'s action: two schedules must not cancel each other. */
    const val ACTION = "se.sensnology.spotnav.CHART_BOUNDARY"

    /** Its own request code, for the same reason. */
    private const val REQUEST_CODE = 13_036

    /** The day-boundary alarm's own code: it is a separate appointment, so a deferred interval alarm cannot take it with it. */
    private const val DAY_REQUEST_CODE = 13_037

    /** The window requested of the platform; a hint, not a bound. */
    private const val WINDOW_MILLIS = 60_000L

    /**
     * Recompute the appointment for every configured widget from the data it holds. Background only, and
     * once per batch, after the whole batch has been drawn. Touches no network.
     */
    fun refresh(context: Context) {
        val ids = configuredIds(context)
        if (ids.isEmpty()) {
            cancel(context)
            return
        }
        armDayBoundary(context, ids)
        val dashboards = WidgetDashboardStore.forContext(context)
        val held = HashMap<String, StoredDashboard?>()
        val needs = ids.mapNotNull { id -> needOf(context, id, dashboards, held) }
        when (val action = ChartBoundaryPlan.action(OffsetDateTime.now(), needs)) {
            is ChartBoundaryAction.Arm -> arm(context, action.atMillis)
            // Only the interval appointment: the day boundary stays armed above.
            ChartBoundaryAction.Cancel -> cancelInterval(context)
        }
    }

    /** The market and prices one widget's bitmap is drawn from, so the appointment and the line agree. */
    private fun needOf(
        context: Context,
        id: Int,
        dashboards: WidgetDashboardStore,
        held: MutableMap<String, StoredDashboard?>
    ): ChartBoundaryNeed? {
        val settings = WidgetSettings.load(context, id)
        val chargerProfileId = settings.chargerProfileId
        if (chargerProfileId != null) {
            val known = WidgetChargerResolver.resolve(settings, ChargerProfileStore.forContext(context)) != null
            val stored = if (known) held.getOrPut(chargerProfileId) { dashboards.dashboardFor(chargerProfileId) } else null
            val source = WidgetChartSource.of(chargerProfileId, known, settings, stored)
            if (source is WidgetChartSource.Dashboard) return source.need
        }
        val inputs = LocalPlanningInputs.ofOrNull(settings) ?: return null
        return ChartBoundaryNeed(ChartMarket.of(inputs), PriceRepository.heldOnly(context, settings.area))
    }

    /** Asks every widget to redraw from what is held: no network, no planner. */
    fun requestRedraw(context: Context) {
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }

    /** Cancels when no configured widget is left. Main-thread safe and never arms. */
    fun cancelIfNoWidgets(context: Context) {
        if (configuredIds(context).isEmpty()) cancel(context)
        else Log.i("SpotNavChartBoundary", "A widget remains: the boundary appointment stays")
    }

    /**
     * The local-midnight redraw, separate from the interval appointment and allowed while idle: a chart
     * cut by the local clock needs only a redraw there, and `setWindow` would be held to the next
     * maintenance window. Still inexact (no exact-alarm permission), so it may lag by minutes under
     * Doze; `setAndAllowWhileIdle` is rate-limited by the platform, which one alarm a day never meets.
     */
    private fun armDayBoundary(context: Context, ids: List<Int>) {
        val zones = ids.map { PriceMarkets.find(WidgetSettings.load(context, it).area)?.zoneId ?: ZoneId.systemDefault() }
            .distinct()
        val at = DayBoundary.nextMidnight(Instant.now(), zones) ?: return
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pendingIntent(context, DAY_REQUEST_CODE)
        )
        Log.i("SpotNavChartBoundary", "Next day boundary $at")
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, DAY_REQUEST_CODE))
        cancelInterval(context)
        Log.i("SpotNavChartBoundary", "Boundary appointments cancelled")
    }

    private fun cancelInterval(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun arm(context: Context, atMillis: Long) {
        context.getSystemService(AlarmManager::class.java).setWindow(
            AlarmManager.RTC_WAKEUP,
            atMillis,
            WINDOW_MILLIS,
            pendingIntent(context)
        )
        Log.i("SpotNavChartBoundary", "Next chart boundary ${java.time.Instant.ofEpochMilli(atMillis)}")
    }

    private fun configuredIds(context: Context): List<Int> {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
            .filter { WidgetSettings.isConfigured(context, it) }
    }

    private fun pendingIntent(context: Context, requestCode: Int = REQUEST_CODE): PendingIntent {
        val intent = Intent(context, PriceWidgetProvider::class.java).apply { action = ACTION }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
