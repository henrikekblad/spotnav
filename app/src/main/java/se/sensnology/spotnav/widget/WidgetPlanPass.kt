package se.sensnology.spotnav.widget

import se.sensnology.spotnav.chart.ChartBand
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ChartMarketBuild
import se.sensnology.spotnav.chart.ChartOverlay
import se.sensnology.spotnav.ha.authority.AuthorityAvailability
import se.sensnology.spotnav.ha.authority.PriceRequestKey
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.MarketZone
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.planning.SchedulePeriodText
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceResult
import java.util.Locale

/**
 * What one widget render draws for its plan half.
 *
 * - [Local]: an unpaired widget calculates and draws its plan locally.
 * - [Remote]: a paired widget draws its own confirmed snapshot's market and the installed schedule's
 *   bands, or, when nothing complete can be drawn, the snapshot's market with no bands and a [Remote.Reason].
 *   With no snapshot it draws no market at all, never the widget's stored area. Nothing on this path
 *   reaches a local planner.
 */
internal sealed interface WidgetPlanPass {
    /** The widget's stored record is the authority; [inputs] is `null` when it names no resolvable area. */
    data class Local(val inputs: PlanningInputs?) : WidgetPlanPass

    /**
     * Home Assistant owns this widget's plan; the market is the snapshot's own ([market]).
     *
     * [bands] are drawn exactly when [refused] is `null`. [snapshot] is kept whenever one exists,
     * including when [stale].
     */
    data class Remote(
        val snapshot: WidgetPlanSnapshot?,
        val bands: List<ChartBand>,
        val refused: Reason?
    ) : WidgetPlanPass {
        init {
            require(bands.isNotEmpty() == (refused == null)) {
                "bands are drawn exactly when nothing was refused"
            }
            require(bands.isEmpty() || snapshot != null) {
                "a band is cut from a snapshot, so nothing can be shaded without one"
            }
            require(snapshot != null || refused in NO_PLAN) {
                "only a widget with nothing confirmed can have no snapshot"
            }
        }

        /** The market to draw: the snapshot's own, or `null` when this widget has no snapshot at all. */
        val market: ChartMarket? get() = snapshot?.market

        /**
         * Whether a retained snapshot stands while the prices in hand are not the ones it was drawn against:
         * true exactly for [Reason.NO_PRICE_ANSWER] and [Reason.PRICES_MOVED].
         */
        val stale: Boolean get() = snapshot != null && refused in PRICE_SIDE

        /** Why a paired widget shaded nothing. Stable names, not prose. */
        enum class Reason {
            /** No snapshot of the bound charger is stored (or it is unreadable). */
            NO_SNAPSHOT,

            /** The snapshot found belongs to another charger. */
            OTHER_CHARGER,

            /** The binding names a charger whose profile no longer exists. */
            CHARGER_GONE,

            /** The widget's own "show the plan" presentation choice is off. */
            NOT_SHOWN,

            /** Home Assistant stated that no schedule is installed (or stated one with no periods). */
            NOT_INSTALLED,

            /** No price answer is current, so there is nothing to clip a band to. */
            NO_PRICE_ANSWER,

            /** The prices in hand are not the documents the snapshot's shading was cut against. */
            PRICES_MOVED,

            /** The schedule is installed but none of its windows falls inside the days these prices carry. */
            OUTSIDE_PRICES
        }

        private companion object {
            /** The states in which a paired widget has no snapshot to draw at all. */
            private val NO_PLAN = setOf(Reason.NO_SNAPSHOT, Reason.OTHER_CHARGER, Reason.CHARGER_GONE)

            /** The states in which a snapshot stands while the prices in hand are not usable. */
            private val PRICE_SIDE = setOf(Reason.NO_PRICE_ANSWER, Reason.PRICES_MOVED)
        }
    }
}


/**
 * The widget's plan decision, asked in two steps: which area to price ([priceArea]), then what to draw
 * ([pass]). A paired widget prices Home Assistant's market or nothing, never its stale local area.
 */
internal object WidgetPlanDecision {
    /**
     * The area to load prices for, or `null` when the widget has no market to price. An unpaired widget
     * prices its stored area; a paired one the area of its matching snapshot, with no fallback to the
     * stored area. A snapshot of another charger names no area.
     */
    fun priceArea(chargerProfileId: String?, snapshot: WidgetPlanSnapshot?, localArea: String): String? =
        if (chargerProfileId == null) localArea
        else snapshot?.takeIf { it.profileId == chargerProfileId }?.areaId

    /**
     * What the widget draws from the loaded prices, or `null` when there was no area to load them for.
     * [chargerKnown] is whether the bound charger still exists; a removed one is the paired case with
     * nothing to draw, never a local plan.
     */
    fun pass(
        chargerProfileId: String?,
        chargerKnown: Boolean,
        settings: WidgetSettings,
        snapshot: WidgetPlanSnapshot?,
        prices: PriceResult?
    ): WidgetPlanPass {
        if (chargerProfileId == null) {
            // Unpaired: local calculation.
            return WidgetPlanPass.Local(LocalPlanningInputs.ofOrNull(settings))
        }
        fun refused(reason: WidgetPlanPass.Remote.Reason, confirmed: WidgetPlanSnapshot? = null) =
            WidgetPlanPass.Remote(snapshot = confirmed, bands = emptyList(), refused = reason)

        if (!chargerKnown) return refused(WidgetPlanPass.Remote.Reason.CHARGER_GONE)
        val confirmed = snapshot ?: return refused(WidgetPlanPass.Remote.Reason.NO_SNAPSHOT)
        if (confirmed.profileId != chargerProfileId) {
            return refused(WidgetPlanPass.Remote.Reason.OTHER_CHARGER)
        }
        if (!settings.showChargingPlan) {
            return refused(WidgetPlanPass.Remote.Reason.NOT_SHOWN, confirmed)
        }
        if (!confirmed.installed || confirmed.periods.isEmpty()) {
            return refused(WidgetPlanPass.Remote.Reason.NOT_INSTALLED, confirmed)
        }
        val result = prices ?: return refused(WidgetPlanPass.Remote.Reason.NO_PRICE_ANSWER, confirmed)
        if (!confirmed.priceIdentity.matches(result)) {
            return refused(WidgetPlanPass.Remote.Reason.PRICES_MOVED, confirmed)
        }
        // In the snapshot's own zone, like SchedulePeriodText, so bands and words share a clock.
        val bands = ChartOverlay.installed(confirmed.remotePlan).bands(result, confirmed.zoneId)
        if (bands.isEmpty()) {
            return refused(WidgetPlanPass.Remote.Reason.OUTSIDE_PRICES, confirmed)
        }
        return WidgetPlanPass.Remote(snapshot = confirmed, bands = bands, refused = null)
    }
}

/**
 * The words a paired widget states when its retained snapshot cannot be refreshed: the installed windows,
 * in the market clock of [SchedulePeriodText]. Pure and Android-free.
 */
internal object WidgetPlanNotice {
    /** One line per installed window of [snapshot], in the snapshot's own zone; [compose] localizes one window. */
    fun installedWindows(
        snapshot: WidgetPlanSnapshot,
        locale: Locale,
        compose: (String, String) -> String
    ): List<String> = snapshot.periods.map { period ->
        SchedulePeriodText.line(period, snapshot.zoneId, locale, compose)
    }
}


internal object WidgetPlanCapture {
    /**
     * The snapshot this pass confirms, or `null` when the pass is not one to capture from. [subject] is
     * the accepted price request identity, so a snapshot only comes from prices loaded for the record's
     * own area.
     */
    fun of(
        profileId: String?,
        subject: PriceRequestKey?,
        state: VisibleAuthority?,
        prices: PriceResult?,
        intervalMinutes: Int,
        capturedAt: Long
    ): WidgetPlanSnapshot? {
        val charger = profileId ?: return null
        val record = when (state) {
            is VisibleAuthority.AutoRemote ->
                state.settings.takeIf { state.availability == AuthorityAvailability.CONFIRMED }
            else -> null
        } ?: return null
        // An undecoded status says nothing about the schedule; publishing from it would clear a real plan.
        val plan = when (state) {
            is VisibleAuthority.AutoRemote -> state.remotePlan
            else -> null
        } ?: return null
        val result = prices ?: return null
        if (subject == null || subject.profileId != charger) return null
        val area = record.areaId?.takeIf { it.isNotBlank() } ?: return null
        if (subject.areaId != area) return null
        // Same market rule as the price table (`ChartMarket.from`): no market to draw, nothing to publish.
        val market = PriceMarkets.find(area) ?: return null
        val built = ChartMarket.from(record, area, intervalMinutes, market)
        if (built !is ChartMarketBuild.Ready) return null
        return WidgetPlanSnapshot(
            profileId = charger,
            revision = record.revision,
            areaId = area,
            zoneId = MarketZone.of(area),
            intervalMinutes = built.market.intervalMinutes,
            vat = built.market.vat,
            tax = built.market.tax,
            transfer = built.market.transfer,
            installed = plan.installed,
            periods = plan.periods,
            amps = plan.amps,
            chargingEnabled = plan.chargingEnabled,
            priceIdentity = PriceDocumentIdentity.of(area, result),
            capturedAt = capturedAt
        )
    }
}

/**
 * Stores an accepted pass's snapshot and asks for one widget redraw ([requestRedraw]) only when it is
 * new ([WidgetPlanSnapshotStore.Merge.Stored]).
 */
internal class WidgetPlanPublication(
    private val store: WidgetPlanSnapshotStore,
    private val requestRedraw: () -> Unit
) {
    /** What this caller last offered, so a repeated pass on the UI thread costs no storage read. Caller-confined. */
    private var lastOffered: WidgetPlanSnapshot? = null

    /** Publishes one candidate and answers what the store did. A `null` candidate publishes nothing and leaves the retained snapshot as is. */
    fun publish(candidate: WidgetPlanSnapshot?): WidgetPlanSnapshotStore.Merge? {
        if (candidate == null) return null
        lastOffered?.let { offered ->
            if (offered.sameSubjectAs(candidate)) return WidgetPlanSnapshotStore.Merge.Unchanged(offered)
        }
        val merge = store.put(candidate)
        lastOffered = merge.snapshot
        if (merge is WidgetPlanSnapshotStore.Merge.Stored) requestRedraw()
        return merge
    }
}

