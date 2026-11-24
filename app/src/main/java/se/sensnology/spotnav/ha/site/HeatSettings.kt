package se.sensnology.spotnav.ha.site

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.heat.HeatLoads
import se.sensnology.spotnav.ha.heat.HeatWaterHeater
import se.sensnology.spotnav.ha.heat.HeatZone
import se.sensnology.spotnav.ha.heat.HeatZonesRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.house.StrictJson
import java.time.Instant
import java.time.ZoneId

/** One entity's offset against the house temperature, in degrees (an indoor sensor's). */
internal data class HeatOffset(val entityId: String, val offsetC: Double)

/**
 * SpotNav's own steering of the pump ("Styr värmen", `control` of `get_heat`) as far as the settings' line under it
 * says it: the state and its reason, what it holds and until when, whether the path is untested, and a stored offset
 * that was not put back ([offsetHome]).
 */
internal data class HeatControlStatus(
    val enabled: Boolean,
    /** `off`, `verifying`, `running` or `waiting`. */
    val state: String,
    val reason: String?,
    /** The automation that steers the pump, by its name, while the control waits for it. */
    val automationName: String?,
    val lowered: Boolean,
    val raised: Boolean,
    val until: Instant?,
    val raiseUntil: Instant?,
    /** The path has not run on a real heat pump yet. */
    val untested: Boolean,
    /** A stored offset reading other than the household's own value; `null` when the two agree. */
    val offsetHome: OffsetHome?
) {
    /** The offset now ([value]) and the household's own ([home]); put back or kept in Home Assistant only. */
    data class OffsetHome(val entityId: String, val value: Double, val home: Double)
}

/** A stored zone and whether SpotNav sets its thermostat (`config.zones[].control`, an administrator's opt-in in Home Assistant). */
internal data class HeatZoneSetting(val entityId: String, val control: Boolean)

/** The plain water heater's settings (`config.water_heater`): its switch, the opt-in to switch it and the optional tank sensor. */
internal data class HeatWaterHeaterSetting(val entityId: String, val control: Boolean, val tankSensor: String?)

/** The cooler night: [deltaC] lower between [start] and [end] (`HH:MM`). */
internal data class HeatNight(val on: Boolean, val deltaC: Double, val start: String, val end: String)

/**
 * The heat settings as the Settings screen reads them (webhook `get_heat`, the twin of `spotnav/get_heat`,
 * `api_version` 1): the stored `config` and the names the answer gives the entities in it. Written back with
 * [HeatWrite]; over the webhook that is the night, the away setback and the stored indoor sensors' offsets only. The
 * zones' and the water heater's opt-ins are an administrator's in Home Assistant: read only here.
 */
internal data class HeatSettings(
    val controlEnabled: Boolean,
    /** `none`, `relay`, `sg_ready`, `sg_ready_api`, `offset`, `setpoint` or a newer word. */
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
    /** The stored zones in their stored order, each with SpotNav's opt-in to set it (read only here). */
    val zones: List<HeatZoneSetting>,
    /** Entity id to the name Home Assistant shows for it. */
    val names: Map<String, String>,
    /** The zone's kind (`floor`, `air`, ...) by entity id. */
    val zoneKinds: Map<String, String>,
    /** The zones as the answer reads them now, with SpotNav's control of each, by entity id. */
    val zoneStatus: Map<String, HeatZone> = emptyMap(),
    /** The water heater's switch, opt-in and tank sensor; `null` without one chosen. */
    val waterHeater: HeatWaterHeaterSetting? = null,
    /** The water heater as the answer reads it now; `null` without one. */
    val waterHeaterStatus: HeatWaterHeater? = null,
    /** The setback while away, in degrees (1 to 6); `null` from a Home Assistant that does not have the setting (no row). */
    val awayDeltaC: Double? = null,
    /** The control's status for the line under "Styr värmen"; `null` without one. */
    val control: HeatControlStatus? = null,
    /** The answer's moment and time zone, for the times the line names. */
    val now: Instant? = null,
    val zone: ZoneId? = null,
    /** Why SpotNav would lower now (`status.would.reason`): "room_warm" words the lowering. */
    val wouldReason: String? = null,
    /**
     * Whether anything heat-related was found, by the card's `heatFound()`: a heat pump, a meter, climate zones (found
     * or to choose), or settings already made (a control path, indoor sensors, a heat meter). Without it the heat
     * settings card is one "Nothing found" row.
     */
    val found: Boolean = true,
    /** The pool's, the spa's and the sauna's stored settings; `null` without the load set up (read only here). */
    val pool: HeatLoadSettings.Pool? = null,
    val spa: HeatLoadSettings.Spa? = null,
    val sauna: HeatLoadSettings.Sauna? = null,
    /** The three loads as the answer reads them now (their power, what SpotNav would do); `null` without the load. */
    val poolStatus: HeatLoads.Pool? = null,
    val spaStatus: HeatLoads.Deadline? = null,
    val saunaStatus: HeatLoads.Deadline? = null
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
        // The water heater's own name, for its read-only row.
        json.optJSONObject("water_heater")?.let { heater ->
            names[text(heater.opt("entity_id"), "entity_id")] = text(heater.opt("name"), "name")
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
            zones = list(config.opt("zones"), "zones").map {
                val zone = obj(it, "zones")
                HeatZoneSetting(text(zone.opt("entity_id"), "zones"), additive(zone, "control", false) { it2 -> flag(it2, "zones.control") })
            },
            names = names,
            zoneKinds = kinds,
            zoneStatus = additive(json, "zones", emptyMap()) { raw -> HeatZonesRead.zones(raw).associateBy { it.entityId } },
            waterHeater = additive(config, "water_heater", null) { raw ->
                objOrNull(raw, "config.water_heater")?.let {
                    HeatWaterHeaterSetting(
                        entityId = text(it.opt("entity_id"), "water_heater.entity_id"),
                        control = flag(it.opt("control"), "water_heater.control"),
                        tankSensor = additive(it, "tank_sensor", null) { sensor -> textOrNull(sensor, "water_heater.tank_sensor") }
                    )
                }
            },
            waterHeaterStatus = additive(json, "water_heater", null) { HeatZonesRead.waterHeater(it) },
            awayDeltaC = if (config.has("away_delta_c")) number(config.opt("away_delta_c"), "away_delta_c") else null,
            control = additive(json, "control", null) { objOrNull(it, "control")?.let(::control) },
            now = additive(json, "now", null) { instant(it, "now") },
            zone = additive(json, "time_zone", null) { raw -> runCatching { ZoneId.of(text(raw, "time_zone")) }.getOrElse { bad("time_zone") } },
            wouldReason = additive(json, "status", null) { raw ->
                objOrNull(raw, "status")?.let { status -> objOrNull(status.opt("would"), "status.would")?.let { text(it.opt("reason"), "would.reason") } }
            },
            found = found(json, config),
            pool = additive(config, "pool", null) { HeatLoadSettings.pool(it) },
            spa = additive(config, "spa", null) { HeatLoadSettings.spa(it) },
            sauna = additive(config, "sauna", null) { HeatLoadSettings.sauna(it) },
            // The loads' live blocks only add the power and the would-line under the sections: one the app cannot read leaves those out.
            poolStatus = runCatching { HeatLoads.pool(json.opt("pool") ?: JSONObject.NULL) }.getOrNull(),
            spaStatus = runCatching { HeatLoads.deadline(json.opt("spa") ?: JSONObject.NULL, "spa") }.getOrNull(),
            saunaStatus = runCatching { HeatLoads.deadline(json.opt("sauna") ?: JSONObject.NULL, "sauna") }.getOrNull()
        )
    }

    /** The card's `heatFound()` (`frontend/src/heat-model.ts`), read leniently: a part the answer lacks counts as none. */
    private fun found(json: JSONObject, config: JSONObject): Boolean {
        val meter = json.optJSONObject("meter")
        val meterId = meter?.opt("entity_id")?.takeUnless { it === JSONObject.NULL }
        val choiceZones = json.optJSONObject("choices")?.optJSONArray("zones")?.length() ?: 0
        val meterEntity = config.opt("heat_meter_entity")?.takeUnless { it === JSONObject.NULL }
        return json.optJSONObject("pump") != null ||
            meterId != null ||
            (json.optJSONArray("zones")?.length() ?: 0) > 0 ||
            choiceZones > 0 ||
            config.optString("control_path", "none") != "none" ||
            (config.optJSONArray("indoor_sensors")?.length() ?: 0) > 0 ||
            meterEntity != null
    }

    private fun control(json: JSONObject): HeatControlStatus = with(StrictJson) {
        HeatControlStatus(
            enabled = flag(json.opt("enabled"), "control.enabled"),
            state = text(json.opt("state"), "control.state"),
            reason = textOrNull(json.opt("reason"), "control.reason"),
            automationName = objOrNull(json.opt("automation"), "control.automation")?.let { text(it.opt("name"), "automation.name") },
            lowered = flag(json.opt("lowered"), "control.lowered"),
            raised = flag(json.opt("raised"), "control.raised"),
            until = instantOrNull(json.opt("until"), "control.until"),
            raiseUntil = instantOrNull(json.opt("raise_until"), "control.raise_until"),
            untested = additive(json, "untested", false) { flag(it, "control.untested") },
            offsetHome = additive(json, "offset_home", null) { raw ->
                objOrNull(raw, "control.offset_home")?.let {
                    HeatControlStatus.OffsetHome(
                        entityId = text(it.opt("entity_id"), "offset_home.entity_id"),
                        value = number(it.opt("value"), "offset_home.value"),
                        home = number(it.opt("home"), "offset_home.home")
                    )
                }
            }
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
