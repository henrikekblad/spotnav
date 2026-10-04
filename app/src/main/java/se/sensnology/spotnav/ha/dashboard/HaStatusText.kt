package se.sensnology.spotnav.ha.dashboard

import se.sensnology.spotnav.app.DistanceUnit
import se.sensnology.spotnav.app.MoneyText
import se.sensnology.spotnav.prices.PriceMarkets
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What the status words need to know about the reader and the market, and nothing else. */
internal data class StatusFormat(
    val language: String,
    val zone: ZoneId?,
    val currency: String?,
    val majorUnit: String?,
    /** The locale numbers and dates are written in: the same one the screen's own rows use. */
    val locale: Locale = Locale.forLanguageTag(language),
    /** Whether a distance is written in miles: the market is in Great Britain. */
    val miles: Boolean = false
) {
    companion object {
        fun of(language: String, market: DashboardMarket, locale: Locale? = null) = StatusFormat(
            language = language,
            zone = market.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() },
            currency = market.currency,
            majorUnit = market.majorUnit,
            locale = locale ?: Locale.forLanguageTag(language),
            miles = market.inGreatBritain || market.areaId?.let { PriceMarkets.find(it)?.inGreatBritain } == true
        )
    }
}

/** The charger's status line, worded from Home Assistant's own `status` block. */
internal object HaStatusText {
    /** The status as it is drawn: the headline line, and the notes that get a line of their own. */
    data class Parts(val headline: String?, val notes: List<String>)

    /** The code whose line is a note under the headline, never one of its joined facts. */
    private const val NOTE_CODE = "settings_suggested"

    /**
     * The headline (the block's lines but the note, joined), and the note lines, each worded
     * separately. The headline is `null` when the block has no other line.
     */
    fun parts(status: DashboardStatus, format: StatusFormat, now: Instant): Parts {
        val (notes, rest) = status.lines.partition { it.code == NOTE_CODE }
        val words = rest.map { line(it, format, now) }
        // A sentence's own full stop would sit oddly before the joined facts ("06:00. · 20 kWh").
        val headline = words.mapIndexed { index, part ->
            if (index < words.lastIndex) part.replace(Regex("[.。]$"), "") else part
        }.joinToString(SEPARATOR).ifEmpty { null }
        return Parts(headline, notes.map { line(it, format, now) })
    }

    /**
     * The headline alone (the widget's compact line), or `null` when the block has nothing to say
     * beyond its notes.
     */
    fun render(status: DashboardStatus, format: StatusFormat, now: Instant): String? =
        parts(status, format, now).headline

    /** The planning states that wait on prices, in the order Home Assistant words them. */
    private val PRICE_WAIT_CODES = listOf("waiting_for_history", "waiting_for_publication", "buying_before_publication")

    /**
     * The line saying the plan waits on prices (tomorrow's, or the history that decides what to
     * leave for them), or `null` when the status says no such thing.
     */
    fun priceWait(status: DashboardStatus, format: StatusFormat, now: Instant): String? {
        val wait = PRICE_WAIT_CODES.firstNotNullOfOrNull { code -> status.lines.firstOrNull { it.code == code } }
            ?: return null
        return line(wait, format, now).replace(Regex("[.。]$"), "")
    }

    const val SEPARATOR = " · "

    private val MISSING_FIELDS = mapOf(
        "area" to "status.missing.area",
        "phases" to "status.missing.phases",
        "amps" to "status.missing.amps",
        "vehicle" to "status.missing.vehicle",
        "target_percent" to "status.missing.target_percent",
    )

    /**
     * A person's Start or Stop pauses Auto for the plug-in session (`paused` with `choice` `manual`):
     * the card's key for what they did (`action`) and what ends it (`ends`: `unplug`, `next_plug_in`,
     * or `resume` on a charger that cannot say when a car is plugged in). A Start also ends when the
     * car is full; an action this build does not know reads as a Stop, as on the card.
     */
    internal fun manualPauseKey(action: Any?, ends: Any?): String = when {
        action == "start" ->
            if (ends == "resume") "control.pausedManualStartResume" else "control.pausedManualStart"
        ends == "next_plug_in" -> "control.pausedManualStopNextPlugIn"
        ends == "resume" -> "control.pausedManualStopResume"
        else -> "control.pausedManualStop"
    }

    /** One line's sentence or fact, with its params formatted here. */
    fun line(line: StatusLine, format: StatusFormat, now: Instant): String {
        val language = format.language
        fun say(key: String, params: Map<String, String> = emptyMap()): String {
            var text = HaStatusWording.text(language, key) ?: return HaStatusWording.text(language, HaStatusWording.UNKNOWN_CODE).orEmpty()
            for ((name, value) in params) text = text.replace("{$name}", value)
            return text
        }
        val key = HaStatusWording.CODE_KEYS[line.code] ?: return say(HaStatusWording.UNKNOWN_CODE)
        val zone = format.zone
        val p = line.params
        fun clock(instant: Instant) = DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone!!))
        fun moment(instant: Instant): String {
            val zoned = instant.atZone(zone!!)
            if (zoned.toLocalDate() == now.atZone(zone).toLocalDate()) return clock(instant)
            val day = DateTimeFormatter.ofPattern("EEE d MMM", format.locale).format(zoned)
            return "$day ${clock(instant)}"
        }
        return when (line.code) {
            "paused" -> {
                val until = instant(p["until"])
                when {
                    // A person's Start or Stop: what they did and what ends it, never a clock.
                    p["choice"] == "manual" -> say(manualPauseKey(p["action"], p["ends"]))
                    until == null -> say("control.pausedIndefinitely")
                    zone == null -> say("status.pausedShort")
                    else -> say("control.pausedUntil", mapOf("time" to moment(until)))
                }
            }
            "charging_now" -> {
                val until = instant(p["until"])
                if (until == null || zone == null) say("status.chargingNowOpen")
                else say("status.chargingNow", mapOf("time" to clock(until)))
            }
            "topping_off" -> {
                val until = instant(p["until"])
                if (until == null || zone == null) say("status.toppingOffOpen")
                else say("status.toppingOff", mapOf("time" to clock(until)))
            }
            "waiting_for_publication" -> {
                val at = instant(p["publication_at"])
                if (at == null || zone == null) say("status.waitingForPublicationNoTime")
                else say("status.waitingForPublication", mapOf("time" to clock(at)))
            }
            "waiting_for_history" -> {
                // The weekday's plural, the saving and the weeks behind it; without all three, only
                // the plain fact.
                val weekday = weekdayPlural(language, num(p["weekday"]))
                val percent = num(p["percent"])
                val weeks = num(p["weeks"])
                if (weekday == null || percent == null || weeks == null) say("status.waitingForHistoryNoDetail")
                else say(key, mapOf(
                    "weekday" to weekday,
                    "percent" to number(format.locale, percent, 0),
                    "weeks" to number(format.locale, weeks, 0)
                ))
            }
            "held_until_window" -> {
                val at = instant(p["time"])
                if (at == null || zone == null) say("status.scheduledNoTime")
                else say(key, mapOf("time" to clock(at)))
            }
            "buying_before_publication" ->
                say(key, mapOf("kwh" to number(format.locale, num(p["kwh"]) ?: 0.0, 1)))
            "auto_planned", "auto_installed" -> {
                val start = instant(p["start"])
                if (start == null || zone == null) say("status.scheduledNoTime")
                else say(key, mapOf("time" to clock(start)))
            }
            "plan_energy" -> say(key, mapOf("kwh" to number(format.locale, num(p["kwh"]) ?: 0.0, 1)))
            "plan_cost" -> {
                val major = (num(p["amount_minor"]) ?: 0.0) / 100.0
                val currency = p["currency"] as? String ?: ""
                // The market's own major unit when the amount is in the market's currency, else the
                // code.
                val unit = if (currency == format.currency && format.majorUnit != null) format.majorUnit else currency
                // The pound is written as the locale writes money ("£1.33"); every other unit as before.
                val cost = if (currency == "GBP") MoneyText.amount(major, currency, unit, format.locale)
                    else "${number(format.locale, major, 2)} $unit".trim()
                say(key, mapOf("cost" to cost))
            }
            "plan_distance" -> {
                // Home Assistant states mil; the unit follows the app's language (km elsewhere).
                val mil = num(p["mil"]) ?: 0.0
                say(key, mapOf("distance" to when {
                    format.miles -> "${number(format.locale, mil * 10 / DistanceUnit.KM_PER_MILE, 0)} mi"
                    DistanceUnit.usesMil(language) -> "${number(format.locale, mil, 1)} mil"
                    else -> "${number(format.locale, mil * 10, 0)} km"
                }))
            }
            "solar_car_stopped" -> {
                val at = instant(p["time"])
                if (at == null || zone == null) say("strategy.status.solar.carStoppedNoTime")
                else say(key, mapOf("time" to clock(at)))
            }
            "solar_charging" -> {
                val amps = num(p["requested_a"])
                if (amps == null) say("strategy.status.solar.chargingUnknown")
                else say(key, mapOf("amps" to number(format.locale, amps, 0)))
            }
            "hybrid_grid" -> {
                val start = instant(p["window_start"])
                val end = instant(p["window_end"])
                val window = if (start != null && end != null && zone != null) " ${clock(start)}–${clock(end)}" else ""
                val grid = say(key, mapOf("grid" to number(format.locale, num(p["grid_kwh"]) ?: 0.0, 1), "window" to window))
                val credit = num(p["credit_kwh"])
                if (credit != null && credit > 0) {
                    grid + say("strategy.status.hybrid.creditSuffix", mapOf("credit" to number(format.locale, credit, 1)))
                } else grid
            }
            "load_balancing_limited" -> {
                val limit = num(p["limit_a"])
                // The cause names who shares the fuse; an unknown (or absent) cause is the plain sentence.
                val wording = when (p["cause"]) {
                    "battery_shares_fuse" -> "status.loadBalancingLimitedByBattery"
                    "house_consumption" -> "status.loadBalancingLimitedByHouse"
                    else -> key
                }
                if (limit == null) say("status.loadBalancingLimited")
                else say(wording, mapOf("limit" to number(format.locale, limit, 0)))
            }
            "proposal_pending" -> {
                // When the new plan takes over, if the current charging window decides it.
                val at = instant(p["installs_at"])
                if (at == null || zone == null) say(key)
                else say("status.proposalPendingAt", mapOf("time" to moment(at)))
            }
            "settings_incomplete" -> {
                // Setup, not a fault: name what is still needed when every field is a known word.
                val missing = (p["missing"] as? List<*>) ?: emptyList<Any?>()
                val names = missing.map { (it as? String)?.let { field -> MISSING_FIELDS[field] } }
                when {
                    names.isEmpty() || names.any { it == null } -> say(key)
                    missing.size == 1 && missing[0] == "area" -> say("status.finishSetupArea")
                    else -> say("status.finishSetup", mapOf("fields" to names.joinToString(", ") { say(it!!) }))
                }
            }
            "target_reached" -> {
                // The percent is shown rounded (the comparison that stopped the charge never was).
                val soc = number(format.locale, num(p["soc_percent"]) ?: 0.0, 0)
                val estimated = p["basis"] == "estimate"
                val ageS = num(p["reading_age_s"])
                val age = when {
                    ageS == null || ageS < 60 -> null
                    ageS < 3600 -> say("status.targetAgeMinutes", mapOf("n" to number(format.locale, Math.floor(ageS / 60), 0)))
                    else -> say("status.targetAgeHours", mapOf("n" to number(format.locale, Math.floor(ageS / 3600), 0)))
                }
                if (age == null) {
                    when {
                        estimated -> say("status.targetStoppedEstimate", mapOf("soc" to soc))
                        ageS == null -> say("status.targetStopped", mapOf("soc" to soc))
                        else -> say("status.targetStoppedNow", mapOf("soc" to soc))
                    }
                } else {
                    say(
                        if (estimated) "status.targetStoppedEstimateAge" else "status.targetStoppedAge",
                        mapOf("soc" to soc, "age" to age)
                    )
                }
            }
            "site_measurement_problem" -> measurementText(
                format,
                strings(p["no_value_phases"]),
                strings(p["no_value_entities"]),
                strings(p["stale_phases"]),
                num(p["max_age_s"])
            )
            "duplicate_charger" -> say(key, mapOf("other" to (p["other"] as? String ?: "")))
            // Why solar has no full basis: the entity concerned where one is named (the card's `basisWording`).
            "solar_no_grid_power" -> entity(p)?.let { say("strategy.status.solar.noGridPowerEntity", mapOf("entity" to it)) }
                ?: say(key)
            "solar_battery_unreadable" -> say(key, mapOf("entity" to (entity(p) ?: "")))
            "solar_charger_current_missing" ->
                entity(p)?.let { say("strategy.status.solar.chargerCurrentUnreadable", mapOf("entity" to it)) }
                    ?: say(key)
            "solar_site_incomplete" -> say(key, mapOf("phases" to strings(p["phases"]).joinToString(", ")))
            // A charger that cannot say when a car is plugged in: only a Start or a plan window ends the Stop.
            "site_meter_unavailable" -> say(
                if (p["cause"] == "inverter_standby") "status.meterUnavailable.inverter" else key,
                mapOf("entities" to meterNames(p).joinToString(", "))
            )
            // A signed meter read as unsigned: the phases that read negative, and the setting that fixes it.
            "site_current_negative" -> say(key, mapOf("phases" to joinPhases(format.language, strings(p["phases"]))))
            "need_limited_by_room" -> say(key, mapOf("kwh" to number(format.locale, num(p["kwh"]) ?: 0.0, 1)))
            "charging_to_vehicle_limit" -> say(key, mapOf("percent" to number(format.locale, num(p["percent"]) ?: 100.0, 0)))
            "remaining_need_estimated" -> say(
                // Counted from the recorded charges, or kept from the meter's last reading.
                if (p["basis"] == "sessions") "issue.needFromSessions" else key,
                mapOf("kwh" to number(format.locale, num(p["kwh"]) ?: 0.0, 1))
            )
            else -> say(key)
        }
    }

    /**
     * The card's `measurementProblemText`: the phases with no value (and the entities they are read
     * from), then the phases older than the maximum age; the general sentence when there is neither.
     */
    internal fun measurementText(
        format: StatusFormat,
        noValuePhases: List<String>,
        noValueEntities: List<String>,
        stalePhases: List<String>,
        maxAgeS: Double?
    ): String {
        val language = format.language
        fun say(key: String, params: Map<String, String>): String {
            var text = HaStatusWording.text(language, key).orEmpty()
            for ((name, value) in params) text = text.replace("{$name}", value)
            return text
        }
        fun form(count: Int) = if (count == 1) "one" else "other"
        val parts = mutableListOf<String>()
        if (noValuePhases.isNotEmpty()) {
            val where = if (noValueEntities.isNotEmpty()) " (${noValueEntities.joinToString(", ")})" else ""
            parts += say(
                "status.siteMeasurement.noValue.${form(noValuePhases.size)}",
                mapOf("phases" to joinPhases(language, noValuePhases), "where" to where)
            )
        }
        if (stalePhases.isNotEmpty()) {
            parts += say(
                "status.siteMeasurement.stale.${form(stalePhases.size)}",
                mapOf(
                    "phases" to joinPhases(language, stalePhases),
                    "seconds" to number(format.locale, maxAgeS ?: 0.0, 0)
                )
            )
        }
        return if (parts.isEmpty()) say("issue.siteMeasurement", emptyMap()) else parts.joinToString(" ")
    }

    private fun strings(value: Any?): List<String> = (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    /** The entity a line names: its friendly name (`entity_name`) when given, else its id. */
    private fun entity(params: Map<String, Any?>): String? =
        (params["entity_name"] as? String)?.takeIf { it.isNotEmpty() }
            ?: (params["entity"] as? String)?.takeIf { it.isNotEmpty() }

    /** The meter's sensors by friendly name where `entity_names` runs parallel to `entities`. */
    private fun meterNames(params: Map<String, Any?>): List<String> {
        val ids = strings(params["entities"])
        val names = strings(params["entity_names"])
        return if (names.size == ids.size) names.mapIndexed { i, name -> name.ifEmpty { ids[i] } } else ids
    }

    /** The card's long "and" list (`Intl.ListFormat`): "L1 and L2", "L1, L2, and L3" (no serial comma outside English). */
    private fun joinPhases(language: String, items: List<String>): String {
        val and = when (language) {
            "sv" -> "och"
            "da", "nb" -> "og"
            "fi" -> "ja"
            else -> "and"
        }
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            2 -> "${items[0]} $and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + (if (language == "en") ", " else " ") + "$and ${items.last()}"
        }
    }

    /**
     * A weekday in the plural the language uses for "every Sunday" (`Sundays`, `söndagar`,
     * `søndager`, `søndage`, `sunnuntaisin`), or `null` for a number that is no ISO weekday.
     */
    internal fun weekdayPlural(language: String, isoWeekday: Double?): String? {
        val number = isoWeekday?.takeIf { it == Math.floor(it) && it >= 1 && it <= 7 }?.toInt() ?: return null
        val locale = if (language == "en") Locale.UK else Locale.forLanguageTag(language)
        val name = DayOfWeek.of(number).getDisplayName(TextStyle.FULL_STANDALONE, locale).lowercase(locale)
        return when (language) {
            "sv" -> "${name}ar"
            "nb" -> "${name}er"
            "da" -> "${name}e"
            "fi" -> if (name.endsWith("i")) "${name}sin" else "${name}isin"
            else -> name.replaceFirstChar { it.titlecase(locale) } + "s"
        }
    }

    /** [age] milliseconds as the card's own short age: minutes under an hour, whole hours after. */
    fun ageText(language: String, ageMs: Long): String {
        val minutes = Math.max(0L, ageMs / 60_000L)
        val (key, n) = if (minutes < 60) "status.targetAgeMinutes" to minutes else "status.targetAgeHours" to minutes / 60
        return (HaStatusWording.text(language, key) ?: "{n}").replace("{n}", n.toString())
    }

    /** The staleness note: "updated 45 min ago". */
    fun staleSuffix(language: String, ageMs: Long): String =
        (HaStatusWording.text(language, HaStatusWording.STALE_SUFFIX) ?: "").replace("{age}", ageText(language, ageMs))

    private fun instant(value: Any?): Instant? =
        (value as? String)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun num(value: Any?): Double? = (value as? Double)?.takeIf { it.isFinite() }

    private fun number(locale: Locale, value: Double, digits: Int): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = digits
            // The card's (Intl) rounding: halves go up, so 17.25 is 17.3 here as there.
            roundingMode = java.math.RoundingMode.HALF_UP
        }.format(value)
}
