package se.sensnology.spotnav.ha.heat

import org.json.JSONObject
import se.sensnology.spotnav.ha.house.StrictJson
import java.time.Instant

/**
 * SpotNav setting a zone's thermostat (`zones[].control` of `get_heat`, `heat/zones_control.py`): relative to the person's
 * own setpoint ([homeC]). `null` in [HeatZone.control] for a zone SpotNav only watches.
 */
internal data class HeatZoneControl(
    val enabled: Boolean,
    /** `off`, `verifying`, `running` or `waiting`. */
    val state: String,
    val reason: String?,
    val homeC: Double?,
    /** What SpotNav holds on the thermostat now; `null`: the person's own value stands. */
    val heldC: Double?,
    val until: Instant?,
    /** The automation SpotNav waits for, by its name. */
    val automationName: String?
)

/** One heat zone as the answer states it: its name and kind (`floor`, `air`) and SpotNav's control of it, if any. */
internal data class HeatZone(val entityId: String, val name: String, val kind: String, val control: HeatZoneControl?)

/** A window the water heater is allowed (or would be, watched only): [reason] is `cheap`, `full_heat` or `max_off`. */
internal data class HeatHeaterWindow(val start: Instant, val end: Instant, val reason: String)

/**
 * A plain water heater on a switch (`water_heater` of `get_heat`, `heat/water_heater_control.py`), watched or switched by
 * SpotNav: [live] while SpotNav switches it, [until] when what holds now ends, [lastFull] the last full heat seen.
 */
internal data class HeatWaterHeater(
    val entityId: String,
    val name: String,
    val on: Boolean?,
    /** `metered`, `tank` or `unmetered`. */
    val mode: String,
    /** `off`, `verifying`, `running` or `waiting`. */
    val state: String,
    val reason: String?,
    val lastFull: Instant?,
    val until: Instant?,
    val windows: List<HeatHeaterWindow>,
    val live: Boolean,
    val automationName: String?,
    /** The meter is not trusted yet: SpotNav has not seen the element draw 500 W (`meter_waiting`). */
    val meterWaiting: Boolean = false
)

/** Reads the zones' and the water heater's blocks of `get_heat`, the same for the heat page and the settings. */
internal object HeatZonesRead {
    fun zones(raw: Any?): List<HeatZone> = with(StrictJson) {
        list(raw, "zones").map { item ->
            val zone = obj(item, "zone")
            HeatZone(
                entityId = text(zone.opt("entity_id"), "zone.entity_id"),
                name = text(zone.opt("name"), "zone.name"),
                kind = text(zone.opt("kind"), "zone.kind"),
                control = additive(zone, "control", null) { objOrNull(it, "zone.control")?.let(::zoneControl) }
            )
        }
    }

    private fun zoneControl(json: JSONObject): HeatZoneControl = with(StrictJson) {
        HeatZoneControl(
            enabled = flag(json.opt("enabled"), "zone.control.enabled"),
            state = text(json.opt("state"), "zone.control.state"),
            reason = additive(json, "reason", null) { textOrNull(it, "zone.control.reason") },
            homeC = additive(json, "home_c", null) { numberOrNull(it, "zone.control.home_c") },
            heldC = additive(json, "held_c", null) { numberOrNull(it, "zone.control.held_c") },
            until = additive(json, "until", null) { instantOrNull(it, "zone.control.until") },
            automationName = additive(json, "automation", null) { objOrNull(it, "zone.control.automation")?.let { a -> text(a.opt("name"), "automation.name") } }
        )
    }

    fun waterHeater(raw: Any?): HeatWaterHeater? = with(StrictJson) {
        val json = objOrNull(raw, "water_heater") ?: return null
        HeatWaterHeater(
            entityId = text(json.opt("entity_id"), "water_heater.entity_id"),
            name = text(json.opt("name"), "water_heater.name"),
            on = if (json.opt("on") === JSONObject.NULL) null else flag(json.opt("on"), "water_heater.on"),
            mode = text(json.opt("mode"), "water_heater.mode"),
            state = text(json.opt("state"), "water_heater.state"),
            reason = additive(json, "reason", null) { textOrNull(it, "water_heater.reason") },
            lastFull = additive(json, "last_full", null) { instantOrNull(it, "water_heater.last_full") },
            until = additive(json, "until", null) { instantOrNull(it, "water_heater.until") },
            windows = list(json.opt("windows"), "water_heater.windows").map {
                val window = obj(it, "water_heater.window")
                HeatHeaterWindow(
                    instant(window.opt("start"), "window.start"),
                    instant(window.opt("end"), "window.end"),
                    text(window.opt("reason"), "window.reason")
                )
            },
            live = flag(json.opt("live"), "water_heater.live"),
            meterWaiting = additive(json, "meter_waiting", false) { flag(it, "water_heater.meter_waiting") },
            automationName = additive(json, "automation", null) { objOrNull(it, "water_heater.automation")?.let { a -> text(a.opt("name"), "automation.name") } }
        )
    }
}
