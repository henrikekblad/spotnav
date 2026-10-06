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

    /**
     * The car the charger card's re-read also re-reads (`refresh_vehicle`): the planned car, where the
     * integration can; `null` otherwise, and then the re-read is the dashboard's alone.
     */
    fun carToReRead(dashboard: Dashboard?): String? {
        val held = dashboard ?: return null
        if (!VehicleRefresh.offered(held.capabilities)) return null
        return VehicleIdentification.carLine(held)?.vehicleId
    }

    /** Whether the car's part of that re-read is said: only a real failure, never Home Assistant's own pace. */
    fun saysCarReRead(answer: VehicleRefresh.Answer): Boolean = answer == VehicleRefresh.Answer.Failed
}
