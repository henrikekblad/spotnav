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
 * [HeatWrite]; over the webhook that is the night, the stored zones' offsets and the stored indoor sensors'
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
    /** The friendly name the answer carries for [entityId]; `null` where it carries none (the id is never shown instead). */
    fun nameOf(entityId: String): String? = names[entityId]

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
        // The names of the entities to choose from (hot water, indoor, meter, control) and the outdoor sensor in use.
        json.optJSONObject("choices")?.let { choices ->
            for (key in choices.keys()) {
                (choices.opt(key) as? org.json.JSONArray)?.let { array ->
                    for (i in 0 until array.length()) {
                        val choice = array.optJSONObject(i) ?: continue
                        val id = choice.optString("entity_id").takeIf { it.isNotEmpty() }
                        val name = choice.optString("name").takeIf { it.isNotEmpty() }
                        if (id != null && name != null) names.putIfAbsent(id, name)
                    }
                }
            }
        }
        json.optJSONObject("outdoor")?.let { outdoor ->
            val id = outdoor.optString("entity_id").takeIf { it.isNotEmpty() }
            val name = outdoor.optString("name").takeIf { it.isNotEmpty() }
            if (id != null && name != null) names.putIfAbsent(id, name)
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
