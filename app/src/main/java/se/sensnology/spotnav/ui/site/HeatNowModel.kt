package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatSteering
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ha.house.House
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.min

/** One hour of the strip under the dial: what the plan does in it, and whether hot water runs. */
internal data class AheadCell(val action: String, val hotWater: Boolean)

/**
 * The heat page's Now tab as the Home Assistant card words it (`frontend/src/heat-view.ts`): the header's status, the
 * dial's figures, the lines under it, the plan's strip and today's figures. Pure: the views draw what this says.
 */
internal object HeatNowModel {
    const val TARGET_MIN = 16.0
    const val TARGET_MAX = 25.0
    const val TARGET_STEP = 0.5
    const val AHEAD_HOURS = 24
    private const val NBSP = " "
    private val AHEAD_ACTIONS = listOf("lower", "preheat", "night", "away")
    private val PUMP_STATES = setOf("heating", "hot_water", "idle")
    private val ROLES = setOf(
        "outdoor_temp", "supply_temp", "return_temp", "room_temp", "hot_water_temp", "degree_minutes",
        "compressor_speed", "compressor_state", "immersion_power", "immersion_state", "priority"
    )
    private val OTHER_CONTROLLERS = mapOf("effektguard" to "EffektGuard")

    /** The dial's figure one step on from [base], kept in range and on half degrees. */
    fun stepped(base: Double, delta: Double): Double =
        (Math.round((base + delta) * 2) / 2.0).coerceIn(TARGET_MIN, TARGET_MAX)

    /** A share of the dial's range (0 to 1) for [value]. */
    fun share(value: Double): Double = ((value - TARGET_MIN) / (TARGET_MAX - TARGET_MIN)).coerceIn(0.0, 1.0)

    fun degrees(text: SiteText, value: Double): String = "${text.fixed(value, 1)} °C"

    private fun tight(value: String) = value.replace(" ", NBSP)

    /** SpotNav's own heat control runs (else the page says Test). */
    fun controls(heat: Heat): Boolean = heat.control?.let { it.enabled && it.state == "running" } == true

    private fun moment(text: SiteText, heat: Heat, at: Instant): String = moment(text, heat.now, heat.zone, at)

    /** A time as the card's `whenLabel` says it: the clock on [now]'s day, the day before it otherwise. */
    fun moment(text: SiteText, now: Instant, zone: java.time.ZoneId, at: Instant): String {
        val clock = text.clock(at, zone)
        val day = at.atZone(zone).toLocalDate()
        return if (day == now.atZone(zone).toLocalDate()) clock else "${DAY.withLocale(text.locale).format(at.atZone(zone))} $clock"
    }

    /**
     * What the heating does now, as the header says it after the name: while SpotNav's control holds the heat down or
     * up, that and until when ("Sänkt till 06:00"), else what the pump does ("Värmer 2,1 kW"); `null` when unknown.
     * [code] names it for tests.
     */
    fun live(text: SiteText, heat: Heat): Pair<String, String>? {
        val words = text.words
        val control = heat.control
        if (control != null && control.enabled && control.state == "running") {
            if (control.raised) {
                val until = control.raiseUntil
                return "raise" to if (until == null) words.say("heat.now.raised") else words.say("heat.now.raisedUntil", "until" to moment(text, heat, until))
            }
            if (control.lowered) {
                val until = control.until
                return "lower" to if (until == null) words.say("heat.now.lowered") else words.say("heat.now.loweredUntil", "until" to moment(text, heat, until))
            }
        }
        val state = heat.status.pumpState?.takeIf { it in PUMP_STATES } ?: return null
        val power = heat.status.kw
        val said = words.say("heat.now.$state")
        return state to if (state == "idle" || power == null) said else "$said ${text.fixed(power, 1)}${NBSP}kW"
    }

    /** The header's status after the name: "Test" while SpotNav controls nothing, then what the heating does. */
    fun statusAfterName(text: SiteText, heat: Heat): String? =
        listOfNotNull(if (controls(heat)) null else text.words.say("header.test"), live(text, heat)?.second)
            .joinToString(" · ").ifEmpty { null }

    /** "Inne 20,4 °C (kallast: Toalett NV)", with the outdoor reading after it. */
    /**
     * Whether SpotNav can only read this heating (the card's `!heatSteers(config)`): no path it steers and its steering
     * off. The target is then shown without − and +, Home/Away is left out, and [readOnlyLine] takes the Test line's place.
     */
    fun readOnly(heat: Heat): Boolean = !HeatSteering.steers(heat.config.controlPath, heat.config.controlEnabled)

    /** The one line under a read-only dial; an administrator's answer names where a control path is chosen. */
    fun readOnlyLine(words: CardWords, heat: Heat): String = words.say(if (heat.writable) "heat.readOnly.admin" else "heat.readOnly")

    fun measuredLine(text: SiteText, heat: Heat): String {
        val words = text.words
        val parts = mutableListOf<String>()
        val value = heat.indoor.valueC
        if (value == null) {
            parts += words.say("heat.noSensor")
        } else {
            val room = roomName(heat.indoor.coldestName ?: heat.indoor.coldest ?: "")
            parts += words.say(
                if (heat.indoor.sensorsRead > 1) "heat.measuredColdest" else "heat.measured",
                "value" to tight(degrees(text, value)), "room" to room
            )
        }
        heat.outdoorC?.let { parts += words.say("heat.outdoor", "value" to tight(degrees(text, it))) }
        return parts.joinToString(" · ")
    }

    /** A room's name without the sensor's trailing "temperature" word. */
    fun roomName(name: String): String =
        name.replace(Regex("\\s+(temperatur|temperature|température|temperatura|lämpötila|temperatuur)$", RegexOption.IGNORE_CASE), "")
            .trim().ifEmpty { name }

    /** The dial's small line: the band the target keeps. */
    fun bandLine(text: SiteText, target: Double): String =
        text.words.say("heat.dial.band", "low" to text.fixed(target - 0.5, 1), "high" to text.fixed(target + 0.5, 1))

    /** What the heat pump does now, by its own facts or its meter. */
    fun pumpLine(text: SiteText, heat: Heat): String? {
        val state = heat.status.pumpState
        val power = heat.status.kw
        if (state == null) return if (!heat.hasMeter && !heat.hasPump) null else text.words.say("heat.pump.unknown")
        val key = if (state in PUMP_STATES) "heat.pump.$state" else "heat.pump.unknown"
        return text.words.say(key, "kw" to (power?.let { "${text.fixed(it, 1)} kW" } ?: "–")).replace(Regex(" · –$"), "")
    }

    private val SG_READY_PATHS = setOf("sg_ready", "sg_ready_api")

    /**
     * Whether a pre-heat is a raise of the pump (the strip and the plan's words): through SG Ready's second contact, the
     * SG Ready API's low-price mode (which needs its activation switch), or a curve offset above the value found.
     */
    fun raisable(heat: Heat): Boolean {
        val config = heat.config
        if (config.controlEntity == null) return false
        return config.controlPath == "offset" || (config.controlPath in SG_READY_PATHS && config.controlEntityB != null)
    }

    /** Whether "would pre-heat" is said as SG Ready's raise: on SG Ready with both contacts, or through its API (an offset keeps the pre-heat's words). */
    private fun sgRaise(heat: Heat): Boolean =
        heat.config.controlPath in SG_READY_PATHS && heat.config.controlEntity != null && heat.config.controlEntityB != null

    /** What SpotNav would do now, in shadow. */
    fun wouldLine(text: SiteText, heat: Heat): String? {
        val would = heat.status.would
        val guard = heat.status.guard
        val words = text.words
        if (guard != null && (would == null || would.action == "normal")) {
            return if (guard in GUARDS) words.say("heat.guard.$guard") else null
        }
        if (would == null || would.action !in WOULD_ACTIONS) return null
        val key = when {
            would.action == "lower" -> when (would.reason) {
                "tight_hour" -> "heat.would.lowerTight"
                "room_warm" -> "heat.would.lowerWarm"
                else -> "heat.would.lower"
            }
            would.action == "preheat" && sgRaise(heat) -> "heat.would.raise"
            else -> "heat.would.${would.action}"
        }
        return words.say(key, "temp" to degrees(text, would.targetC), "until" to (would.until?.let { moment(text, heat, it) } ?: ""))
    }

    private fun runEnd(hours: List<Heat.RelayHour>, index: Int): Instant {
        fun end(hour: Heat.RelayHour) = hour.start.plusSeconds(((hour.relayMin ?: 0.0) * 60).toLong())
        var end = end(hours[index])
        var next = index + 1
        while (next < hours.size && (hours[next].relayMin ?: 0.0) > 0) {
            end = end(hours[next]); next++
        }
        return end
    }

    /** What the pump's relay does, whoever closes it, or when it is expected to lower next; `null` without a relay. */
    fun relayLine(text: SiteText, heat: Heat, now: Instant): String? {
        val relay = heat.relay ?: return null
        val hours = heat.relayHours
        val words = text.words
        val current = hours.indexOfFirst { !it.start.isAfter(now) && now.isBefore(it.start.plus(Duration.ofHours(1))) }
        if (relay.closed == true) {
            val since = relay.since?.let { moment(text, heat, it) } ?: ""
            val ahead = if (current >= 0 && hours[current].relayExpected) runEnd(hours, current) else null
            if (ahead != null && ahead.isAfter(now)) {
                return words.say("heat.relay.closedUntil", "since" to since, "until" to moment(text, heat, ahead))
            }
            return words.say("heat.relay.closed", "since" to since)
        }
        val next = hours.indices.firstOrNull { it > current && hours[it].relayExpected && (hours[it].relayMin ?: 0.0) > 0 }
        if (next == null || current < 0) return null
        val first = hours[next]
        val from = first.start.plusSeconds((60 - min(60.0, first.relayMin ?: 0.0)).toLong() * 60)
        return words.say("heat.relay.next", "from" to moment(text, heat, from), "to" to moment(text, heat, runEnd(hours, next)))
    }

    /** The plan's next 24 hours in words: the first run of each thing it does; `null` without hours ahead. */
    fun aheadLine(text: SiteText, heat: Heat): String? {
        val hours = heat.ahead
        if (hours.isEmpty()) return null
        val words = text.words
        val parts = mutableListOf<Pair<Instant, String>>()
        for (action in AHEAD_ACTIONS) {
            val first = hours.indexOfFirst { it.action == action }
            if (first < 0) continue
            var last = first
            while (last + 1 < hours.size && hours[last + 1].action == action) last++
            val key = if (action == "preheat" && raisable(heat)) "raise" else action
            parts += hours[first].start to words.say(
                "heat.ahead.$key",
                "from" to text.clock(hours[first].start, heat.zone),
                "to" to text.clock(hours[last].start.plus(Duration.ofHours(1)), heat.zone)
            )
        }
        // Hot water at its planned boost's own time where there is one ("varmvatten 04:15"), else at its hour.
        val last = hours.last().start.plus(Duration.ofHours(1))
        val planned = heat.hotWater?.planned.orEmpty().map { it.start }.filter { !it.isBefore(heat.now) && it.isBefore(last) }.minOrNull()
        (planned ?: hours.firstOrNull { it.hotWater }?.start)?.let {
            parts += it to words.say("heat.ahead.water", "time" to text.clock(it, heat.zone))
        }
        if (parts.isEmpty()) return words.say("heat.ahead.none")
        return words.say("heat.ahead.lead", "parts" to parts.sortedBy { it.first }.joinToString(" · ") { it.second })
    }

    /** The strip's 24 cells; the hours the plan does not reach yet are `null` (open). */
    fun aheadCells(heat: Heat): List<AheadCell?> {
        val cells = heat.ahead.take(AHEAD_HOURS).map {
            AheadCell(if (it.action == "preheat" && raisable(heat)) "raise" else it.action, it.hotWater)
        }
        return cells + List(AHEAD_HOURS - cells.size) { null }
    }

    /** The strip's clock under every third hour. */
    fun aheadAxis(text: SiteText, heat: Heat): List<String> {
        val first = heat.ahead.firstOrNull()?.start ?: return emptyList()
        return (0 until AHEAD_HOURS step 3).map { text.clock(first.plus(Duration.ofHours(it.toLong())), heat.zone) }
    }

    /** Money in the minor unit as the card says it everywhere. */
    fun amount(text: SiteText, minor: Double, money: House.Money?): String = text.amount(minor, money)

    /** "I dag · värme 13,9 kWh · varav zoner 0,8 kWh · varmvatten 1,7 kWh · 19 kr"; `null` without today. */
    fun todayLine(text: SiteText, heat: Heat): String? {
        val today = heat.today ?: return null
        val words = text.words
        fun kwh(v: Double) = "${text.fixed(v, 1)} kWh"
        val parts = mutableListOf(words.say("heat.today.label"), words.say("heat.today.heat", "kwh" to kwh(today.heatKwh)))
        today.zonesKwh?.takeIf { it > 0 }?.let { parts += words.say("heat.today.zones", "kwh" to kwh(it)) }
        today.hotWaterKwh?.let { parts += words.say("heat.today.water", "kwh" to kwh(it)) }
        today.cost?.let { parts += amount(text, it, today.money) }
        return parts.joinToString(" · ")
    }

    /** One sentence per note the household can act on; a code not known is left out. */
    fun noteLines(text: SiteText, heat: Heat): List<Pair<String, String>> {
        val words = text.words
        val out = mutableListOf<Pair<String, String>>()
        for (note in heat.notes) {
            val params = note.params
            when (note.code) {
                "pump_price_control" -> {
                    val where = (params["where"] as? String)?.takeIf { it in listOf("nibe_smart_price", "ctc_smartgrid") }
                        ?.let { words.say("heat.where.$it") } ?: ""
                    val said = words.say("heat.note.pump_price_control", "label" to (params["label"]?.toString() ?: ""), "where" to where)
                    out += note.code to if (where.isEmpty()) said.replace(" ()", "") else said
                }
                "readings_disabled" -> {
                    val names = (params["entities"] as? List<*>).orEmpty().joinToString(", ") {
                        if (it is String && it in ROLES) words.say("heat.role.$it") else it.toString()
                    }
                    out += note.code to words.say("heat.note.readings_disabled", "names" to names)
                }
                "other_controller" -> {
                    val names = (params["names"] as? List<*>).orEmpty().joinToString(", ") { OTHER_CONTROLLERS[it.toString()] ?: it.toString() }
                    out += note.code to words.say("heat.note.other_controller", "names" to names)
                }
                "automation_steers" -> {
                    val key = when {
                        params["blocks"] == true -> "heat.note.automation_blocks"
                        params["relay"] == true -> "heat.note.automation_relay"
                        else -> "heat.note.automation_zone"
                    }
                    val targets = (params["targets"] as? List<*>).orEmpty().joinToString(", ") { it.toString() }
                    out += note.code to words.say(key, "name" to (params["name"]?.toString() ?: ""), "targets" to targets)
                }
                "water_heater_not_full" -> out += note.code to words.say("heat.note.water_heater_not_full")
            }
        }
        return out
    }

    /** The things SpotNav holds beside the pump now, and the water heater's next windows, under the status lines (the card's `holdLines`). */
    fun holdLines(text: SiteText, heat: Heat): List<Pair<String, String>> =
        HeatHoldText.of(text, heat).holdLines(heat.zoneList, heat.waterHeater)

    /** "Borta till fre 20 16:00": the return time of an away, for the line under the buttons. */
    fun awayLine(text: SiteText, heat: Heat): String? {
        val until = heat.comfort.awayUntil ?: return null
        if (heat.comfort.mode != "away") return null
        return text.words.say("heat.mode.awayUntil", "when" to moment(text, heat, until))
    }

    /** The Boost button's words now: the line beside it, the button's label and what pressing does (`null`: no button). */
    data class BoostWords(val text: String?, val action: String?, val label: String?)

    /** The Boost button's state in words (the card's `boostWords`); `null` where the pump has no self-ending boost. */
    fun boostWords(text: SiteText, heat: Heat, now: Instant): BoostWords? {
        val boost = heat.hotWater?.boost ?: return null
        val words = text.words
        val left = boost.ends?.let { maxOf(1L, Math.ceil((it.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toLong()) }
        return when (boost.state) {
            "starting" -> BoostWords(null, null, words.say("heat.boost.starting"))
            "running" -> if (boost.source == "user") {
                val line = if (left == null) words.say("heat.boost.on")
                else words.say(if (boost.endsEstimated) "heat.boost.left" else "heat.boost.leftExact", "minutes" to left.toString())
                if (boost.stoppable) BoostWords(line, "stop", words.say("heat.boost.stop")) else BoostWords(line, null, null)
            } else {
                BoostWords(if (left == null) words.say("heat.boost.heatingOn") else words.say("heat.boost.heating", "minutes" to left.toString()), null, null)
            }
            "failed" -> BoostWords(words.say("heat.boost.failed"), "start", words.say("heat.boost.start"))
            else -> BoostWords(null, "start", words.say("heat.boost.start"))
        }
    }

    /** A refused press of the Boost button in words (the card's `boostErrorKey`); `null` when it went through. */
    fun boostError(text: SiteText, outcome: se.sensnology.spotnav.ha.heat.HeatBoostWrite.Outcome): String? = when (outcome) {
        is se.sensnology.spotnav.ha.heat.HeatBoostWrite.Outcome.Done -> null
        is se.sensnology.spotnav.ha.heat.HeatBoostWrite.Outcome.Refused -> text.words.say(
            when (outcome.code) {
                se.sensnology.spotnav.ha.heat.HeatBoostWrite.RATE_LIMITED -> "heat.boost.rateLimited"
                se.sensnology.spotnav.ha.heat.HeatBoostWrite.NO_HEADROOM -> "heat.boost.noHeadroom"
                else -> "heat.boost.failed"
            }
        )
        is se.sensnology.spotnav.ha.heat.HeatBoostWrite.Outcome.Unsupported -> text.words.say("heat.error.generic")
        is se.sensnology.spotnav.ha.heat.HeatBoostWrite.Outcome.Failed -> text.words.say("heat.boost.failed")
    }

    /** A refused write in words (the card's `writeError`). */
    fun errorWords(text: SiteText, outcome: se.sensnology.spotnav.ha.heat.HeatWrite.Outcome): String {
        val words = text.words
        return when (outcome) {
            is se.sensnology.spotnav.ha.heat.HeatWrite.Outcome.Conflict -> words.say("heat.error.conflict")
            is se.sensnology.spotnav.ha.heat.HeatWrite.Outcome.NotPermitted -> words.say("heat.error.notAdmin")
            is se.sensnology.spotnav.ha.heat.HeatWrite.Outcome.Refused -> {
                val byCode = mapOf("out_of_range" to "heat.error.range", "not_a_step" to "heat.error.step", "away_in_past" to "heat.error.past", "away_too_far" to "heat.error.far")
                words.say(outcome.codes.firstNotNullOfOrNull { byCode[it] } ?: "heat.error.generic")
            }
            else -> words.say("heat.error.generic")
        }
    }

    private val GUARDS = setOf("no_prices", "too_cold", "room_cold")
    private val WOULD_ACTIONS = setOf("lower", "preheat", "night", "away", "return", "normal")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
}
