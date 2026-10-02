package se.sensnology.spotnav.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ChartNow
import se.sensnology.spotnav.chart.ChartProfile
import se.sensnology.spotnav.chart.ChartRenderer
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.PricePublicationBatch
import se.sensnology.spotnav.prices.PricePublications
import se.sensnology.spotnav.prices.PriceRepository
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.prices.PublicationOutcome
import se.sensnology.spotnav.prices.runPublicationBatch
import se.sensnology.spotnav.ui.WidgetConfigActivity
import java.util.concurrent.Executors

class PriceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateBatch(context, manager, ids.toList(), goAsync(), Mode.ORDINARY)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // A resize is drawn from what is held.
        updateBatch(context, manager, listOf(id), goAsync(), Mode.REDRAW)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PriceUpdateScheduler.scheduleNext(context, tomorrowAvailable = false)
            // Alarms do not survive a reboot: redraw from what is held, which re-arms the chart boundaries.
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
            updateBatch(context, manager, ids.toList(), goAsync(), Mode.REDRAW)
        } else if (intent.action == ACTION_PUBLICATION_CHECK) {
            // Schedule the next attempt now; the result below moves it to the next day if tomorrow is available.
            PriceUpdateScheduler.scheduleNext(context, tomorrowAvailable = false)
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PriceWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            val result = goAsync()
            // One operation for all widgets; see publishPrices.
            if (ids.isEmpty()) result.finish() else publishPrices(context, manager, ids, result)
        } else if (intent.action == WidgetChartBoundary.ACTION) {
            // The chart's own boundary: redraw what is held; the batch's end re-arms the next one.
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
            updateBatch(context, manager, ids.toList(), goAsync(), Mode.REDRAW)
        } else if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) update(context, AppWidgetManager.getInstance(context), id, goAsync(), forceRefresh = true)
        }
    }

    override fun onEnabled(context: Context) {
        PriceUpdateScheduler.scheduleNext(context, tomorrowAvailable = false)
    }

    override fun onDisabled(context: Context) {
        PriceUpdateScheduler.cancel(context)
        WidgetChartBoundary.cancel(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetSettings.delete(context, it) }
        // Cancel the boundary appointment now, on the main thread, if no widget is left.
        WidgetChartBoundary.cancelIfNoWidgets(context)
    }

    /**
     * What a batch may wait for: [REDRAW] draws what is held (chart boundary, resize), [ORDINARY] refreshes
     * what is stale, [FORCED] refreshes whatever its age (user refresh, publication check).
     */
    internal enum class Mode(val network: Boolean, val force: Boolean) {
        REDRAW(network = false, force = false),
        ORDINARY(network = true, force = false),
        FORCED(network = true, force = true)
    }

    companion object {
        const val ACTION_REFRESH = "se.sensnology.spotnav.REFRESH"
        const val ACTION_PUBLICATION_CHECK = "se.sensnology.spotnav.PUBLICATION_CHECK"
        private const val TAG = "SpotNavWidget"
        private val executor = Executors.newSingleThreadExecutor()

        fun update(context: Context, manager: AppWidgetManager, id: Int, pending: PendingResult? = null, forceRefresh: Boolean = false) {
            updateBatch(context, manager, listOf(id), pending, if (forceRefresh) Mode.FORCED else Mode.ORDINARY)
        }

        /**
         * One batch of widget updates: every widget drawn, then the appointments re-armed once, then the
         * pending result finished once. The batch is the unit of the network budget ([WidgetBatch]).
         */
        internal fun updateBatch(context: Context, manager: AppWidgetManager, ids: List<Int>, pending: PendingResult?, mode: Mode) {
            if (ids.isEmpty()) {
                pending?.finish()
                return
            }
            executor.execute {
                var failure: Throwable? = null
                try {
                    val batch = WidgetBatch(context, mode)
                    for (id in ids) {
                        try {
                            updateNow(context, manager, id, batch)
                        } catch (error: Throwable) {
                            // One widget's failure does not stop the rest.
                            val first = failure
                            if (first == null) failure = error else first.addSuppressed(error)
                        }
                    }
                } finally {
                    finishBatch(context, pending)
                }
                failure?.let { throw it }
            }
        }

        /**
         * One publication operation: every widget refreshed against the relay, then one event per area,
         * only after the last accepted result is stored. The pending result is finished exactly once.
         */
        fun publishPrices(context: Context, manager: AppWidgetManager, ids: IntArray, pending: PendingResult) {
            executor.execute {
                val batch = WidgetBatch(context, Mode.FORCED)
                // The batch rules live in the tested runPublicationBatch.
                runPublicationBatch(
                    widgets = ids.toList(),
                    visit = { id, outcomes ->
                        batch.outcomes = outcomes
                        updateNow(context, manager, id, batch)
                    },
                    finish = { finishBatch(context, pending) },
                    announce = { PricePublicationBatch.announce(it, PricePublications.process) }
                )
            }
        }

        /** After the last widget of a batch: re-arm the schedules once from what is held, then finish. */
        private fun finishBatch(context: Context, pending: PendingResult?) {
            try {
                PriceUpdateScheduler.scheduleForActiveWidgets(context)
                WidgetChartBoundary.refresh(context)
            } finally {
                pending?.finish()
            }
        }

        /** One batch: its mode, the shared dashboard reads and network budget, and any publication outcomes. */
        internal class WidgetBatch(context: Context, val mode: Mode) {
            private val startedAt = System.currentTimeMillis()

            /** Set while a publication operation visits a widget; `null` otherwise. */
            var outcomes: MutableList<PublicationOutcome>? = null

            val dashboards = WidgetDashboardSource(
                store = WidgetDashboardStore.forContext(context),
                fetch = { profileId, timeouts ->
                    val profile = ChargerProfileStore.forContext(context).getProfile(profileId)
                    if (profile == null || !profile.configured) null
                    else HomeAssistantClient.dashboardRead(profile.toHomeAssistantSettings(), timeouts.connectMs, timeouts.readMs)
                },
                budget = WidgetNetworkBudget(startedAt, now = System::currentTimeMillis),
                now = System::currentTimeMillis,
                network = mode.network,
                force = mode.force,
                logFailure = { kind -> Log.w(TAG, "Dashboard refresh failed: $kind") }
            )
        }

        /** Renders one widget: a bound one draws its charger's stored dashboard, an unbound one its local plan; [batch] governs fetching. */
        private fun updateNow(context: Context, manager: AppWidgetManager, id: Int, batch: WidgetBatch) {
            // The catalogue first: the widget's area is only meaningful against it.
            AreaCatalogue.load(context)
            // A widget placed without the configuration screen (API 31+ configuration_optional) is drawn
            // from the defaults, stored once:
            WidgetSettings.seedDefaults(context, id)
            if (!WidgetSettings.isConfigured(context, id)) {
                Log.i(TAG, "Skipping unconfigured widget=$id")
                return
            }
            if (batch.mode.network) AreaCatalogue.refreshIfDue(context)
            val settings = WidgetSettings.load(context, id)
            val chargerProfileId = settings.chargerProfileId
            val chargerKnown = chargerProfileId != null &&
                WidgetChargerResolver.resolve(settings, ChargerProfileStore.forContext(context)) != null
            // Chart and bottom line both come from this one stored dashboard, refreshed first when due and allowed.
            val stored = if (chargerProfileId != null && chargerKnown) batch.dashboards.latest(chargerProfileId) else null
            val source = WidgetChartSource.of(chargerProfileId, chargerKnown, settings, stored)
            val statusLine = if (stored != null && settings.showChargingPlan) {
                WidgetStatusLine.compose(
                    stored.status,
                    AppLanguageSettings.language(context),
                    java.time.Instant.now(),
                    AppLanguageSettings.numberLocale(context)
                )
            } else null
            val options = manager.getAppWidgetOptions(id)
            val density = context.resources.displayMetrics.density
            val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320) * density).toInt()
            val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180) * density).toInt()
            val oneSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics)
            val chart: Bitmap = when (source) {
                is WidgetChartSource.Dashboard -> {
                    // A publication operation still loads and announces this market so screens hear of a new day; the drawing does not depend on it.
                    batch.outcomes?.let { outcomes ->
                        source.areaId.takeIf { it.isNotBlank() }?.let { areaId ->
                            outcomes += PublicationOutcome(areaId, PriceRepository.loadForPublication(context, areaId).changed)
                        }
                    }
                    ChartRenderer.renderFrame(
                        context, width, height, oneSp, source.chart.market, source.chart.prices, source.bands,
                        footer = null,
                        profile = ChartProfile.WIDGET,
                        nowMinute = ChartNow.currentMarkMinute(source.chart.market, source.chart.prices),
                        statusFooter = statusLine
                    ).bitmap
                }
                WidgetChartSource.Pass -> drawPlanPass(context, id, settings, chargerProfileId, chargerKnown, statusLine, batch, width, height, oneSp)
            }
            val views = RemoteViews(context.packageName, R.layout.widget_price)
            views.setImageViewBitmap(R.id.chart, chart)
            val openApp = Intent(context, WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(WidgetConfigActivity.EXTRA_EXISTING_WIDGET, true)
            }
            val openIntent = PendingIntent.getActivity(context, id, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)
            manager.updateAppWidget(id, views)
            Log.i(TAG, "Update complete widget=$id source=${source.javaClass.simpleName}")
        }

        /** The prices this widget's plan pass draws from, following what [batch] may fetch. */
        private fun loadPrices(context: Context, areaId: String, batch: WidgetBatch): PriceResult {
            val outcomes = batch.outcomes
            return when {
                outcomes != null -> PriceRepository.loadForPublication(context, areaId).also { published ->
                    outcomes += PublicationOutcome(areaId, published.changed)
                }.result
                batch.mode.network -> PriceRepository.load(context, areaId, batch.mode.force)
                else -> PriceRepository.heldOnly(context, areaId)
            }
        }

        /** The plan pass for a widget with no dashboard: an unpaired widget's local plan, or a paired widget's last confirmed snapshot. */
        private fun drawPlanPass(
            context: Context,
            id: Int,
            settings: WidgetSettings,
            chargerProfileId: String?,
            chargerKnown: Boolean,
            statusLine: WidgetStatusLine.Line?,
            batch: WidgetBatch,
            width: Int,
            height: Int,
            oneSp: Float
        ): Bitmap {
            val snapshot = WidgetPlanSnapshotStore.forContext(context).snapshotFor(chargerProfileId)
            // A `null` area is a paired widget with nothing confirmed: nothing is fetched and it renders unavailable.
            val area = WidgetPlanDecision.priceArea(chargerProfileId, snapshot, settings.area)
            Log.i(TAG, "Update start widget=$id area=$area")
            val data = area?.let { loadPrices(context, it, batch) }
            return when (val pass = WidgetPlanDecision.pass(chargerProfileId, chargerKnown, settings, snapshot, data)) {
                is WidgetPlanPass.Local -> {
                    // Unpaired: null inputs or prices both mean no market, so the placeholder.
                    val inputs = pass.inputs
                    if (inputs == null || data == null) {
                        ChartRenderer.emptyState(context, width, height)
                    } else {
                        ChartRenderer.render(
                            context, width, height, oneSp, inputs, data,
                            showPlan = settings.showChargingPlan,
                            // Now line only, never a selection line.
                            nowMinute = ChartNow.currentMarkMinute(ChartMarket.of(inputs), data)
                        )
                    }
                }
                is WidgetPlanPass.Remote -> {
                    // Paired without a stored dashboard: the snapshot's market with its installed bands.
                    val confirmed = pass.snapshot
                    when {
                        // Nothing confirmed: the unavailable state.
                        confirmed == null -> ChartRenderer.noticeState(
                            width, height,
                            title = AppLanguageSettings.text(context, R.string.widget_plan_unavailable)
                        )
                        // Snapshot kept but the prices moved: marked stale, with its installed windows.
                        pass.stale -> ChartRenderer.noticeState(
                            width, height,
                            title = AppLanguageSettings.text(context, R.string.widget_plan_stale),
                            details = WidgetPlanNotice.installedWindows(
                                snapshot = confirmed,
                                locale = AppLanguageSettings.locale(context)
                            ) { start, end ->
                                AppLanguageSettings.text(context, R.string.charging_window, start, end)
                            }
                        )
                        // Confirmed but no current price answer: the placeholder.
                        data == null -> ChartRenderer.emptyState(context, width, height)
                        else -> ChartRenderer.renderFrame(
                            context, width, height, oneSp, confirmed.market, data, pass.bands,
                            footer = null,
                            profile = ChartProfile.WIDGET,
                            nowMinute = ChartNow.currentMarkMinute(confirmed.market, data),
                            statusFooter = statusLine
                        ).bitmap
                    }
                }
            }
        }
    }
}
