package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseNow
import se.sensnology.spotnav.ui.common.CardWords
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** A part around the house in the Now schematic. [key] is the card's word for it. */
internal enum class NodeRole(val key: String) { SUN("sun"), GRID("grid"), BATTERY("battery"), CAR("car"), HEAT("heat") }

/** Which way the power runs on a line: towards the house, away from it, or not at all. */
internal enum class Way(val key: String) { IN("in"), OUT("out"), STILL("still") }

/** What a figure or a line's dots are coloured as: a part's own colour, an import or an export. */
internal enum class Tone { SUN, BATTERY, CAR, HEAT, GRID, IMPORT, EXPORT, HOUSE }

/** One line of the schematic: its power (kW, never negative; `null` unread), its way and its colour. */
internal data class SchematicLine(val role: NodeRole, val kw: Double?, val way: Way, val tone: Tone)

/** Which parts the site has. */
internal data class Present(val sun: Boolean, val battery: Boolean, val car: Boolean, val heat: Boolean) {
    /** A house with the grid alone: drawn as one low row. */
    val alone: Boolean get() = !sun && !battery && !car && !heat
}

/** One corner's words: a small name over a coloured figure, and a smaller figure beside or under it. */
internal data class CornerWords(
    /** `null` is the house itself. */
    val role: NodeRole?,
    val name: String,
    val value: String,
    val tone: Tone,
    val beside: String? = null,
    val besideTone: Tone? = null,
    val under: String? = null,
    val underTone: Tone? = null
)

/** The Now schematic, ready to draw: the parts, the lines with their kW figures, the corners and the picture in words. */
internal data class Schematic(
    val present: Present,
    val lines: List<SchematicLine>,
    /** Each flowing line's figure ("0,4 kW"). */
    val labels: Map<NodeRole, String>,
    val corners: List<CornerWords>,
    /** The house's own power, inside its disc; `null` unread and in the low row. */
    val houseKw: String?,
    /** The car's node is dimmed while no car is plugged in. */
    val carDimmed: Boolean,
    val description: String
)

/** What the battery's plan does from an hour on: an action, or its own self-consumption (`self`). */
internal data class PlanRun(val act: String, val start: Instant)

/** What the house page's header says after the name. */
internal sealed interface HouseStatus {
    data class Said(val code: String, val text: String) : HouseStatus

    /** SpotNav controls nothing yet: "Test", with its explanation. */
    data object Test : HouseStatus
}

/**
 * The words and figures in the reader's language: the card's words ([CardWords]), day names in [locale], and numbers
 * as the rest of the screen writes them.
 */
internal class SiteText(val words: CardWords, val locale: Locale, private val numbers: Locale = locale) {
    fun fixed(value: Double, digits: Int): String = String.format(numbers, "%.${digits}f", value)

    /** At most one decimal, none for a whole figure ("1,4", "2"). */
    fun short(value: Double): String = fixed(value, 1).let { if (it.endsWith("0")) fixed(value, 0) else it }

    fun kw(kw: Double): String = "${fixed(kw, if (kw >= 10) 0 else 1)} kW"

    fun kwh(kwh: Double): String = "${bare(kwh)} kWh"

    /** A figure without its unit ("0,1"). */
    fun bare(kwh: Double): String = fixed(kwh, if (kwh >= 100) 0 else 1)

    fun percent(value: Double): String = "${fixed(value, 0)} %"

    /** Money given in the minor unit, shown in the major one: whole units from 10 up and for nothing, else one decimal. */
    fun amount(minor: Double, money: House.Money?): String {
        val major = minor / 100.0
        val unit = money?.majorUnit
        if (unit == null) {
            val small = money?.minorUnit?.takeIf { it.isNotEmpty() }
            return fixed(minor, 0) + (small?.let { " $it" } ?: "")
        }
        return "${fixed(major, if (abs(major) >= 10 || major == 0.0) 0 else 1)} $unit"
    }

    fun clock(at: Instant, zone: ZoneId): String = CLOCK.format(at.atZone(zone))

    /** The day as a short cue ("lör 14"). */
    fun dayCue(at: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("EEE d", locale).format(at.atZone(zone))

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * The house page's Now tab, as the Home Assistant card's Now tab has it (`frontend/src/house-now.ts` and
 * `house-view.ts`): the schematic's lines and corner figures from the live flows and the day's energy flow, the
 * sentence of what the battery does and does next, today's money, and the small notes. Pure: the view draws what
 * this says.
 */
internal object HouseNowModel {
    /** Power below this (kW, either way) is drawn as no flow. */
    const val STILL_KW = 0.05

    /** Measured battery power below this (kW, either way) is the battery at rest. */
    private const val REST_KW = 0.1

    private const val HOUR_SECONDS = 3600L

    /** The battery brands whose own optimiser has a name. */
    private val OPTIMISERS = mapOf("sigenergy" to "Sigenergy AI")

    private val MODES = setOf("self_consumption", "balanced", "max_profit")

    /** Whether a car is plugged in: the live answer's word; unknown (no line drawn) without it. */
    fun carPlugged(live: HouseNow?): Boolean = live?.carConnected == true

    /** Which nodes the site has: the live answer's word, else what the day's energy flow and the house show. */
    fun present(live: HouseNow?, house: House): Present {
        if (live != null) return Present(live.has.sun, live.has.battery, live.has.car, live.has.heat)
        val roles = house.energyFlow?.nodes.orEmpty().map { it.role }.toSet()
        return Present(
            sun = house.sun != null || "solar" in roles,
            battery = house.battery != null,
            car = "car" in roles,
            heat = house.heat?.status == "found" || "heat" in roles
        )
    }

    /**
     * Each line of the schematic from the live figures (`null`: the day's figures only, with the battery's own
     * power). The car has a line only while a car is plugged in.
     */
    fun lines(live: HouseNow?, house: House): List<SchematicLine> {
        val has = present(live, house)
        fun line(role: NodeRole, signed: Double?, inPositive: Boolean): SchematicLine {
            if (signed == null) return SchematicLine(role, null, Way.STILL, tone(role))
            val kw = abs(signed)
            val way = if (kw < STILL_KW) Way.STILL else if ((signed > 0) == inPositive) Way.IN else Way.OUT
            val tone = if (role == NodeRole.GRID) (if (way == Way.OUT) Tone.EXPORT else Tone.IMPORT) else tone(role)
            return SchematicLine(role, kw, way, tone)
        }
        return buildList {
            if (has.sun) add(line(NodeRole.SUN, live?.sunKw, true))
            add(line(NodeRole.GRID, live?.gridKw, true))
            // + charging: away from the house.
            if (has.battery) add(line(NodeRole.BATTERY, live?.batteryKw ?: house.battery?.now?.powerKw, false))
            if (has.car && carPlugged(live)) add(line(NodeRole.CAR, live?.carKw, false))
            if (has.heat) add(line(NodeRole.HEAT, live?.heatKw, false))
        }
    }

    fun tone(role: NodeRole): Tone = when (role) {
        NodeRole.SUN -> Tone.SUN
        NodeRole.GRID -> Tone.GRID
        NodeRole.BATTERY -> Tone.BATTERY
        NodeRole.CAR -> Tone.CAR
        NodeRole.HEAT -> Tone.HEAT
    }

    /** The dots' lap (s): faster with more power, from 3.3 s near nothing to 0.8 s from 12 kW. */
    fun dotLap(kw: Double): Double = (1 - min(abs(kw), 12.0) / 12.0) * 2.5 + 0.8

    /**
     * The corners' words: the day so far (the energy flow's figures) and the live levels. [wide] puts the grid's
     * sold part beside the bought figure, else under it.
     */
    fun corners(text: SiteText, house: House, live: HouseNow?, wide: Boolean): List<CornerWords> {
        val words = text.words
        val flow = house.energyFlow
        val has = present(live, house)
        fun value(kwh: Double?): String = kwh?.let(text::kwh) ?: "–"
        return buildList {
            if (has.sun) add(CornerWords(NodeRole.SUN, words.say("house.nowTab.sun"), value(flow?.kwh(true, "solar")), Tone.SUN))
            // The house with its heat; the car is its own corner, and the figure says it is not in here.
            val whole = flow?.let { (it.kwh(false, "house") ?: 0.0) + (it.kwh(false, "heat") ?: 0.0) }
            add(
                CornerWords(
                    null, words.say("house.nowTab.house"), value(whole), Tone.HOUSE,
                    under = if (has.car) words.say("house.nowTab.houseNoCar") else null
                )
            )
            val sold = flow?.kwh(false, "grid")?.takeIf { it >= STILL_KW }
            add(
                CornerWords(
                    NodeRole.GRID, words.say("house.nowTab.grid"), value(flow?.kwh(true, "grid")), Tone.IMPORT,
                    beside = sold?.takeIf { wide }?.let { " · " + words.say("house.nowTab.sold", "kwh" to text.bare(it)) },
                    besideTone = Tone.EXPORT,
                    under = sold?.takeIf { !wide }?.let { words.say("house.nowTab.sold", "kwh" to text.kwh(it)) },
                    underTone = sold?.takeIf { !wide }?.let { Tone.EXPORT }
                )
            )
            if (has.battery) {
                val soc = live?.socPct ?: house.battery?.socPct
                val usable = house.battery?.usableKwh
                add(
                    CornerWords(
                        NodeRole.BATTERY, words.say("house.nowTab.battery"), soc?.let(text::percent) ?: "–", Tone.BATTERY,
                        under = if (soc != null && usable != null) text.kwh(soc / 100.0 * usable) else null
                    )
                )
            }
            if (has.heat) add(CornerWords(NodeRole.HEAT, words.say("house.nowTab.heat"), value(flow?.kwh(false, "heat")), Tone.HEAT))
            if (has.car) {
                val soc = if (carPlugged(live)) live?.carSocPct else null
                val today = flow?.kwh(false, "car")
                add(
                    if (soc == null) {
                        CornerWords(NodeRole.CAR, words.say("house.nowTab.car"), value(today), Tone.CAR, under = words.say("house.nowTab.today"))
                    } else {
                        CornerWords(
                            NodeRole.CAR, words.say("house.nowTab.car"), text.percent(soc), Tone.CAR,
                            under = words.say("house.nowTab.carToday", "kwh" to value(today))
                        )
                    }
                )
            }
        }
    }

    /** The whole schematic for a width: [wide] has room for the grid's sold part beside the bought figure. */
    fun schematic(text: SiteText, house: House, live: HouseNow?, wide: Boolean): Schematic {
        val has = present(live, house)
        val lines = lines(live, house)
        return Schematic(
            present = has,
            lines = lines,
            labels = lines.filter { it.kw != null && it.way != Way.STILL }.associate { it.role to text.kw(it.kw!!) },
            corners = corners(text, house, live, wide),
            houseKw = live?.houseKw?.takeIf { !has.alone }?.let(text::kw),
            carDimmed = !carPlugged(live),
            description = description(text, house, live, wide)
        )
    }

    /** The picture in words, for a screen reader: each corner, the house's power, and each line's way. */
    fun description(text: SiteText, house: House, live: HouseNow?, wide: Boolean): String {
        val said = ArrayList<String>()
        if (live?.houseKw != null && !present(live, house).alone) {
            said.add("${text.words.say("house.nowTab.houseNow")} ${text.kw(live.houseKw)}")
        }
        for (word in corners(text, house, live, wide)) {
            said.add(listOfNotNull(word.name, word.value, word.beside, word.under).joinToString(" "))
        }
        for (line in lines(live, house)) {
            if (line.kw != null && line.way != Way.STILL) {
                said.add(text.words.say("house.nowTab.line.${line.role.key}.${line.way.key}", "kw" to text.kw(line.kw)))
            }
        }
        return said.joinToString(". ")
    }

    /** The plan's runs from the running hour on: each change of what the battery does, until the plan's last hour. */
    fun planRuns(house: House, now: Instant): List<PlanRun> {
        val runs = ArrayList<PlanRun>()
        for (hour in house.hours) {
            if (!hour.start.plusSeconds(HOUR_SECONDS).isAfter(now)) continue
            if (hour.socPlanPct == null && hour.batteryAction == null) {
                // The plan ends here.
                if (runs.isNotEmpty()) break
                continue
            }
            val act = hour.batteryAction ?: "self"
            if (runs.isEmpty() || runs.last().act != act) runs.add(PlanRun(act, hour.start))
        }
        return runs
    }

    private fun whenText(text: SiteText, zone: ZoneId, now: Instant, at: Instant): String {
        val same = now.atZone(zone).toLocalDate() == at.atZone(zone).toLocalDate()
        return if (same) text.clock(at, zone) else "${text.dayCue(at, zone)} ${text.clock(at, zone)}"
    }

    /**
     * The Now tab's sentence: what the battery does now and until when, then what comes next; while SpotNav does not
     * control the battery, who does and what SpotNav would do. `null` without a battery or anything to say.
     */
    fun planSentence(text: SiteText, house: House, now: Instant): String? {
        val words = text.words
        val battery = house.battery ?: return null
        if (battery.status == "waiting") return words.say("house.saving.waiting")
        if (battery.status == "no_plan") return words.say("house.saving.noPlan")
        val runs = planRuns(house, now)
        val controlling = battery.control == "on"
        val verb = if (controlling) "act" else "inf"
        val who = if (battery.management == "self_managed") {
            words.say("house.plan.who.own", "name" to (OPTIMISERS[battery.brand.orEmpty()] ?: words.say("house.compare.own")))
        } else {
            words.say("house.plan.who.test")
        }
        if (runs.isEmpty()) return if (controlling) null else who
        val first = words.say("house.plan.$verb.${runs[0].act}")
        val next = runs.getOrNull(1)
            ?: return if (controlling) words.say("house.plan.whole", "act" to first)
            else words.say("house.plan.wouldWhole", "who" to who, "act" to first)
        // Controlled, the second clause has its own subject ("sedan säljer det till nätet").
        val then = words.say("house.plan.${if (controlling) "then" else "inf"}.${next.act}")
        val time = whenText(text, house.zone, now, next.start)
        return if (controlling) words.say("house.plan.next", "act" to first, "time" to time, "then" to then)
        else words.say("house.plan.wouldNext", "who" to who, "act" to first, "time" to time, "then" to then)
    }

    /** Today's money, one line: "I dag: batteriet tjänade 11 kr · SpotNav sparade 17 kr"; `null` with neither. */
    fun moneyLine(text: SiteText, house: House): String? {
        val words = text.words
        val parts = ArrayList<String>()
        val today = house.today
        if (house.battery != null && today != null) {
            parts.add(words.say("house.nowTab.moneyBattery", "amount" to text.amount(today.arbitrage, house.money)))
        }
        house.saving?.total?.let { parts.add(words.say("house.nowTab.moneySaved", "amount" to text.amount(it, house.money))) }
        return if (parts.isEmpty()) null else words.say("house.nowTab.money", "parts" to parts.joinToString(" · "))
    }

    fun modeName(text: SiteText, mode: String): String = if (mode in MODES) text.words.say("house.mode.$mode") else mode

    /**
     * The small grey line under the money: the battery's mode, and that there are no live figures while they cannot
     * be read. `null` with neither.
     */
    fun notesLine(text: SiteText, house: House, live: HouseNow?): String? {
        val notes = ArrayList<String>()
        house.battery?.let { notes.add(text.words.say("house.sub.mode", "mode" to modeName(text, house.today?.mode ?: it.mode))) }
        if (live == null) notes.add(text.words.say("house.nowTab.noLive"))
        if (notes.isEmpty()) return null
        return notes.joinToString(" · ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(text.locale) else it.toString() }
    }

    /** "13 dygn kvar": a learning period's whole days left. */
    fun daysLeft(text: SiteText, days: Int): String =
        text.words.say("learning.daysLeft.${if (days == 1) "one" else "other"}", "days" to days.toString())

    /** One line per learning period that holds something back now; none when nothing is held back. */
    fun learningLines(text: SiteText, house: House): List<String> = house.learning.filter { it.what == "phase_peaks" }.map { hold ->
        val left = daysLeft(text, hold.daysLeft)
        if (hold.capKw > 0) {
            text.words.say("house.learning.cappedWhile", "kw" to text.short(hold.capKw), "left" to left)
        } else {
            "${text.words.say("house.learning.peaksLine", "left" to left)} ${text.words.say("house.learning.noGridUntil")}"
        }
    }

    /**
     * What the battery does now, as the header says it after the name: while SpotNav controls it, the action its
     * control runs, else what the measured power says; "Egen styrning" while the battery's own optimiser runs it;
     * [HouseStatus.Test] while SpotNav controls nothing yet; `null` without a battery or (controlling) a reading.
     */
    fun status(words: CardWords, house: House): HouseStatus? {
        val battery = house.battery ?: return null
        if (battery.control != "on") {
            return if (battery.management == "self_managed") HouseStatus.Said("own", words.say("house.now.own")) else HouseStatus.Test
        }
        val said = mapOf(
            "grid_charge" to "house.legend.charge",
            "hold" to "house.now.hold",
            "export_discharge" to "house.legend.sell",
            "discharge_house" to "house.now.discharge"
        )
        val action = battery.now?.action
        said[action]?.let { return HouseStatus.Said(action!!, words.say(it)) }
        val power = battery.now?.powerKw ?: return null
        return when {
            power >= REST_KW -> HouseStatus.Said("sun", words.say("house.now.sun"))
            power <= -REST_KW -> HouseStatus.Said("discharge", words.say("house.now.discharge"))
            else -> HouseStatus.Said("idle", words.say("house.now.idle"))
        }
    }
}
