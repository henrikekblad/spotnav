package se.sensnology.spotnav.notify

import org.json.JSONObject
import se.sensnology.spotnav.ha.dashboard.ConnectionState
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.FullCarRule
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ha.settings.NotificationEvent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What one dashboard read says about the events a notification can be about, kept between two
 * background checks (see [NotificationRules]). Times are `HH:MM` in the market's zone already, so a
 * message needs nothing else.
 */
internal data class NotificationSnapshot(
    val at: Instant,
    /** The charge is on (`live.charging`, or the connection says charging). */
    val charging: Boolean,
    /** A car is plugged in: `true`, `false`, or `null` when the charger cannot say. */
    val connected: Boolean?,
    /** What identifies the installed plan (its windows); `null` with none. */
    val planKey: String?,
    /** When the installed plan's next or current window starts. */
    val planStart: String?,
    /** A window of the installed plan is open now, and when it ends. */
    val windowOpen: Boolean,
    val windowEnd: String?,
    /** A window of the installed plan ends after now. */
    val windowsLeft: Boolean,
    /**
     * Why a charge SpotNav expected is not running (`not_started`, `charger_unavailable`,
     * `held_by_charger`, `charger_disabled`, `vehicle_not_requesting`), or `null` when nothing is wrong.
     */
    val stopReason: String?,
    /** The departure cannot be met with the energy that remains. */
    val atRisk: Boolean,
    /** The departure's wall time, when one is set. */
    val departure: String?,
    /** The target state of charge was reached and the charge stopped there. */
    val targetReached: Boolean,
    val targetPercent: Double?,
    /** What remains of a manual need (Home Assistant 1.9), `null` where nothing counts it. */
    val remainingKwh: Double?,
    /** The energy of the plan the preview proposes. */
    val plannedKwh: Double?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at.toEpochMilli())
        put("charging", charging)
        put("connected", connected ?: JSONObject.NULL)
        put("plan_key", planKey ?: JSONObject.NULL)
        put("plan_start", planStart ?: JSONObject.NULL)
        put("window_open", windowOpen)
        put("window_end", windowEnd ?: JSONObject.NULL)
        put("windows_left", windowsLeft)
        put("stop_reason", stopReason ?: JSONObject.NULL)
        put("at_risk", atRisk)
        put("departure", departure ?: JSONObject.NULL)
        put("target_reached", targetReached)
        put("target_percent", targetPercent ?: JSONObject.NULL)
        put("remaining_kwh", remainingKwh ?: JSONObject.NULL)
        put("planned_kwh", plannedKwh ?: JSONObject.NULL)
    }

    companion object {
        /** A stored snapshot, or `null` when it is not one (then the next read is a baseline). */
        fun fromJson(json: JSONObject): NotificationSnapshot? = runCatching {
            fun text(key: String) = json.opt(key) as? String
            fun number(key: String) = (json.opt(key) as? Number)?.toDouble()
            fun flag(key: String) = json.opt(key) as? Boolean ?: error("$key")
            NotificationSnapshot(
                at = Instant.ofEpochMilli((json.opt("at") as Number).toLong()),
                charging = flag("charging"),
                connected = json.opt("connected") as? Boolean,
                planKey = text("plan_key"),
                planStart = text("plan_start"),
                windowOpen = flag("window_open"),
                windowEnd = text("window_end"),
                windowsLeft = flag("windows_left"),
                stopReason = text("stop_reason"),
                atRisk = flag("at_risk"),
                departure = text("departure"),
                targetReached = flag("target_reached"),
                targetPercent = number("target_percent"),
                remainingKwh = number("remaining_kwh"),
                plannedKwh = number("planned_kwh")
            )
        }.getOrNull()
    }
}

/** One notification to post: the event and the facts its message is worded with. */
internal data class LocalEvent(
    val event: NotificationEvent,
    /** `plan_stopped`: why (`stopped`, `not_started`, ...); `charge_complete`: `target`, `energy` or `plan_done`. */
    val reason: String? = null,
    /** `HH:MM`: the departure, the window's end, or the new plan's start. */
    val time: String? = null,
    val percent: Double? = null,
    val kwh: Double? = null
)

/** What one check decided: the events to post, and when each kind was last posted. */
internal data class Derivation(val events: List<LocalEvent>, val lastSent: Map<NotificationEvent, Instant>)

/**
 * The phone's own background check, as rules: a dashboard becomes a [NotificationSnapshot], and two
 * successive snapshots become the events of `docs/notifications.md` in the Home Assistant
 * integration -- the same events Home Assistant sends through the Companion app, judged from what the
 * dashboard says rather than from inside Home Assistant. Pure, so every rule is tested.
 */
internal object NotificationRules {
    /** The same event for the same charger is not posted again within this long. */
    val QUIET: Duration = Duration.ofMinutes(15)

    /** A window must have been open this long before a charge that has not started is told about. */
    val GRACE: Duration = Duration.ofMinutes(3)

    /** A previous snapshot older than this is no baseline for a change: the next one starts afresh. */
    val STALE: Duration = Duration.ofHours(3)

    private const val NEED_MET_KWH = 0.05

    /** Lines under which a charge that is not running is no fault (`docs/notifications.md`). */
    private val EXCUSED = setOf(
        "paused", "target_reached", "hybrid_satisfied", "nothing_to_charge", "load_balancing_limited"
    )

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun snapshot(dashboard: Dashboard, now: Instant, fallbackZone: ZoneId = ZoneId.systemDefault()): NotificationSnapshot {
        val zone = dashboard.market.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
        fun clock(instant: Instant) = CLOCK.format(instant.atZone(zone))
        val periods = dashboard.plan.installed?.periods.orEmpty()
            .map { it.start.toInstant() to it.end.toInstant() }
            .sortedBy { it.first }
        val open = periods.firstOrNull { (start, end) -> !now.isBefore(start) && now.isBefore(end) }
        val next = periods.firstOrNull { (_, end) -> now.isBefore(end) }
        val connection = dashboard.connection
        val connected = when (connection) {
            ConnectionState.DISCONNECTED -> false
            ConnectionState.CONNECTED, ConnectionState.CHARGING, ConnectionState.PAUSED, ConnectionState.FINISHED -> true
            ConnectionState.ERROR, null -> null
        }
        val charging = dashboard.live.charging || connection == ConnectionState.CHARGING
        val codes = dashboard.status.lines.map { it.code }.toSet()
        val remaining = dashboard.plan.remainingKwh
        val expected = open != null &&
            Duration.between(open.first, now) >= GRACE &&
            connected != false &&
            dashboard.settings?.strategy != HaSettingsStrategy.SOLAR &&
            codes.none { it in EXCUSED } &&
            (remaining == null || remaining > NEED_MET_KWH)
        val stopReason = when {
            !expected -> null
            "charger_unavailable" in codes -> "charger_unavailable"
            "held_by_charger" in codes -> "held_by_charger"
            "charger_disabled" in codes -> "charger_disabled"
            !charging -> "not_started"
            FullCarRule.advisory(dashboard) -> "vehicle_not_requesting"
            else -> null
        }
        val settings = dashboard.settings
        return NotificationSnapshot(
            at = now,
            charging = charging,
            connected = connected,
            planKey = periods.takeIf { it.isNotEmpty() }?.joinToString(";") { (start, end) -> "$start/$end" },
            planStart = next?.first?.let(::clock),
            windowOpen = open != null,
            windowEnd = open?.second?.let(::clock),
            windowsLeft = next != null,
            stopReason = stopReason,
            atRisk = dashboard.planningReason == "deadline_too_short",
            departure = settings?.takeIf { it.departureEnabled }?.departureTime,
            targetReached = "target_reached" in codes,
            targetPercent = dashboard.soc?.targetPercent ?: settings?.target?.targetPercent,
            remainingKwh = remaining,
            plannedKwh = dashboard.plan.proposal?.plannedKwh
        )
    }

    /**
     * The events between [previous] and [current] that are [chosen] and not quiet. Without a usable
     * [previous] (the first check, or one long ago) nothing is told: a check is not an event.
     */
    fun derive(
        previous: NotificationSnapshot?,
        current: NotificationSnapshot,
        chosen: Set<NotificationEvent>,
        lastSent: Map<NotificationEvent, Instant>
    ): Derivation {
        if (previous == null || Duration.between(previous.at, current.at) > STALE || current.at.isBefore(previous.at)) {
            return Derivation(emptyList(), lastSent)
        }
        val found = changes(previous, current)
        val sent = lastSent.toMutableMap()
        val events = found.filter { event ->
            val last = sent[event.event]
            val quiet = last != null && Duration.between(last, current.at) < QUIET
            (event.event in chosen && !quiet).also { if (it) sent[event.event] = current.at }
        }
        return Derivation(events, sent)
    }

    /** Every event the change from [before] to [now] is, chosen or not, in the contract's order. */
    private fun changes(before: NotificationSnapshot, now: NotificationSnapshot): List<LocalEvent> = buildList {
        if (now.stopReason != null && before.stopReason == null) {
            val reason = if (now.stopReason == "not_started" && before.charging) "stopped" else now.stopReason
            add(LocalEvent(NotificationEvent.PLAN_STOPPED, reason = reason))
        }
        if (now.atRisk && !before.atRisk) add(LocalEvent(NotificationEvent.PLAN_AT_RISK, time = now.departure))
        complete(before, now)?.let(::add)
        if (now.charging && !before.charging) add(LocalEvent(NotificationEvent.CHARGE_STARTED, time = now.windowEnd))
        if (now.connected == true && before.connected == false) add(LocalEvent(NotificationEvent.PLUGGED_IN))
        if (now.connected == false && before.connected == true) add(LocalEvent(NotificationEvent.UNPLUGGED))
        if (now.planKey != null && now.planKey != before.planKey && now.planStart != null) {
            add(LocalEvent(NotificationEvent.PLAN_INSTALLED, time = now.planStart, kwh = now.plannedKwh))
        }
    }

    private fun complete(before: NotificationSnapshot, now: NotificationSnapshot): LocalEvent? {
        val event = NotificationEvent.CHARGE_COMPLETE
        val was = before.remainingKwh
        val left = now.remainingKwh
        return when {
            now.targetReached && !before.targetReached -> LocalEvent(event, reason = "target", percent = now.targetPercent)
            was != null && was > NEED_MET_KWH && left != null && left <= NEED_MET_KWH -> LocalEvent(event, reason = "energy")
            before.charging && before.windowOpen && !now.charging && !now.windowsLeft -> LocalEvent(event, reason = "plan_done")
            else -> null
        }
    }
}
