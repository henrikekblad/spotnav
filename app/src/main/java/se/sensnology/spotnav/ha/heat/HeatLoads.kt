package se.sensnology.spotnav.ha.heat

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.StrictJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The pool, the spa and the sauna as `get_heat` states them (Home Assistant 3.0, `pool/runtime.py` and
 * `spa/runtime.py`; the card's `heat-model.ts`): what each does now, what SpotNav would do or does, the plan from now
 * on and SpotNav's control of each (watched until its own opt-in is on in Home Assistant). `null` without the load.
 */
internal object HeatLoads {
    /** SpotNav's control of a load (`loads/hold_part.py`): watched, or controlled, paused or handed back until a time. */
    data class Control(
        /** `watched`, `watch_only`, `season_closed`, `controlled`, `paused` or `handed_back`. */
        val state: String,
        val reason: String?,
        val pausedUntil: Instant?,
        /** Writes that did not read back (or were refused), ever. */
        val failures: Int
    ) {
        /** SpotNav controls it (its opt-in on): its line says how. */
        val controlling: Boolean get() = state in CONTROLLING
    }

    data class Would(val action: String, val until: Instant?, val reason: String)

    /** One planned run from now on: [what] `filter`, `heat`, `extra` or `hold`, with why. */
    data class Run(val start: Instant, val end: Instant, val what: String, val reason: String)

    /** One of the pool's two parts: the heat pump ([heating] read) or the circulation. */
    data class PoolPart(val on: Boolean?, val heating: Boolean?, val kw: Double?, val live: Boolean, val control: Control, /** `entered`, `learned` or `typical`: where [kw] comes from (`null` from a backend that did not say). */ val kwSource: String? = null)

    data class PoolToday(
        val needH: Double?,
        /** `water`, `override` or `default`. */
        val needSource: String?,
        val doneH: Double,
        val plannedH: Double,
        val heatH: Double?,
        val kwh: Double?
    )

    data class Pool(
        val seasonOpen: Boolean,
        val waterC: Double?,
        val waterSource: String?,
        /** When the water was read ([waterSource] says by what), or `null` while none has. */
        val waterAt: Instant?,
        val targetC: Double,
        val heatPump: PoolPart?,
        val circulation: PoolPart?,
        val today: PoolToday,
        val planned: List<Run>,
        /** The extra filtering asked for: its hours, to be done before [Extra.until]. */
        val extra: Extra?,
        val seasonHint: Boolean,
        val would: Would?
    )

    data class Extra(val hours: Double, val until: Instant)

    /** A bath or a session that will not be ready: why, what it reaches, and the earliest later hour that fits. */
    data class NotReadyAt(val at: Instant, val cause: String, val reachC: Double, val shortC: Double, val shortKwh: Double, val later: Instant?)

    /** A weekly time: on [weekdays] (Monday 1 to Sunday 7, as a car's departures) at the local [time]. */
    data class Session(val weekdays: List<Int>, val time: String)

    /** The spa ([kind] `spa`) or the sauna (`sauna`, no idle temperature): heated by the times booked. */
    data class Deadline(
        val kind: String,
        val family: String,
        val heating: Boolean?,
        /** The power that counts: the entered kW, else the learned one, else a typical one; never `null` on a current backend. */
        val kw: Double?,
        /** `entered`, `learned` or `typical` ([kw]'s source); `null` from a backend that did not say. */
        val kwSource: String? = null,
        val rateCH: Double,
        val rateLearned: Boolean,
        /** `idle`, `heating`, `ready` or `late`. */
        val state: String?,
        val readyAt: Instant?,
        val live: Boolean,
        val control: Control,
        val would: Would?,
        val notReady: NotReadyAt?,
        val planned: List<Run>,
        /** The water (spa) or the room (sauna) now. */
        val tempC: Double?,
        /** The bath's (spa) or the session's (sauna) temperature. */
        val targetC: Double,
        val idleC: Double?,
        val floorC: Double?,
        val next: Instant?,
        val weekly: List<Session>,
        val once: Instant?,
        /** "Bada nu" / "Värm nu" pressed: it runs until then. */
        val nowUntil: Instant?,
        /** The sauna's door or safety lock reads locked; `null` where nothing reads (and for the spa). */
        val locked: Boolean?
    )

    val CONTROLLING = setOf("controlled", "paused", "handed_back")

    private fun control(json: JSONObject, what: String): Control = with(StrictJson) {
        Control(
            state = text(json.opt("state"), "$what.state"),
            reason = textOrNull(json.opt("reason"), "$what.reason"),
            pausedUntil = instantOrNull(json.opt("paused_until"), "$what.paused_until"),
            failures = count(json.opt("failures"), "$what.failures")
        )
    }

    private fun would(raw: Any?, what: String): Would? = with(StrictJson) {
        objOrNull(raw, what)?.let {
            Would(text(it.opt("action"), "$what.action"), instantOrNull(it.opt("until"), "$what.until"), text(it.opt("reason"), "$what.reason"))
        }
    }

    private fun runs(raw: Any?, what: String): List<Run> = with(StrictJson) {
        list(raw, what).map {
            val run = obj(it, "$what[]")
            Run(instant(run.opt("start"), "$what.start"), instant(run.opt("end"), "$what.end"), text(run.opt("what"), "$what.what"), text(run.opt("reason"), "$what.reason"))
        }
    }

    private fun boolOrNull(raw: Any?, what: String): Boolean? = if (raw === JSONObject.NULL) null else StrictJson.flag(raw, what)

    private fun part(raw: Any?, what: String, heatPump: Boolean): PoolPart? = with(StrictJson) {
        val part = objOrNull(raw, what) ?: return null
        PoolPart(
            on = boolOrNull(part.opt("on"), "$what.on"),
            heating = if (heatPump) boolOrNull(part.opt("heating"), "$what.heating") else null,
            kw = numberOrNull(part.opt("kw"), "$what.kw"),
            live = flag(part.opt("live"), "$what.live"),
            control = control(part, what),
            kwSource = textOrNull(part.opt("kw_source"), "$what.kw_source")
        )
    }

    /** `get_heat`'s `pool`, or `null` without one. */
    fun pool(raw: Any?): Pool? = with(StrictJson) {
        val pool = objOrNull(raw, "pool") ?: return null
        val today = obj(pool.opt("today"), "pool.today")
        Pool(
            seasonOpen = flag(pool.opt("season_open"), "pool.season_open"),
            waterC = numberOrNull(pool.opt("water_c"), "pool.water_c"),
            waterSource = textOrNull(pool.opt("water_source"), "pool.water_source"),
            waterAt = instantOrNull(pool.opt("water_at") ?: JSONObject.NULL, "pool.water_at"),
            targetC = number(pool.opt("target_c"), "pool.target_c"),
            heatPump = part(pool.opt("heat_pump"), "pool.heat_pump", heatPump = true),
            circulation = part(pool.opt("circulation"), "pool.circulation", heatPump = false),
            today = PoolToday(
                needH = numberOrNull(today.opt("need_h"), "pool.today.need_h"),
                needSource = textOrNull(today.opt("need_source"), "pool.today.need_source"),
                doneH = number(today.opt("done_h"), "pool.today.done_h"),
                plannedH = number(today.opt("planned_h"), "pool.today.planned_h"),
                heatH = numberOrNull(today.opt("heat_h"), "pool.today.heat_h"),
                kwh = numberOrNull(today.opt("kwh"), "pool.today.kwh")
            ),
            planned = runs(pool.opt("planned"), "pool.planned"),
            extra = objOrNull(pool.opt("extra"), "pool.extra")?.let {
                Extra(number(it.opt("hours"), "pool.extra.hours"), instant(it.opt("until"), "pool.extra.until"))
            },
            seasonHint = flag(pool.opt("season_hint"), "pool.season_hint"),
            would = would(pool.opt("would"), "pool.would")
        )
    }

    /** `get_heat`'s `spa` or `sauna` ([kind]), or `null` without one. */
    fun deadline(raw: Any?, kind: String): Deadline? = with(StrictJson) {
        val load = objOrNull(raw, kind) ?: return null
        val spa = kind == "spa"
        Deadline(
            kind = kind,
            family = text(load.opt("family"), "$kind.family"),
            heating = boolOrNull(load.opt("heating"), "$kind.heating"),
            kw = numberOrNull(load.opt("kw"), "$kind.kw"),
            kwSource = textOrNull(load.opt("kw_source"), "$kind.kw_source"),
            rateCH = number(load.opt("rate_c_h"), "$kind.rate_c_h"),
            rateLearned = flag(load.opt("rate_learned"), "$kind.rate_learned"),
            state = textOrNull(load.opt("state"), "$kind.state"),
            readyAt = instantOrNull(load.opt("ready_at"), "$kind.ready_at"),
            live = flag(load.opt("live"), "$kind.live"),
            control = control(obj(load.opt("control"), "$kind.control"), "$kind.control"),
            would = would(load.opt("would"), "$kind.would"),
            notReady = objOrNull(load.opt("not_ready"), "$kind.not_ready")?.let {
                NotReadyAt(
                    at = instant(it.opt("at"), "not_ready.at"),
                    cause = text(it.opt("cause"), "not_ready.cause"),
                    reachC = number(it.opt("reach_c"), "not_ready.reach_c"),
                    shortC = number(it.opt("short_c"), "not_ready.short_c"),
                    shortKwh = number(it.opt("short_kwh"), "not_ready.short_kwh"),
                    later = instantOrNull(it.opt("later"), "not_ready.later")
                )
            },
            planned = runs(load.opt("planned"), "$kind.planned"),
            tempC = numberOrNull(load.opt(if (spa) "water_c" else "temp_c"), "$kind.temp"),
            targetC = number(load.opt(if (spa) "bath_c" else "target_c"), "$kind.target"),
            idleC = if (spa) number(load.opt("idle_c"), "spa.idle_c") else null,
            floorC = if (spa) number(load.opt("floor_c"), "spa.floor_c") else null,
            next = objOrNull(load.opt(if (spa) "next_bath" else "next_session"), "$kind.next")?.let { instant(it.opt("at"), "$kind.next.at") },
            weekly = list(load.opt(if (spa) "baths" else "sessions"), "$kind.sessions").map {
                val session = obj(it, "$kind.session")
                Session(list(session.opt("weekdays"), "session.weekdays").map { day -> count(day, "session.weekday") }, text(session.opt("time"), "session.time"))
            },
            once = instantOrNull(load.opt(if (spa) "bath_once" else "session_once"), "$kind.once"),
            nowUntil = objOrNull(load.opt(if (spa) "bath_now" else "session_now"), "$kind.now")?.let { instant(it.opt("until"), "$kind.now.until") },
            locked = if (spa) null else boolOrNull(load.opt("locked"), "sauna.locked")
        )
    }
}

/**
 * A press on the pool, the spa or the sauna (webhook `pool`, `spa` and `sauna`, the twins of `spotnav/pool`,
 * `spotnav/spa` and `spotnav/sauna`), for anyone who sees the heat page, as the dial is: `pool_action` (`season`,
 * `extra_filtering`, `cancel_extra`, `target`) with `open`, `hours` or `target_c`; `spa_action` / `sauna_action`
 * (`bath_now`/`session_now`, `cancel_now`, `take_what_fits`, `later_bath`/`later_session`, `accept_peak`, and the
 * settings `bath_c`, `baths`, ...) with `value`. The opt-ins stay with an administrator in Home Assistant.
 */
internal object HeatLoadPress {
    const val RATE_LIMITED = "spotnav_rate_limited"
    const val NOT_SUPPORTED = "spotnav_not_supported"
    const val NO_LATER = "spotnav_no_later_bath"
    /** The sauna's refusal when no later session fits (the spa's is [NO_LATER]). */
    const val NO_LATER_SESSION = "spotnav_no_later_session"

    /** A pool press: [fields] are `open`, `hours` or `target_c` as the action takes them. */
    fun pool(action: String, fields: Map<String, Any> = emptyMap()): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "pool")
        put("api_version", HeatRead.API_VERSION)
        put("pool_action", action)
        fields.forEach { (key, value) -> put(key, value) }
    }

    /** A spa or sauna press ([kind] `spa` or `sauna`), with the setting's [value] where the action takes one. */
    fun deadline(kind: String, action: String, value: Any? = null): JSONObject = JSONObject().apply {
        require(kind == "spa" || kind == "sauna") { "a deadline load is the spa or the sauna" }
        put("version", 1)
        put("action", kind)
        put("api_version", HeatRead.API_VERSION)
        put("${kind}_action", action)
        if (value != null) put("value", value)
    }

    /** The pool's target water temperature (`target`, 18 to 40 °C in half degrees). */
    fun poolTarget(targetC: Double): JSONObject = pool("target", mapOf("target_c" to targetC))

    /** The spa's bath temperature (`bath_c`, half degrees) or the sauna's session temperature (`target_c`, whole degrees). */
    fun deadlineTarget(kind: String, targetC: Double): JSONObject =
        if (kind == "spa") deadline(kind, "bath_c", targetC) else deadline(kind, "target_c", Math.round(targetC).toInt())

    /** The weekly bath times (`baths`) or sessions (`sessions`), up to four; an empty list clears them. */
    fun weekly(kind: String, times: List<HeatLoads.Session>): JSONObject = deadline(
        kind,
        if (kind == "spa") "baths" else "sessions",
        JSONArray().apply { times.forEach { put(JSONObject().put("weekdays", JSONArray(it.weekdays)).put("time", it.time)) } }
    )

    /** The one-off bath (`bath_once`) or session (`session_once`) at [at], said in the site's [zone] with its offset; `null` clears it. */
    fun once(kind: String, at: Instant?, zone: ZoneId): JSONObject =
        deadline(kind, if (kind == "spa") "bath_once" else "session_once").apply {
            put("value", at?.atZone(zone)?.format(OFFSET_INSTANT) ?: JSONObject.NULL)
        }

    private val OFFSET_INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    sealed interface Outcome {
        data object Done : Outcome

        /** Refused by Home Assistant: [code] says why (`spotnav_rate_limited`, `spotnav_invalid_value`, ...). */
        data class Refused(val code: String?) : Outcome

        data object Unsupported : Outcome

        /** No answer, or one that could not be read. */
        data class Failed(val code: String?) : Outcome
    }

    /** The answer's envelope (the load's block itself is read again with `get_heat`). */
    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (head.error == WriteEnvelope.UNSUPPORTED_VERSION || head.error == "Unsupported action") return Outcome.Unsupported
        val version = head.json.opt("api_version")
        if (version != null && version != HeatRead.API_VERSION) return Outcome.Unsupported
        return if (head.ok) Outcome.Done else Outcome.Refused(head.error)
    }
}
