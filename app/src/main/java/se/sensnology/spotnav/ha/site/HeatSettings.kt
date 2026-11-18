package se.sensnology.spotnav.ha.site

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.house.StrictJson

/** One entity's offset against the house temperature, in degrees (a zone's or an indoor sensor's). */
internal data class HeatOffset(val entityId: String, val offsetC: Double)

/** The cooler night: [deltaC] lower between [start] and [end] (`HH:MM`). */
internal data class HeatNight(val on: Boolean, val deltaC: Double, val start: String, val end: String)

/**
 * The heat settings as the Settings screen reads them (webhook `get_heat`, the twin of `spotnav/get_heat`,
 * `api_version` 1): the stored `config` and the names the answer gives the entities in it. Written back with
 * [HeatSettingsUpdate]; over the webhook that is the night, the stored zones' offsets and the stored indoor sensors'
 * offsets only.
 */
internal data class HeatSettings(
    val controlEnabled: Boolean,
    /** `none`, `relay`, `sg_ready`, `offset`, `setpoint` or a newer word. */
    val controlPath: String,
    val controlEntity: String?,
    val controlEntityB: String?,
    val hotWaterEntity: String?,
    val meterEntity: String?,
    val outdoorSensor: String?,
    val indoorSensors: List<String>,
    /** Sparse, as stored: a sensor left out (or at 0) has no offset. */
    val indoorOffsets: List<HeatOffset>,
    val night: HeatNight,
    /** The stored zones in their stored order. */
    val zones: List<HeatOffset>,
    /** Entity id to the name Home Assistant shows for it. */
    val names: Map<String, String>,
    /** The zone's kind (`floor`, `air`, ...) by entity id. */
    val zoneKinds: Map<String, String>
) {
    fun nameOf(entityId: String): String = names[entityId] ?: entityId

    fun indoorOffsetOf(entityId: String): Double = indoorOffsets.firstOrNull { it.entityId == entityId }?.offsetC ?: 0.0
}

internal object HeatSettingsRead {
    const val API_VERSION = 1

    fun payload(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_heat")
        put("api_version", API_VERSION)
    }

    fun answer(status: Int?, body: String?): SiteRead<HeatSettings> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    fun decode(json: JSONObject): SiteRead<HeatSettings> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private fun document(json: JSONObject): HeatSettings = with(StrictJson) {
        val config = obj(json.opt("config"), "config")
        val night = obj(config.opt("night"), "night")
        val names = LinkedHashMap<String, String>()
        val kinds = LinkedHashMap<String, String>()
        list(obj(json.opt("indoor"), "indoor").opt("sensors"), "indoor.sensors").forEach {
            val sensor = obj(it, "indoor.sensor")
            names[text(sensor.opt("entity_id"), "entity_id")] = text(sensor.opt("name"), "name")
        }
        list(json.opt("zones"), "zones").forEach {
            val zone = obj(it, "zone")
            val id = text(zone.opt("entity_id"), "entity_id")
            names[id] = text(zone.opt("name"), "name")
            kinds[id] = text(zone.opt("kind"), "kind")
        }
        // The meter, when found: its name for the read-only row. `null` without a meter.
        objOrNull(json.opt("meter"), "meter")?.let { meter ->
            val id = textOrNull(meter.opt("entity_id"), "entity_id")
            val name = textOrNull(meter.opt("name"), "name")
            if (id != null && name != null) names[id] = name
        }
        HeatSettings(
            controlEnabled = flag(config.opt("control_enabled"), "control_enabled"),
            controlPath = text(config.opt("control_path"), "control_path"),
            controlEntity = textOrNull(config.opt("control_entity"), "control_entity"),
            controlEntityB = textOrNull(config.opt("control_entity_b"), "control_entity_b"),
            hotWaterEntity = textOrNull(config.opt("hot_water_entity"), "hot_water_entity"),
            meterEntity = textOrNull(config.opt("heat_meter_entity"), "heat_meter_entity"),
            outdoorSensor = textOrNull(config.opt("outdoor_sensor"), "outdoor_sensor"),
            indoorSensors = list(config.opt("indoor_sensors"), "indoor_sensors").map { text(it, "indoor_sensors") },
            indoorOffsets = offsets(config.opt("indoor_offsets"), "indoor_offsets"),
            night = HeatNight(
                on = flag(night.opt("on"), "night.on"),
                deltaC = number(night.opt("delta_c"), "night.delta_c"),
                start = time(night.opt("start"), "night.start"),
                end = time(night.opt("end"), "night.end")
            ),
            zones = offsets(config.opt("zones"), "zones"),
            names = names,
            zoneKinds = kinds
        )
    }

    private fun offsets(raw: Any?, what: String): List<HeatOffset> = StrictJson.list(raw, what).map {
        val item = StrictJson.obj(it, what)
        HeatOffset(StrictJson.text(item.opt("entity_id"), what), StrictJson.number(item.opt("offset_c"), what))
    }

    private fun time(raw: Any?, what: String): String {
        val text = StrictJson.text(raw, what)
        if (!Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(text)) StrictJson.bad(what)
        return text
    }
}

/**
 * `update_heat` for the fields the Settings screen writes: the night and the offsets (`changes` and `expected`, the
 * values last read, a compare-and-set). The dial and home/away are the heat page's own writes.
 */
internal object HeatSettingsUpdate {
    const val API_VERSION = 1
    const val NIGHT_MIN = 0.5
    const val NIGHT_MAX = 3.0
    const val OFFSET_MIN = -3.0
    const val OFFSET_MAX = 3.0

    sealed interface Outcome {
        /** Written: the settings are to be read again. */
        data object Updated : Outcome

        /** Somebody else changed the value since it was read: nothing was written. */
        data object Conflict : Outcome

        /** Refused as invalid; the stable codes of the fields (`out_of_range`, `not_a_step`, ...). */
        data class Refused(val codes: List<String>) : Outcome

        /** Home Assistant's alone (`spotnav_not_permitted_over_webhook`). */
        data object NotPermitted : Outcome
        data object NoSite : Outcome
        data object NotSupported : Outcome
        data class Failed(val code: String?) : Outcome
    }

    /** A request: what to change and the values it was judged against. */
    class Request(val changes: JSONObject, val expected: JSONObject) {
        fun payload(): JSONObject = JSONObject().apply {
            put("version", 1)
            put("action", "update_heat")
            put("api_version", API_VERSION)
            put("changes", changes)
            put("expected", expected)
        }
    }

    fun night(stored: HeatSettings, next: HeatNight): Request = Request(
        changes = JSONObject().put("night", nightJson(next)),
        expected = JSONObject().put("night", nightJson(stored.night))
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

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (head.ok) return if (head.error == null) Outcome.Updated else Outcome.Failed(head.error)
        return when (head.error) {
            WriteEnvelope.CONFLICT -> Outcome.Conflict
            WriteEnvelope.NOT_PERMITTED_OVER_WEBHOOK -> Outcome.NotPermitted
            WriteEnvelope.INVALID_VALUE -> Outcome.Refused(head.fieldErrors.map { it.code })
            WriteEnvelope.UNSUPPORTED_VERSION -> Outcome.NotSupported
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
