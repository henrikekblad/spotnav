package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatLoadPress
import se.sensnology.spotnav.ha.heat.HeatLoads
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle

/**
 * The Pool, Spa and Bastu boxes' words, as the card's `pool-view.ts` and `spa-view.ts` say them: what the load does now
 * and what SpotNav would do, the plan from now on, today's filtering, a bath that will not be ready with its choices, and
 * SpotNav's control ("Styrs av SpotNav.", "Pausad till …", "Återlämnad till dig till …"). Pure: times are said on
 * [now]'s day by the clock, another day with its day.
 */
internal class HeatLoadsText(private val text: SiteText, private val now: Instant, private val zone: ZoneId) {
    private val words get() = text.words

    private fun degrees(value: Double): String = HeatNowModel.degrees(text, value)

    private fun whenLabel(at: Instant): String = HeatNowModel.moment(text, now, zone, at)

    private fun clock(at: Instant): String = text.clock(at, zone)

    private fun hours(value: Double): String = "${text.fixed(value, if (value == Math.floor(value)) 0 else 1)} h"

    /** The runs ahead ("22:00–02:00, 11:00–13:00"), the first [count]. */
    fun runsText(runs: List<HeatLoads.Run>, count: Int = 3): String = runs
        .filter { it.end.isAfter(now) }
        .take(count)
        .joinToString(", ") { "${whenLabel(maxOf(it.start, now))}–${clock(it.end)}" }

    // --- the pool ---------------------------------------------------------------------------------

    /** The water now ("Vattnet 24,6 °C"), or that it is not read (yet). */
    fun poolWater(pool: HeatLoads.Pool): String = pool.waterC?.let { words.say("heat.pool.water", "value" to degrees(it)) }
        ?: words.say(if (pool.heatPump == null) "heat.pool.waterNone" else "heat.pool.waterWait")

    /** Where the water was read and when ("Från värmepumpen, 12:40"), or `null` while none has read. */
    fun poolSource(pool: HeatLoads.Pool): String? {
        val at = pool.waterAt ?: return null
        if (pool.waterC == null || (pool.waterSource != "heat_pump" && pool.waterSource != "sensor")) return null
        return words.say("heat.pool.source.${pool.waterSource}", "time" to whenLabel(at))
    }

    /** The target as the box's figure says it ("Mål 27,0 °C" is the card's dial; here the value alone). */
    fun target(value: Double): String = degrees(value)

    /** What the pool does now: the season closed, heating towards the target, filtering, or standing still. */
    fun poolStatus(pool: HeatLoads.Pool): String {
        if (!pool.seasonOpen) return words.say("heat.pool.closed")
        val target = degrees(pool.targetC)
        if (pool.heatPump?.heating == true) {
            return pool.waterC?.let { words.say("heat.pool.heating", "value" to degrees(it), "target" to target) }
                ?: words.say("heat.pool.heatingTo", "target" to target)
        }
        return words.say(if (pool.circulation?.on == true) "heat.pool.filtering" else "heat.pool.still")
    }

    /** What SpotNav would do now ("Skulle filtrera till 14:00: billig timme"); `null` with the season closed. */
    fun poolWould(pool: HeatLoads.Pool): String? {
        val would = pool.would ?: return null
        if (!pool.seasonOpen) return null
        val until = would.until?.let(::whenLabel)
        if (would.action == "heat" || would.action == "filter") {
            val base = "heat.pool.would.${would.action}${if (until == null) "Now" else ""}"
            val said = words.say(base, "until" to (until ?: ""))
            return if (would.reason in POOL_WHY) words.say("heat.pool.would.with", "text" to said, "why" to words.say("heat.pool.why.${would.reason}")) else said
        }
        return when {
            would.reason == "need_met" -> words.say("heat.pool.would.met")
            would.reason == "no_prices" -> words.say("heat.pool.would.noPrices")
            until == null -> words.say("heat.pool.would.offNow")
            else -> words.say("heat.pool.would.off", "until" to until)
        }
    }

    /** Today's filtering and the heat pump's planned hours; none with the season closed. */
    fun poolToday(pool: HeatLoads.Pool): List<String> {
        if (!pool.seasonOpen) return emptyList()
        val today = pool.today
        return buildList {
            if (pool.circulation != null) {
                val done = "done" to hours(today.doneH)
                val planned = "planned" to hours(today.plannedH)
                add(
                    today.needH?.let { need ->
                        words.say(if (today.needSource == "override") "heat.pool.todayNeedSet" else "heat.pool.todayNeed", done, planned, "need" to hours(need))
                    } ?: words.say("heat.pool.today", done, planned)
                )
            }
            if (pool.heatPump != null && today.heatH != null) {
                val heat = words.say("heat.pool.todayHeat", "hours" to hours(today.heatH))
                add(today.kwh?.let { "$heat · ${text.kwh(it)}" } ?: heat)
            }
        }
    }

    /** The planned hours in words ("Filtrerar 22:00–02:00 · Värmer 11:00–13:00"); `null` with the season closed. */
    fun poolPlan(pool: HeatLoads.Pool): String? {
        if (!pool.seasonOpen) return null
        val parts = listOf("filter", "heat", "extra").mapNotNull { what ->
            pool.planned.filter { it.what == what }.takeIf { it.isNotEmpty() }?.let { words.say("heat.pool.plan.$what", "runs" to runsText(it, 2)) }
        }
        return if (parts.isEmpty()) words.say("heat.pool.plan.none") else parts.joinToString(" · ")
    }

    /** The extra filtering asked for and when it would run; `null` with none asked for. */
    fun poolExtra(pool: HeatLoads.Pool): String? {
        val extra = pool.extra ?: return null
        val runs = pool.planned.filter { it.what == "extra" }
        val count = text.fixed(extra.hours, 0)
        return if (runs.isEmpty()) words.say("heat.pool.extra.waiting", "hours" to count, "until" to whenLabel(extra.until))
        else words.say("heat.pool.extra.when", "hours" to count, "runs" to runsText(runs, 3))
    }

    /** Each part SpotNav controls ("Cirkulationen: Styrs av SpotNav."); empty while it only watches both. */
    fun poolControl(pool: HeatLoads.Pool): List<String> = listOfNotNull(
        pool.heatPump?.let { part -> control(part.control)?.let { words.say("heat.loadControl.part", "name" to words.say("heat.pool.partHeatPump"), "text" to it) } },
        pool.circulation?.let { part -> control(part.control)?.let { words.say("heat.loadControl.part", "name" to words.say("heat.pool.partCirculation"), "text" to it) } }
    )

    // --- the spa and the sauna --------------------------------------------------------------------

    /** The water or the room now ("Vattnet 36,5 °C", "Nu 62,0 °C"), or that nothing reads it. */
    fun deadlineTemp(load: HeatLoads.Deadline): String = load.tempC?.let { words.say("heat.${load.kind}.temp", "value" to degrees(it)) }
        ?: words.say("heat.${load.kind}.tempNone")

    /** "Klar 18:40, värmer 2 °C/h", ready, idle at its temperature or off, with the next time. */
    fun deadlineStatus(load: HeatLoads.Deadline): String {
        val ready = load.readyAt?.let(::clock)
        if (load.state == "heating" && ready != null) {
            return if (load.kind == "spa" && load.rateLearned) words.say("heat.spa.readyRate", "time" to ready, "rate" to text.upTo(load.rateCH, 1))
            else words.say("heat.spa.readyAt", "time" to ready)
        }
        if (load.state == "ready") {
            return load.tempC?.let { words.say("heat.${load.kind}.readyNow", "value" to degrees(it)) } ?: words.say("heat.${load.kind}.ready")
        }
        val next = load.next?.let(::whenLabel)
        if (load.kind == "spa" && load.idleC != null) {
            val idle = degrees(load.idleC)
            return if (next == null) words.say("heat.spa.idle", "idle" to idle) else words.say("heat.spa.idleNext", "idle" to idle, "when" to next)
        }
        return if (next == null) words.say("heat.sauna.off") else words.say("heat.sauna.offNext", "when" to next)
    }

    /** What SpotNav would do now; for the sauna also that a lock holds it, or that it must be started by hand. */
    fun deadlineWould(load: HeatLoads.Deadline): String? {
        val would = load.would ?: return null
        val until = would.until?.let(::whenLabel)
        val target = degrees(load.targetC)
        return when (would.action) {
            "heat" -> {
                val said = if (until == null) words.say("heat.spa.would.heatNow") else words.say("heat.spa.would.heat", "until" to until)
                if (would.reason in DEADLINE_WHY) words.say("heat.spa.would.with", "text" to said, "why" to words.say("heat.spa.why.${would.reason}")) else said
            }
            "hold" -> if (until == null) words.say("heat.spa.would.holdNow", "target" to target) else words.say("heat.spa.would.hold", "target" to target, "until" to until)
            "idle" -> load.idleC?.let { words.say("heat.spa.would.idle", "idle" to degrees(it)) }
            "floor" -> load.floorC?.let { words.say("heat.spa.would.floor", "floor" to degrees(it)) }
            "blocked" -> words.say("heat.sauna.would.blocked")
            "start_by_hand" -> load.next?.let { words.say("heat.sauna.would.byHand", "when" to whenLabel(it)) } ?: words.say("heat.sauna.would.byHandNow")
            else -> words.say("heat.${load.kind}.would.off")
        }
    }

    /**
     * The would-line where it adds something, as the card shows it: not under a controlled, paused or handed-back load
     * (its control line says what it does), and never a "would stand still" that repeats the status.
     */
    fun deadlineWouldShown(load: HeatLoads.Deadline, withStatus: Boolean = true): String? {
        if (load.control.state in CONTROL_STATES) return null
        val would = load.would
        // [withStatus] false where no status line is beside it (the settings' help line under the section).
        if (withStatus && would != null && would.action !in WOULD_ACTIVE) {
            val statusActive = load.state == "ready" || (load.state == "heating" && load.readyAt != null)
            if (!statusActive) return null
        }
        return deadlineWould(load)
    }

    /** The pool's would-line, as [deadlineWouldShown]: not with either part controlled, paused or handed back, nor a plain "stand still" while it stands still. */
    fun poolWouldShown(pool: HeatLoads.Pool, withStatus: Boolean = true): String? {
        if (listOfNotNull(pool.heatPump, pool.circulation).any { it.control.state in CONTROL_STATES }) return null
        val would = pool.would
        if (withStatus && would != null && would.action != "heat" && would.action != "filter" && would.reason != "need_met" && would.reason != "no_prices") {
            val still = pool.heatPump?.heating != true && pool.circulation?.on != true
            if (still) return null
        }
        return poolWould(pool)
    }

    /** A bath or a session that will not be ready ("Badet 19:00 blir inte klart i tid: det når 35 °C. Säkringen …"). */
    fun notReadyLine(load: HeatLoads.Deadline, notReady: HeatLoads.NotReadyAt): String {
        val said = words.say("heat.${load.kind}.notReady", "when" to whenLabel(notReady.at), "reach" to degrees(notReady.reachC))
        return if (notReady.cause in CAUSES) "$said ${words.say("heat.spa.cause.${notReady.cause}")}" else said
    }

    data class Choice(val action: String, val label: String)

    /** The choices a bath or a session that will not be ready offers: take what fits, a later time, allow the peak. */
    fun notReadyChoices(load: HeatLoads.Deadline, notReady: HeatLoads.NotReadyAt): List<Choice> = buildList {
        add(Choice("take_what_fits", words.say("heat.spa.choice.fits", "reach" to degrees(notReady.reachC))))
        notReady.later?.let {
            add(Choice(if (load.kind == "spa") "later_bath" else "later_session", words.say("heat.${load.kind}.choice.later", "time" to whenLabel(it))))
        }
        if (notReady.cause == "import_target" || notReady.cause == "power_level") add(Choice("accept_peak", words.say("heat.spa.choice.peak")))
    }

    /** "Bada nu: skulle värma och hålla till 21:00" while pressed; `null` otherwise. */
    fun nowLine(load: HeatLoads.Deadline): String? = load.nowUntil?.let { words.say("heat.${load.kind}.nowUntil", "until" to whenLabel(it)) }

    fun nowLabel(load: HeatLoads.Deadline): String = words.say("heat.${load.kind}.now")

    fun nowAction(load: HeatLoads.Deadline): String = if (load.kind == "spa") "bath_now" else "session_now"

    /** The weekly times and the one-off ("Badtider: fre, lör 19:00 · en gång tis 24 nov. 18:00"). */
    fun times(load: HeatLoads.Deadline): String {
        val parts = load.weekly.map { "${weekdays(it.weekdays)} ${it.time}" }.toMutableList()
        load.once?.let { parts += words.say("heat.spa.once", "when" to whenLabel(it)) }
        val said = if (parts.isEmpty()) words.say(if (load.kind == "spa") "heat.spa.noBaths" else "heat.sauna.noSessions") else parts.joinToString(" · ")
        return "${words.say("heat.${load.kind}.times")}: $said"
    }

    /** Weekdays (Monday 1 to Sunday 7, as a car's departures), runs of three or more joined ("mån–fre", "lör, sön"); all seven "varje dag". */
    fun weekdays(days: List<Int>): String {
        val sorted = days.filter { it in 1..7 }.distinct().sorted()
        if (sorted.size == 7) return words.say("heat.spa.everyDay")
        fun short(day: Int) = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, text.locale).trimEnd('.')
        val parts = mutableListOf<String>()
        var index = 0
        while (index < sorted.size) {
            var end = index
            while (end + 1 < sorted.size && sorted[end + 1] == sorted[end] + 1) end++
            if (end - index >= 2) parts += "${short(sorted[index])}–${short(sorted[end])}" else (index..end).forEach { parts += short(sorted[it]) }
            index = end + 1
        }
        return parts.joinToString(", ")
    }

    /** "Mellan baden: 30,0 °C" (the spa only). */

    /** The heat-up and the hold in words ("Värms 16:00–17:45 · Hålls varmt till 20:00"); `null` with nothing planned. */
    fun deadlinePlan(load: HeatLoads.Deadline): String? {
        val heat = load.planned.filter { it.what == "heat" }
        val hold = load.planned.filter { it.what == "hold" }
        val parts = buildList {
            if (heat.isNotEmpty()) add(words.say("heat.${load.kind}.plan.heat", "runs" to runsText(heat, 2)))
            if (hold.isNotEmpty()) add(words.say("heat.${load.kind}.plan.hold", "until" to clock(hold.last().end)))
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** SpotNav's control of the spa or the sauna, else watched only (and why) or slow through a cloud. */
    fun deadlineControl(load: HeatLoads.Deadline): String = control(load.control) ?: when {
        load.control.reason == "no_safety" -> words.say("heat.sauna.noSafety")
        load.control.reason == "cloud_slow" -> words.say("heat.spa.cloud")
        load.control.state == "watch_only" -> words.say("heat.${load.kind}.watchOnly")
        else -> words.say("heat.${load.kind}.watched")
    }

    /**
     * A controlled load's line ("Styrs av SpotNav.", "Pausad till i morgon 00:00: dagens ändringar är använda.",
     * "Återlämnad till dig till …"), with the writes that did not read back; `null` for a load SpotNav only watches.
     */
    fun control(control: HeatLoads.Control): String? {
        if (!control.controlling) return null
        val reason = control.reason.orEmpty()
        val why = if (reason in CONTROL_WHY) words.say("heat.loadControl.why.$reason") else null
        val until = control.pausedUntil?.let(::whenLabel)
        val said = when {
            control.state == "handed_back" -> words.say("heat.loadControl.handedBack", "until" to (until ?: ""))
            control.state == "paused" -> {
                val because = why ?: words.say("heat.loadControl.why.unavailable")
                if (until == null) words.say("heat.loadControl.pausedNow", "why" to because) else words.say("heat.loadControl.paused", "until" to until, "why" to because)
            }
            reason == "cloud_slow" -> words.say("heat.loadControl.cloud")
            why == null -> words.say("heat.loadControl.controlled")
            else -> words.say("heat.loadControl.controlledWhy", "why" to why)
        }
        return if (control.failures > 0) "$said ${words.say("heat.loadControl.failures", "count" to control.failures.toString())}" else said
    }

    companion object {
        /** A press's refusal in words, or `null` when it took. */
        fun pressError(outcome: HeatLoadPress.Outcome, failed: String, rateLimited: String): String? = when (outcome) {
            HeatLoadPress.Outcome.Done -> null
            is HeatLoadPress.Outcome.Refused -> if (outcome.code == HeatLoadPress.RATE_LIMITED) rateLimited else failed
            else -> failed
        }

        private val POOL_WHY = setOf("sun", "cheap", "need", "extra")
        private val DEADLINE_WHY = setOf("sun", "cheap", "bath", "now", "floor")
        private val WOULD_ACTIVE = setOf("heat", "hold", "floor", "blocked", "start_by_hand")
        private val CONTROL_STATES = setOf("controlled", "paused", "handed_back")
        private val CAUSES = setOf("rate", "import_target", "power_level", "fuse")
        private val CONTROL_WHY = setOf(
            "no_read_back", "write_cap", "starts_cap", "fault", "automation", "unavailable", "foreign_mode", "own_scheduler",
            "unsupported", "interlock", "run_on", "floor", "lock", "max_on", "lock_unreadable", "pump_not_ours", "restore_waits", "stale_plan",
        )

        fun of(text: SiteText, heat: Heat) = HeatLoadsText(text, heat.now, heat.zone)
    }
}
