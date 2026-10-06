package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.ha.dashboard.ChargeProgressContract
import se.sensnology.spotnav.ha.dashboard.ConnectionState
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaSettingsDriver
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** What the charge bar's percent counts. */
internal enum class ChargeBarBasis {
    /** A target level: the car's level now as a share of the target ("x % of the target"). */
    TARGET,

    /** A fixed amount: the energy delivered toward it as a share of the whole amount. */
    KWH,

    /** "Fill": the car's level now as a share of its own charge limit. */
    FILL,

    /** A person's Start with the car's level known: the level as a share of the car's own limit. */
    CAR_LIMIT,

    /** A person's Start with no level known: a bar that only says the charge runs, with its power. */
    OPEN
}

/**
 * The charger card's progress bar while a charge is on: [percent] (`null` for [ChargeBarBasis.OPEN]),
 * the expected end ([endsAt], `null` when it cannot be said), the charging power for the open bar,
 * and whether current is flowing ([moving]: the bar's sheen moves only then).
 */
internal data class ChargeBar(
    val basis: ChargeBarBasis,
    val percent: Int?,
    val endsAt: Instant?,
    val powerKw: Double?,
    val moving: Boolean
)

/**
 * The bar from the dashboard alone, never invented: `null` (today's status line only) whenever the
 * dashboard does not carry the numbers.
 *
 * - Shown only while the charge is on (`live.charging`), not while Home Assistant starts up, and not
 *   for a charger that reports no car, a finished car or an error.
 * - It moves while current flows: the charger reports `charging` (or states no connection) and Home
 *   Assistant does not see a car that takes no current. Otherwise it stands still and states no end.
 * - A person's Start (the `paused` line with `choice` `manual` and `action` `start`) charges to the
 *   car's own limit: level / limit (else 100 %), the end from the battery's room; with no level, the
 *   open bar and the power.
 * - A target: level / target (the dashboard carries no level at plug-in, so never a session share),
 *   the end from `soc.need_kwh`.
 * - Fill: level / the car's limit, the end from `soc.room_kwh`.
 * - A fixed amount: `plan.delivered_kwh` / (`delivered_kwh` + `remaining_kwh`), else / the requested
 *   amount; nothing without `delivered_kwh`.
 * - The percent is rounded down and kept within 0-100.
 * - The end: the remaining energy at the measured current (x phases x 230 V, 400 V between phases on
 *   three), else the installed schedule's power, else its current, else the proposal's power, else
 *   the settings' current. While a schedule window holds now, the energy is laid along the remaining
 *   windows and never ends after the last one. A solar or hybrid charge follows the sun and states no
 *   end (a person's Start does).
 */
internal object ChargeBarRule {
    private const val PAUSED_LINE = "paused"
    private const val MANUAL = "manual"
    private const val START = "start"

    private val NO_BAR = setOf(ConnectionState.DISCONNECTED, ConnectionState.FINISHED, ConnectionState.ERROR)

    fun of(dashboard: Dashboard?, now: Instant): ChargeBar? {
        val held = dashboard ?: return null
        if (!held.live.charging || held.startingUpUntil != null) return null
        if (held.connection in NO_BAR) return null
        val moving = (held.connection == null || held.connection == ConnectionState.CHARGING) &&
            !ChargeProgressContract.advisory(held.chargeProgress)
        if (personStarted(held)) return personStart(held, now, moving)
        val settings = held.settings ?: return null
        val follows = settings.strategy == HaSettingsStrategy.SOLAR || settings.strategy == HaSettingsStrategy.HYBRID
        val soc = held.soc
        val (basis, fraction, remaining) = when {
            settings.driver == HaSettingsDriver.TARGET_SOC -> {
                val level = soc?.value ?: return null
                val target = target(held) ?: return null
                Triple(ChargeBarBasis.TARGET, level / target, soc.needKwh)
            }
            settings.fillToLimit == true -> {
                val level = soc?.value ?: return null
                Triple(ChargeBarBasis.FILL, level / ceiling(held), soc.roomKwh)
            }
            else -> {
                val delivered = held.plan.deliveredKwh ?: return null
                val left = held.plan.remainingKwh
                val total = left?.let { delivered + it }
                    ?: held.plan.proposal?.requestedKwh
                    ?: settings.requestedKwh
                if (!total.isFinite() || total <= 0.0) return null
                Triple(ChargeBarBasis.KWH, delivered / total, left ?: (total - delivered).coerceAtLeast(0.0))
            }
        }
        val endsAt = if (moving && !follows) endsAt(held, remaining, now) else null
        return ChargeBar(basis, percent(fraction), endsAt, null, moving)
    }

    /** Whether the charge is a person's Start: Home Assistant charges until the car is full or unplugged. */
    fun personStarted(dashboard: Dashboard): Boolean = dashboard.status.lines.any {
        it.code == PAUSED_LINE && it.params["choice"] == MANUAL && it.params["action"] == START
    }

    private fun personStart(held: Dashboard, now: Instant, moving: Boolean): ChargeBar {
        val soc = held.soc
        val level = soc?.value
        if (level == null) {
            return ChargeBar(ChargeBarBasis.OPEN, null, null, if (moving) powerKw(held) else null, moving)
        }
        val ceiling = ceiling(held)
        val room = soc.roomKwh ?: run {
            val capacity = soc.capacityKwh?.takeIf { it > 0.0 } ?: return@run null
            val efficiency = soc.efficiency?.takeIf { it > 0.0 } ?: return@run null
            (capacity * (ceiling - level) / 100.0 / efficiency).coerceAtLeast(0.0)
        }
        return ChargeBar(
            ChargeBarBasis.CAR_LIMIT, percent(level / ceiling),
            if (moving) endsAt(held, room, now) else null, null, moving
        )
    }

    /** The car's own charge limit, else 100 %. */
    private fun ceiling(held: Dashboard): Double =
        held.soc?.vehicleMaxPercent?.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(100.0) ?: 100.0

    /** The target the charge stops at: the target, rounded as Home Assistant rounds it, never above the car's limit. */
    private fun target(held: Dashboard): Double? {
        val stated = held.soc?.targetPercent ?: held.settings?.target?.targetPercent ?: return null
        if (!stated.isFinite()) return null
        return min(Math.rint(stated), ceiling(held)).takeIf { it > 0.0 }
    }

    /** A share as a whole percent, rounded down and kept within 0-100; `null` for no number. */
    fun percent(fraction: Double): Int? {
        if (!fraction.isFinite()) return null
        return floor(fraction * 100.0 + 1e-9).toInt().coerceIn(0, 100)
    }

    /** When [remaining] kWh will have been charged, or `null` when it cannot be said. */
    fun endsAt(held: Dashboard, remaining: Double?, now: Instant): Instant? {
        val energy = remaining?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val power = powerKw(held) ?: return null
        var hours = energy / power
        val windows = held.plan.installed?.periods.orEmpty()
            .map { it.start.toInstant() to it.end.toInstant() }
            .filter { (start, end) -> end.isAfter(start) }
            .sortedBy { it.first }
        val driving = windows.any { (start, end) -> !now.isBefore(start) && now.isBefore(end) }
        if (!driving) return now.plus(span(hours))
        for ((start, end) in windows) {
            if (!end.isAfter(now)) continue
            val from = if (start.isAfter(now)) start else now
            val length = Duration.between(from, end).toMillis() / 3_600_000.0
            if (hours <= length) return from.plus(span(hours))
            hours -= length
        }
        return windows.last().second
    }

    private fun span(hours: Double): Duration = Duration.ofMillis(Math.round(hours * 3_600_000.0))

    /** The power the charge runs at, in kW: measured first, then what the schedule or the settings say. */
    fun powerKw(held: Dashboard): Double? {
        val installed = held.plan.installed
        val phases = sequenceOf(
            installed?.phases, held.chargingPhases?.phases, held.plan.proposal?.phases, held.settings?.phases
        ).firstOrNull { it == 1 || it == 3 }
        val measured = held.live.measuredCurrentA?.takeIf { it > 0.0 }
        val power = when {
            measured != null && phases != null -> kw(measured, phases)
            installed?.powerKw != null -> installed.powerKw
            installed?.amps != null && (installed.phases == 1 || installed.phases == 3) ->
                kw(installed.amps.toDouble(), installed.phases)
            held.plan.proposal?.powerKw != null -> held.plan.proposal.powerKw
            held.settings?.amps != null && phases != null -> kw(held.settings.amps.toDouble(), phases)
            else -> null
        }
        return power?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** The planner's own power: 230 V on one phase, 400 V between phases on three. */
    private fun kw(amps: Double, phases: Int): Double =
        if (phases == 1) 230.0 * amps / 1000.0 else sqrt(3.0) * 400.0 * amps / 1000.0
}

/** The words under the bar that are numbers: the end's clock and the open bar's power. */
internal object ChargeBarText {
    /**
     * The end as the phone shows a time: its own zone and 12- or 24-hour clock, with the weekday when
     * the end falls on another day than now.
     */
    fun clock(endsAt: Instant, now: Instant, zone: ZoneId, is24Hour: Boolean, locale: Locale): String {
        val end = endsAt.atZone(zone)
        val time = if (is24Hour) "HH:mm" else "h:mm a"
        val sameDay = end.toLocalDate() == now.atZone(zone).toLocalDate()
        val pattern = if (sameDay) time else "EEE $time"
        return DateTimeFormatter.ofPattern(pattern, locale).format(end)
    }

    /** A power in kW with one decimal in the screen's number locale, a whole number without ",0". */
    fun kw(powerKw: Double, locale: Locale): String =
        DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).format(Math.round(powerKw * 10.0) / 10.0)
}

/** Whether the bar's sheen moves: only while current flows and the system lets things move. */
internal object ChargeBarMotion {
    fun animate(bar: ChargeBar?, animatorsEnabled: Boolean): Boolean = bar != null && bar.moving && animatorsEnabled
}
