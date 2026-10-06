package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaSettingsDriver

/**
 * The car on a paired charger's card, and in the charger drop-down's rows: its name and its levels,
 * "EV6 · 89 % → 93 %" when planning to a target, only "EV6 · 89 %" when planning an amount, "≈" before
 * a level Home Assistant estimated between readings and "–" for none. Always the car Home Assistant
 * plans for, never one saved in this app.
 */
internal object PairedCarLine {
    /** A car's level now (whole percent, `null` when not read), and the target when planning to one. */
    data class Levels(val now: Int?, val estimated: Boolean, val target: Int?, val targetMode: Boolean)

    fun levels(dashboard: Dashboard, vehicleId: String): Levels {
        val soc = dashboard.soc?.takeIf { it.vehicleId == vehicleId }
        val row = dashboard.vehicles.firstOrNull { it.id == vehicleId }
        val now = soc?.value ?: row?.socPercent
        val target = if (soc != null) {
            dashboard.settings?.target?.targetPercent ?: soc.targetPercent ?: row?.targetPercent
        } else {
            row?.targetPercent
        }
        return Levels(
            now = now?.let { SocDisplay.wholePercent(it) },
            estimated = soc != null && soc.value != null && soc.estimated,
            target = target?.let { SocDisplay.wholePercent(it) },
            targetMode = dashboard.settings?.driver == HaSettingsDriver.TARGET_SOC
        )
    }

    /** "89 % → 93 %", "≈ 91 % → 93 %", "– → 90 %" or "89 %"; `null` when there is nothing to say. */
    fun levelsText(levels: Levels, percent: (Int) -> String): String? {
        val now = levels.now?.let { (if (levels.estimated) "≈ " else "") + percent(it) }
        val target = levels.target?.takeIf { levels.targetMode } ?: return now
        return "${now ?: "–"} → ${percent(target)}"
    }

    /** The charger's car and its levels, "EV6 · 89 % → 93 %"; `null` when no car is planned for. */
    fun summary(dashboard: Dashboard, percent: (Int) -> String): String? {
        val line = VehicleIdentification.carLine(dashboard) ?: return null
        return listOfNotNull(line.name, levelsText(levels(dashboard, line.vehicleId), percent)).joinToString(" · ")
    }
}
