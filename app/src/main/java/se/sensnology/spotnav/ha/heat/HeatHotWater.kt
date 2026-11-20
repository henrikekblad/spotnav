package se.sensnology.spotnav.ha.heat

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.StrictJson
import java.time.Instant

/**
 * The site's hot water (`hot_water` of `get_heat`, `heat/dhw_runtime.py`; the card's `HeatHotWater`): the Boost
 * button's state, the peaks it is to be ready by, the boosts SpotNav plans (or would plan, in shadow), today's runs,
 * yesterday's review, the way to live ([gate]) and level A's blocks kept clear of the hot water ([blocking]).
 */
internal data class HeatHotWater(
    /** The Boost button; `null` where the pump has no boost that ends by itself (no button then). */
    val boost: Boost?,
    val peaks: List<Peak>,
    val planned: List<Planned>,
    val runsToday: List<Run>,
    val review: Review?,
    val gate: Gate,
    /** "B" (a boost route), "A" (a block path only) or `null`. */
    val level: String?,
    /** "shadow", "live" or "watch". */
    val mode: String,
    val blocking: Blocking?
) {
    data class Boost(
        /** "idle", "starting", "running" or "failed". */
        val state: String,
        /** "user" (the button's or the household's own), "plan" (SpotNav's) or "pump" (its own run). */
        val source: String?,
        val started: Instant?,
        val ends: Instant?,
        /** [ends] is the learned run length's estimate, not a timer's. */
        val endsEstimated: Boolean,
        /** The household's own boost that can be ended early: the button offers Cancel. */
        val stoppable: Boolean
    )

    /** A peak the tank is to be ready by ("05:00", local). */
    data class Peak(val readyBy: String, val kwh: Double, val learned: Boolean)

    data class Planned(
        val start: Instant,
        val end: Instant,
        val kwh: Double,
        /** The run's mean price (minor unit per kWh). */
        val price: Double,
        /** "cheap" or "sun". */
        val reason: String,
        /** The peak it fills ("06:00", local). */
        val peak: String,
        /** Written to the pump (live); else what SpotNav would do (shadow). */
        val live: Boolean,
        /** The pump's own run the boost would have replaced, once seen. */
        val pumpRun: Instant?
    )

    data class Run(val start: Instant, val end: Instant, val kwh: Double, val source: String)

    data class Review(
        val date: String,
        val mode: String,
        val wouldCost: Double,
        val actualCost: Double,
        val saved: Double,
        val movedKwh: Double,
        val hits: Int,
        val peaks: Int,
        val misses: Int,
        val coldRisks: Int,
        val blocksCut: Int,
        /** Level A's mode that day: "shadow" or "live". */
        val aMode: String,
        val perPeak: List<PerPeak>
    ) {
        data class PerPeak(val peak: String, val hit: Boolean, val pick: Instant?, val pumpRun: Instant?, val miss: Boolean, val coldRisk: Boolean)
    }

    data class Gate(
        val level: String,
        val days: Int,
        val daysNeeded: Int,
        val weekdays: Int,
        val peaks: Int,
        val peaksNeeded: Int,
        val hits: Int,
        val hitNeeded: Double,
        val saved: Double,
        val open: Boolean,
        val backedOff: String?
    )

    /** An hour level A takes out of the heat plan: "peak", "day_cap", "hygiene" or "moved". */
    data class Cut(val start: Instant, val end: Instant, val reason: String, val peak: String?, val live: Boolean)

    data class Blocking(val mode: String, val gate: Gate, val cuts: List<Cut>, val tank: Boolean, val strict: Boolean)
}

internal object HeatHotWaterRead {
    fun hotWater(json: JSONObject): HeatHotWater = with(StrictJson) {
        HeatHotWater(
            boost = objOrNull(json.opt("boost"), "hot_water.boost")?.let(::boost),
            peaks = list(json.opt("peaks"), "hot_water.peaks").map {
                val peak = obj(it, "hot_water.peak")
                HeatHotWater.Peak(clock(peak.opt("ready_by"), "peak.ready_by"), number(peak.opt("kwh"), "peak.kwh"), flag(peak.opt("learned"), "peak.learned"))
            },
            planned = list(json.opt("planned"), "hot_water.planned").map {
                val item = obj(it, "hot_water.planned")
                HeatHotWater.Planned(
                    start = instant(item.opt("start"), "planned.start"),
                    end = instant(item.opt("end"), "planned.end"),
                    kwh = number(item.opt("kwh"), "planned.kwh"),
                    price = number(item.opt("price"), "planned.price"),
                    reason = text(item.opt("reason"), "planned.reason"),
                    peak = text(item.opt("peak"), "planned.peak"),
                    live = flag(item.opt("live"), "planned.live"),
                    pumpRun = instantOrNull(item.opt("pump_run"), "planned.pump_run")
                )
            },
            runsToday = list(json.opt("runs_today"), "hot_water.runs_today").map {
                val run = obj(it, "hot_water.run")
                HeatHotWater.Run(instant(run.opt("start"), "run.start"), instant(run.opt("end"), "run.end"), number(run.opt("kwh"), "run.kwh"), text(run.opt("source"), "run.source"))
            },
            review = objOrNull(json.opt("review"), "hot_water.review")?.let(::review),
            gate = gate(json.opt("gate"), "hot_water.gate"),
            level = textOrNull(json.opt("level"), "hot_water.level"),
            mode = text(json.opt("mode"), "hot_water.mode"),
            blocking = objOrNull(json.opt("blocking"), "hot_water.blocking")?.let { blocking ->
                HeatHotWater.Blocking(
                    mode = text(blocking.opt("mode"), "blocking.mode"),
                    gate = gate(blocking.opt("gate"), "blocking.gate"),
                    cuts = list(blocking.opt("cuts"), "blocking.cuts").map {
                        val cut = obj(it, "blocking.cut")
                        HeatHotWater.Cut(
                            instant(cut.opt("start"), "cut.start"), instant(cut.opt("end"), "cut.end"), text(cut.opt("reason"), "cut.reason"),
                            textOrNull(cut.opt("peak"), "cut.peak"), flag(cut.opt("live"), "cut.live")
                        )
                    },
                    tank = flag(blocking.opt("tank"), "blocking.tank"),
                    strict = flag(blocking.opt("strict"), "blocking.strict")
                )
            }
        )
    }

    fun boost(json: JSONObject): HeatHotWater.Boost = with(StrictJson) {
        HeatHotWater.Boost(
            state = text(json.opt("state"), "boost.state"),
            source = textOrNull(json.opt("source"), "boost.source"),
            started = instantOrNull(json.opt("started"), "boost.started"),
            ends = instantOrNull(json.opt("ends"), "boost.ends"),
            endsEstimated = flag(json.opt("ends_estimated"), "boost.ends_estimated"),
            stoppable = flag(json.opt("stoppable"), "boost.stoppable")
        )
    }

    private fun review(json: JSONObject): HeatHotWater.Review = with(StrictJson) {
        HeatHotWater.Review(
            date = text(json.opt("date"), "review.date"),
            mode = text(json.opt("mode"), "review.mode"),
            wouldCost = number(json.opt("would_cost"), "review.would_cost"),
            actualCost = number(json.opt("actual_cost"), "review.actual_cost"),
            saved = number(json.opt("saved"), "review.saved"),
            movedKwh = number(json.opt("moved_kwh"), "review.moved_kwh"),
            hits = count(json.opt("hits"), "review.hits"),
            peaks = count(json.opt("peaks"), "review.peaks"),
            misses = count(json.opt("misses"), "review.misses"),
            coldRisks = count(json.opt("cold_risks"), "review.cold_risks"),
            blocksCut = count(json.opt("blocks_cut"), "review.blocks_cut"),
            aMode = text(json.opt("a_mode"), "review.a_mode"),
            perPeak = list(json.opt("per_peak"), "review.per_peak").map {
                val item = obj(it, "review.peak")
                HeatHotWater.Review.PerPeak(
                    peak = text(item.opt("peak"), "per_peak.peak"),
                    hit = flag(item.opt("hit"), "per_peak.hit"),
                    pick = instantOrNull(item.opt("pick"), "per_peak.pick"),
                    pumpRun = instantOrNull(item.opt("pump_run"), "per_peak.pump_run"),
                    miss = flag(item.opt("miss"), "per_peak.miss"),
                    coldRisk = flag(item.opt("cold_risk"), "per_peak.cold_risk")
                )
            }
        )
    }

    private fun gate(raw: Any?, what: String): HeatHotWater.Gate = with(StrictJson) {
        val json = obj(raw, what)
        HeatHotWater.Gate(
            level = text(json.opt("level"), "$what.level"),
            days = count(json.opt("days"), "$what.days"),
            daysNeeded = count(json.opt("days_needed"), "$what.days_needed"),
            weekdays = count(json.opt("weekdays"), "$what.weekdays"),
            peaks = count(json.opt("peaks"), "$what.peaks"),
            peaksNeeded = count(json.opt("peaks_needed"), "$what.peaks_needed"),
            hits = count(json.opt("hits"), "$what.hits"),
            hitNeeded = number(json.opt("hit_needed"), "$what.hit_needed"),
            saved = number(json.opt("saved"), "$what.saved"),
            open = flag(json.opt("open"), "$what.open"),
            backedOff = textOrNull(json.opt("backed_off"), "$what.backed_off")
        )
    }

    private fun clock(raw: Any?, what: String): String {
        val text = StrictJson.text(raw, what)
        if (!Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(text)) StrictJson.bad(what)
        return text
    }
}

/**
 * The Boost button (webhook `heat_boost`, the twin of `spotnav/heat_boost`): `boost_action` "start" or "stop", for
 * any user. The answer is the command's envelope with the boost's state after the press; HTTP 429 is
 * `spotnav_rate_limited`, 409 another refusal (`spotnav_no_headroom`, `spotnav_busy`, `spotnav_write_failed`, ...).
 */
internal object HeatBoostWrite {
    const val RATE_LIMITED = "spotnav_rate_limited"
    const val NO_HEADROOM = "spotnav_no_headroom"

    fun payload(action: String): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "heat_boost")
        put("api_version", HeatRead.API_VERSION)
        put("boost_action", action)
    }

    sealed interface Outcome {
        /** Pressed: the boost's state after it (`null` where the pump has no boost any more). */
        data class Done(val boost: HeatHotWater.Boost?) : Outcome

        /** Refused by Home Assistant: [code] says why (`spotnav_rate_limited`, `spotnav_no_headroom`, ...). */
        data class Refused(val code: String?) : Outcome

        data object Unsupported : Outcome

        /** No answer, or one that could not be read. */
        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        val version = head.json.opt("api_version")
        if (head.error == WriteEnvelope.UNSUPPORTED_VERSION || head.error == "unknown_command") return Outcome.Unsupported
        if (version != null && version != HeatRead.API_VERSION) return Outcome.Unsupported
        if (!head.ok) return Outcome.Refused(head.error)
        return runCatching {
            val raw = head.json.opt("boost")
            Outcome.Done(if (raw == null || raw === JSONObject.NULL) null else HeatHotWaterRead.boost(StrictJson.obj(raw, "boost")))
        }.getOrElse { Outcome.Failed(null) }
    }
}
