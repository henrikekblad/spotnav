package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatHeaterWindow
import se.sensnology.spotnav.ha.heat.HeatWaterHeater
import se.sensnology.spotnav.ha.heat.HeatZone
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * What SpotNav holds besides the heat pump, as the card words it (`zoneLine`, `waterHeaterLine`, `holdLines` of
 * `frontend/src/heat-view.ts`): each controlled zone's thermostat and the water heater's switch and windows. [now] and
 * [zone] are the answer's moment and time zone, the times the lines name.
 */
internal class HeatHoldText(private val text: SiteText, private val now: Instant, private val zone: ZoneId) {
    private val words = text.words

    private fun at(instant: Instant): String = HeatNowModel.moment(text, now, zone, instant)

    private fun degrees(value: Double): String = HeatNowModel.degrees(text, value)

    /** A zone's line under its row: what SpotNav holds on the thermostat, or what it waits for; `null` while it is watched only. */
    fun zoneLine(zone: HeatZone): String? {
        val control = zone.control
        if (control == null || !control.enabled || control.state == "off") return null
        if (control.state == "verifying") return words.say("heat.zone.verifying")
        if (control.state == "running") {
            val held = control.heldC
            if (held != null) {
                val until = control.until
                return if (until == null) words.say("heat.zone.heldNow", "value" to degrees(held))
                else words.say("heat.zone.held", "value" to degrees(held), "until" to at(until))
            }
            return control.homeC?.let { words.say("heat.zone.own", "value" to degrees(it)) }
        }
        val reason = control.reason.orEmpty()
        return if (reason in ZONE_WAITS) words.say("heat.zone.wait.$reason", "name" to control.automationName.orEmpty())
        else words.say("heat.zone.waiting")
    }

    /** The windows ahead as the strip says hours ("01:00–04:00, 14:00–17:00"), the first [count]. */
    fun windowsText(windows: List<HeatHeaterWindow>, count: Int = 3): String = windows
        .filter { it.end.isAfter(now) }
        .take(count)
        .joinToString(", ") { "${at(maxOf(it.start, now))}–${text.clock(it.end, zone)}" }

    /**
     * The water heater's line under its row: watched (the windows SpotNav would allow), on and why, off until when, or
     * what it waits for. [controlled] is the opt-in (`config.water_heater.control`).
     */
    fun waterHeaterLine(heater: HeatWaterHeater, controlled: Boolean): String? {
        val line = baseLine(heater, controlled)
        return if (line == null || !heater.meterWaiting) line else "$line ${words.say("heat.waterHeater.meterWaiting")}"
    }

    private fun baseLine(heater: HeatWaterHeater, controlled: Boolean): String? {
        if (!controlled || heater.state == "off") {
            val windows = windowsText(heater.windows)
            return if (windows.isEmpty()) words.say("heat.waterHeater.watchedNone") else words.say("heat.waterHeater.watched", "windows" to windows)
        }
        if (heater.state == "verifying" && heater.reason == "check") return words.say("heat.waterHeater.verifying")
        val reason = heater.reason.orEmpty()
        if (heater.state == "running" || heater.state == "verifying") {
            val until = heater.until
            // Paused by the shed order under the ceiling ("Pausad för taket till 18:00"), else off by the plan.
            if (heater.on == false && until != null) {
                return words.say(if (reason == "ceiling") "heat.waterHeater.ceiling" else "heat.waterHeater.off", "until" to at(until))
            }
            if (heater.on == true && reason in HEATER_WHY) {
                return words.say("heat.waterHeater.on", "why" to words.say("heat.waterHeater.why.$reason"))
            }
            return null
        }
        return if (reason in HEATER_WAITS) words.say("heat.waterHeater.wait.$reason", "name" to heater.automationName.orEmpty())
        else words.say("heat.zone.waiting")
    }

    /**
     * Now: one line per thing SpotNav holds now beside the pump ("Badrumsgolv 20,0 °C till 19:00", "Varmvattenberedaren
     * av till 01:00") and the water heater's next windows, each with the card's key.
     */
    fun holdLines(zones: List<HeatZone>, heater: HeatWaterHeater?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (zone in zones) {
            val control = zone.control
            val held = control?.heldC
            if (control == null || control.state != "running" || held == null) continue
            val until = control.until
            out += "zone:${zone.entityId}" to
                if (until == null) words.say("heat.now.zoneNow", "name" to zone.name, "value" to degrees(held))
                else words.say("heat.now.zone", "name" to zone.name, "value" to degrees(held), "until" to at(until))
        }
        if (heater != null) {
            val off = heater.until
            if (heater.live && heater.on == false && off != null) {
                val key = if (heater.reason == "ceiling") "heat.now.waterHeaterCeiling" else "heat.now.waterHeaterOff"
                out += "waterHeater" to words.say(key, "until" to at(off))
            }
            // The windows within the strip's twelve hours.
            val horizon = now.plus(Duration.ofHours(HeatNowModel.AHEAD_HOURS.toLong()))
            val windows = windowsText(heater.windows.filter { it.start.isBefore(horizon) })
            if (windows.isNotEmpty()) {
                out += "waterHeaterWindows" to words.say(if (heater.live) "heat.now.waterHeaterWindows" else "heat.now.waterHeaterWould", "windows" to windows)
            }
        }
        return out
    }

    companion object {
        private val ZONE_WAITS = setOf("automation", "foreign_change", "no_read_back", "check_failed", "not_qualified", "unavailable", "write_cap")
        private val HEATER_WAITS = setOf("automation", "foreign_on", "foreign_off", "no_read_back", "check_failed", "not_full", "unavailable")
        private val HEATER_WHY = setOf("cheap", "full_heat", "max_off", "sensor_lost", "no_plan", "tank_low", "write_cap")

        fun of(text: SiteText, heat: Heat) = HeatHoldText(text, heat.now, heat.zone)
    }
}
