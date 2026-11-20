package se.sensnology.spotnav.ha.heat

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.house.StrictJson
import se.sensnology.spotnav.ha.site.HeatNight
import se.sensnology.spotnav.ha.site.HeatOffset
import se.sensnology.spotnav.ha.site.HeatSettings
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The site's heating (webhook `get_heat`, the twin of `spotnav/get_heat`, `api_version` 1), as far as the heat page's
 * Now box and the Plan box read it: the dial's target and mode, the comfort band, the measured rooms, today's figures,
 * what the heat pump does and what SpotNav would do, the plan's next hours and the notes, the day's plan by the hour
 * (`band`, `indoor_day`) and yesterday's `review`. The settings' blocks (`choices`, the sensors' own rows) are not
 * read here. Shape: the
 * integration's `heat/view.py`; the card's reader is `frontend/src/heat-model.ts`. Over the webhook `writable` is
 * `false` and `choices` `null`: the configuration stays in Home Assistant.
 */
internal data class Heat(
    val siteId: String,
    val now: Instant,
    val zone: ZoneId,
    /** `false` only while SpotNav's own heat control runs; until then the page says Test. */
    val shadow: Boolean,
    val config: Config,
    val comfort: Comfort,
    val indoor: Indoor,
    /** The outdoor reading (°C), `null` without one. */
    val outdoorC: Double?,
    /** What the site has to show: a heat pump, a heat meter, heat zones and indoor sensors. */
    val hasPump: Boolean,
    val hasMeter: Boolean,
    val zones: Int,
    val today: Today?,
    val status: Status,
    /** The hours' relay minutes, in order. */
    val relayHours: List<RelayHour>,
    val relay: Relay?,
    val control: Control?,
    val ahead: List<AheadHour>,
    val notes: List<Note>,
    /** Today's plan hour by hour, for the Plan box's chart. */
    val band: List<HeatBandHour> = emptyList(),
    /** The day's measured rooms up to now; `null` without indoor sensors. */
    val indoorDay: HeatIndoorDay? = null,
    /** Yesterday's review under today's chart; `null` without one. */
    val review: HeatReview? = null,
    /** The site's hot water (`heat/dhw_runtime.py`); `null` without the heat shadow or from an older Home Assistant. */
    val hotWater: HeatHotWater? = null,
    /** An administrator's answer (`writable`); `false` over the webhook. */
    val writable: Boolean = false
) {
    /** What the dial and Home/Away are written against. The raw text of `away_until` is what the compare-and-set sends back. */
    data class Config(
        val targetC: Double,
        val mode: String,
        val awayUntilRaw: String?,
        val controlPath: String,
        val controlEntity: String?,
        val controlEntityB: String?,
        /** SpotNav's own steering is on (`config.control_enabled`). */
        val controlEnabled: Boolean = false
    )

    data class Comfort(val targetC: Double, val lowC: Double, val highC: Double, val mode: String, val awayUntil: Instant?, val aimC: Double)

    data class Indoor(val valueC: Double?, val coldest: String?, val coldestName: String?, val sensorsRead: Int)

    data class Today(
        val heatKwh: Double,
        val hotWaterKwh: Double?,
        /** The zones' own energy, already in [heatKwh]. */
        val zonesKwh: Double?,
        /** In the minor unit. */
        val cost: Double?,
        val money: House.Money?,
        /** Today's hot-water runs by the meter (start, end), drawn where the hot water has none of its own. */
        val runs: List<Pair<Instant, Instant>> = emptyList()
    )

    data class Would(val action: String, val targetC: Double, val until: Instant?, val reason: String)

    data class Status(val pumpState: String?, val kw: Double?, val would: Would?, val guard: String?)

    data class RelayHour(val start: Instant, val relayMin: Double?, val relayExpected: Boolean)

    data class Relay(val closed: Boolean?, val since: Instant?)

    data class Control(val enabled: Boolean, val state: String, val lowered: Boolean, val raised: Boolean, val until: Instant?, val raiseUntil: Instant?)

    data class AheadHour(val start: Instant, val action: String, val targetC: Double, val hotWater: Boolean)

    data class Note(val code: String, val params: Map<String, Any?>)

    /** Whether the heat page has anything to show: a meter, a pump, heat zones or indoor sensors. */
    val hasSomething: Boolean get() = hasMeter || hasPump || zones > 0 || indoor.sensorsRead > 0 || indoor.valueC != null
}

/** The card's `heatSteers()` (`frontend/src/heat-model.ts`): SpotNav steers the heating on these paths, or while its steering is on. */
internal object HeatSteering {
    /** The control paths SpotNav steers itself ("Styr värmen" has a row on them). */
    val STEERED_PATHS = setOf("relay", "sg_ready", "sg_ready_api", "offset")

    fun steers(controlPath: String, controlEnabled: Boolean): Boolean = controlPath in STEERED_PATHS || controlEnabled
}

internal object HeatRead {
    const val API_VERSION = 1

    fun payload(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_heat")
        put("api_version", API_VERSION)
    }

    fun answer(status: Int?, body: String?): SiteRead<Heat> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The document itself, as the webhook and the WebSocket both send it. */
    fun decode(json: JSONObject): SiteRead<Heat> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private fun document(json: JSONObject): Heat = with(StrictJson) {
        val config = obj(json.opt("config"), "config")
        val comfort = obj(json.opt("comfort"), "comfort")
        val indoor = obj(json.opt("indoor"), "indoor")
        val status = obj(json.opt("status"), "status")
        Heat(
            siteId = text(json.opt("site_id"), "site_id"),
            now = instant(json.opt("now"), "now"),
            zone = runCatching { ZoneId.of(text(json.opt("time_zone"), "time_zone")) }.getOrElse { bad("time_zone") },
            shadow = flag(json.opt("shadow"), "shadow"),
            config = Heat.Config(
                targetC = number(config.opt("target_c"), "config.target_c"),
                mode = text(config.opt("mode"), "config.mode"),
                awayUntilRaw = textOrNull(config.opt("away_until"), "config.away_until"),
                controlPath = text(config.opt("control_path"), "config.control_path"),
                controlEntity = textOrNull(config.opt("control_entity"), "config.control_entity"),
                controlEntityB = textOrNull(config.opt("control_entity_b"), "config.control_entity_b"),
                controlEnabled = additive(config, "control_enabled", false) { flag(it, "config.control_enabled") }
            ),
            comfort = Heat.Comfort(
                targetC = number(comfort.opt("target_c"), "comfort.target_c"),
                lowC = number(comfort.opt("low_c"), "comfort.low_c"),
                highC = number(comfort.opt("high_c"), "comfort.high_c"),
                mode = text(comfort.opt("mode"), "comfort.mode"),
                awayUntil = instantOrNull(comfort.opt("away_until"), "comfort.away_until"),
                aimC = number(comfort.opt("aim_c"), "comfort.aim_c")
            ),
            indoor = Heat.Indoor(
                valueC = numberOrNull(indoor.opt("value_c"), "indoor.value_c"),
                coldest = textOrNull(indoor.opt("coldest"), "indoor.coldest"),
                coldestName = textOrNull(indoor.opt("coldest_name"), "indoor.coldest_name"),
                sensorsRead = list(indoor.opt("sensors"), "indoor.sensors").count {
                    numberOrNull(obj(it, "indoor.sensor").opt("value_c"), "indoor.sensor.value_c") != null
                }
            ),
            outdoorC = objOrNull(json.opt("outdoor"), "outdoor")?.let { numberOrNull(it.opt("value_c"), "outdoor.value_c") },
            hasPump = objOrNull(json.opt("pump"), "pump") != null,
            hasMeter = objOrNull(json.opt("meter"), "meter")?.let {
                // A meter that is not found or usable is something to set up, not something to show.
                text(it.opt("status"), "meter.status") == "found"
            } ?: false,
            zones = list(json.opt("zones"), "zones").onEach { obj(it, "zone") }.size,
            today = objOrNull(json.opt("today"), "today")?.let(::today),
            status = Heat.Status(
                pumpState = textOrNull(status.opt("pump_state"), "status.pump_state"),
                kw = numberOrNull(status.opt("kw"), "status.kw"),
                would = objOrNull(status.opt("would"), "status.would")?.let {
                    Heat.Would(
                        action = text(it.opt("action"), "would.action"),
                        targetC = number(it.opt("target_c"), "would.target_c"),
                        until = instantOrNull(it.opt("until"), "would.until"),
                        reason = text(it.opt("reason"), "would.reason")
                    )
                },
                guard = textOrNull(status.opt("guard"), "status.guard")
            ),
            relayHours = list(obj(json.opt("band"), "band").opt("hours"), "band.hours").map { raw ->
                val hour = obj(raw, "band.hour")
                Heat.RelayHour(
                    start = instant(hour.opt("start"), "band.hour.start"),
                    relayMin = numberOrNull(hour.opt("relay_min"), "band.hour.relay_min"),
                    relayExpected = flag(hour.opt("relay_expected"), "band.hour.relay_expected")
                )
            },
            relay = objOrNull(json.opt("relay"), "relay")?.let {
                Heat.Relay(
                    closed = if (it.opt("closed") === JSONObject.NULL) null else flag(it.opt("closed"), "relay.closed"),
                    since = instantOrNull(it.opt("since"), "relay.since")
                )
            },
            control = objOrNull(json.opt("control"), "control")?.let {
                Heat.Control(
                    enabled = flag(it.opt("enabled"), "control.enabled"),
                    state = text(it.opt("state"), "control.state"),
                    lowered = flag(it.opt("lowered"), "control.lowered"),
                    raised = flag(it.opt("raised"), "control.raised"),
                    until = instantOrNull(it.opt("until"), "control.until"),
                    raiseUntil = instantOrNull(it.opt("raise_until"), "control.raise_until")
                )
            },
            ahead = list(json.opt("ahead"), "ahead").map { raw ->
                val hour = obj(raw, "ahead.hour")
                Heat.AheadHour(
                    start = instant(hour.opt("start"), "ahead.start"),
                    action = text(hour.opt("action"), "ahead.action"),
                    targetC = number(hour.opt("target_c"), "ahead.target_c"),
                    hotWater = flag(hour.opt("hot_water"), "ahead.hot_water")
                )
            },
            notes = list(json.opt("notes"), "notes").map { raw ->
                val note = obj(raw, "note")
                Heat.Note(text(note.opt("code"), "note.code"), params(obj(note.opt("params"), "note.params")))
            },
            band = list(obj(json.opt("band"), "band").opt("hours"), "band.hours").map(HeatDayRead::bandHour),
            indoorDay = additive(json, "indoor_day", null) { objOrNull(it, "indoor_day")?.let(HeatDayRead::indoorDay) },
            review = additive(json, "review", null) { objOrNull(it, "review")?.let(HeatDayRead::review) },
            hotWater = additive(json, "hot_water", null) { objOrNull(it, "hot_water")?.let(HeatHotWaterRead::hotWater) },
            writable = additive(json, "writable", false) { flag(it, "writable") }
        )
    }

    private fun today(json: JSONObject): Heat.Today = with(StrictJson) {
        Heat.Today(
            heatKwh = number(json.opt("heat_kwh"), "today.heat_kwh"),
            hotWaterKwh = numberOrNull(json.opt("hot_water_kwh"), "today.hot_water_kwh"),
            zonesKwh = numberOrNull(json.opt("zones_kwh"), "today.zones_kwh"),
            cost = numberOrNull(json.opt("cost"), "today.cost"),
            money = objOrNull(json.opt("money"), "today.money")?.let {
                House.Money(
                    currency = textOrNull(it.opt("currency"), "money.currency"),
                    majorUnit = textOrNull(it.opt("major_unit"), "money.major_unit"),
                    minorUnit = textOrNull(it.opt("minor_unit"), "money.minor_unit")
                )
            },
            runs = additive(json, "runs", emptyList()) { raw ->
                list(raw, "today.runs").map {
                    val run = obj(it, "today.run")
                    instant(run.opt("start"), "today.run.start") to instant(run.opt("end"), "today.run.end")
                }
            }
        )
    }

    /** A note's parameters as plain values: text, flags, numbers and lists of them; anything else is left out. */
    private fun params(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
        when (val raw = json.opt(key)) {
            is JSONArray -> (0 until raw.length()).map { raw.opt(it) }.filter { it is String || it is Boolean || it is Number }
            is String, is Boolean, is Number -> raw
            else -> null
        }
    }
}

/**
 * `update_heat`, the one write of the heating (webhook `update_heat`): a [Request] of `changes` and the values they were
 * judged against (`expected`, a compare-and-set). The heat page writes the dial and Home/Away ([Change]); the Settings
 * screen writes the night and the offsets ([night], [zoneOffset], [indoorOffset]). One [Outcome] for both.
 */
internal object HeatWrite {
    const val NIGHT_MIN = 0.5
    const val NIGHT_MAX = 3.0
    const val AWAY_DELTA_MIN = 1.0
    const val AWAY_DELTA_MAX = 6.0
    const val OFFSET_MIN = -3.0
    const val OFFSET_MAX = 3.0

    /** What the heat page may change: the dial and Home/Away, by the field names Home Assistant answers. */
    sealed interface Change {
        val key: String

        data class Target(val targetC: Double, val expected: Double) : Change {
            override val key get() = "target_c"
        }

        /** Home or away; [until] only with away (an instant), [expectedUntil] the stored text. */
        data class Mode(val mode: String, val until: Instant?, val expectedMode: String, val expectedUntil: String?) : Change {
            override val key get() = "mode"
        }
    }

    /** A request: what to change and the values it was judged against. */
    class Request(val changes: JSONObject, val expected: JSONObject) {
        fun payload(): JSONObject = JSONObject().apply {
            put("version", 1)
            put("action", "update_heat")
            put("api_version", HeatRead.API_VERSION)
            put("changes", changes)
            put("expected", expected)
        }
    }

    fun request(change: Change): Request {
        val changes = JSONObject()
        val expected = JSONObject()
        when (change) {
            is Change.Target -> {
                changes.put("target_c", change.targetC)
                expected.put("target_c", change.expected)
            }
            is Change.Mode -> {
                changes.put("mode", change.mode)
                expected.put("mode", change.expectedMode)
                if (change.mode == "away") {
                    changes.put("away_until", change.until?.truncatedTo(ChronoUnit.SECONDS)?.toString() ?: JSONObject.NULL)
                }
                // Home clears the return time: expected is what was stored, so another phone's change is not undone.
                expected.put("away_until", change.expectedUntil ?: JSONObject.NULL)
            }
        }
        return Request(changes, expected)
    }

    fun payload(change: Change): JSONObject = request(change).payload()

    fun night(stored: HeatSettings, next: HeatNight): Request = Request(
        changes = JSONObject().put("night", nightJson(next)),
        expected = JSONObject().put("night", nightJson(stored.night))
    )

    /** The setback while away; [stored] must carry one (the row is only shown then). */
    fun awayDelta(stored: HeatSettings, next: Double): Request = Request(
        changes = JSONObject().put("away_delta_c", next),
        expected = JSONObject().put("away_delta_c", stored.awayDeltaC ?: JSONObject.NULL)
    )

    /** One stored zone's offset; the list names exactly the stored zones in their stored order, as the webhook takes it. */
    fun zoneOffset(stored: HeatSettings, entityId: String, offsetC: Double): Request = Request(
        changes = JSONObject().put("zones", offsetsJson(stored.zones.map { if (it.entityId == entityId) it.copy(offsetC = offsetC) else it })),
        expected = JSONObject().put("zones", offsetsJson(stored.zones))
    )

    /** One stored indoor sensor's offset; the list stays sparse (a sensor at 0 is left out) and names stored sensors only. */
    fun indoorOffset(stored: HeatSettings, entityId: String, offsetC: Double): Request {
        val next = stored.indoorSensors
            .map { HeatOffset(it, if (it == entityId) offsetC else stored.indoorOffsetOf(it)) }
            .filter { it.offsetC != 0.0 }
        return Request(
            changes = JSONObject().put("indoor_offsets", offsetsJson(next)),
            expected = JSONObject().put("indoor_offsets", offsetsJson(stored.indoorOffsets))
        )
    }

    /** Whether [value] is a whole number of half degrees. */
    fun isHalfStep(value: Double): Boolean = Math.abs(value * 2 - Math.round(value * 2)) < 1e-9

    sealed interface Outcome {
        /** Written (the settings are to be read again). */
        data object Written : Outcome

        /** Somebody else changed it since it was read: nothing was written. */
        data object Conflict : Outcome

        /** A value Home Assistant refuses ([codes] are the fields' codes such as `out_of_range`). */
        data class Refused(val codes: List<String>) : Outcome

        /** The change is one only an administrator in Home Assistant may make. */
        data object NotPermitted : Outcome

        data object NoSite : Outcome

        data object Unsupported : Outcome

        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        val version = head.json.opt("api_version")
        if (version != null && version != HeatRead.API_VERSION) {
            return if (head.error == WriteEnvelope.UNSUPPORTED_VERSION) Outcome.Unsupported else Outcome.Failed(head.error)
        }
        if (head.ok) return if (head.error == null) Outcome.Written else Outcome.Failed(head.error)
        return when (head.error) {
            WriteEnvelope.CONFLICT -> Outcome.Conflict
            WriteEnvelope.INVALID_VALUE -> Outcome.Refused(head.fieldErrors.map { it.code })
            WriteEnvelope.NOT_PERMITTED_OVER_WEBHOOK, "spotnav_not_admin" -> Outcome.NotPermitted
            WriteEnvelope.UNSUPPORTED_VERSION -> Outcome.Unsupported
            WriteEnvelope.NO_SITE, WriteEnvelope.SITE_UNAVAILABLE -> Outcome.NoSite
            else -> Outcome.Failed(head.error)
        }
    }

    private fun nightJson(night: HeatNight) =
        JSONObject().put("on", night.on).put("delta_c", night.deltaC).put("start", night.start).put("end", night.end)

    private fun offsetsJson(offsets: List<HeatOffset>) = JSONArray().apply {
        offsets.forEach { put(JSONObject().put("entity_id", it.entityId).put("offset_c", it.offsetC)) }
    }
}
