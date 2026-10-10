package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaSettingsDriver
import java.util.Locale

/**
 * The car on a paired charger's card, and in the charger drop-down's rows: its name and its levels,
 * "EV6 · 89 % of 93 % target" when planning to a target, only "EV6 · 89 %" when planning an amount, "≈" before
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
        // What the charge really aims for: never above the car's own limit.
        val limit = soc?.vehicleMaxPercent ?: row?.maxPercent
        return Levels(
            now = now?.let { SocDisplay.wholePercent(it) },
            estimated = soc != null && soc.value != null && soc.estimated,
            target = target?.let { TargetNeed.effectiveTarget(it, limit) },
            targetMode = dashboard.settings?.driver == HaSettingsDriver.TARGET_SOC
        )
    }

    /**
     * "89 % of 93 % target", "≈ 91 % of 93 % target", "– of 90 % target" or "89 %"; `null` when there is
     * nothing to say. [ofTarget] is the translated `car_line_of_target` template (level, then target).
     */
    fun levelsText(levels: Levels, percent: (Int) -> String, ofTarget: String): String? {
        val now = levels.now?.let { (if (levels.estimated) "≈ " else "") + percent(it) }
        val target = levels.target?.takeIf { levels.targetMode } ?: return now
        // Both parts are already written, so the template needs no locale of its own.
        return ofTarget.format(Locale.ROOT, now ?: "–", percent(target))
    }

    /** The charger's car and its levels, "EV6 · 89 % of 93 % target"; `null` when no car is planned for. */
    fun summary(dashboard: Dashboard, percent: (Int) -> String, ofTarget: String): String? {
        val line = VehicleIdentification.carLine(dashboard) ?: return null
        val id = line.vehicleId ?: return null
        return listOfNotNull(line.name ?: id, levelsText(levels(dashboard, id), percent, ofTarget)).joinToString(" · ")
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
