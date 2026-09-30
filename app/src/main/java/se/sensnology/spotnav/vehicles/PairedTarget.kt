package se.sensnology.spotnav.vehicles

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardSoc
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.ha.dashboard.DashboardVehicleRef
import se.sensnology.spotnav.planning.ChargeNeed
import kotlin.math.floor

/** The energy a target state of charge needs, computed with Home Assistant's own formula. */
internal object TargetNeed {
    /** Python's `round()` on a float: half goes to the even neighbour, so 80.5 is 80 and 81.5 is 82. */
    fun pythonRound(value: Double): Int {
        val down = floor(value)
        val diff = value - down
        val whole = down.toInt()
        return when {
            diff < 0.5 -> whole
            diff > 0.5 -> whole + 1
            whole % 2 == 0 -> whole
            else -> whole + 1
        }
    }

    /** The highest percentage a charge may aim for: the vehicle's own limit (whole percent) or 100. */
    fun chargeCeiling(maxPercent: Double?): Int =
        if (maxPercent == null || !maxPercent.isFinite()) 100 else floor(maxPercent).toInt().coerceIn(0, 100)

    /** The target the charger will really aim for: the one set, whole, and no higher than the limit. */
    fun effectiveTarget(targetPercent: Double, maxPercent: Double?): Int =
        minOf(pythonRound(targetPercent), chargeCeiling(maxPercent))

    /** Whether the target, as the charger will take it, lies above the vehicle's own limit. */
    fun roundedAbove(targetPercent: Double, maxPercent: Double?): Boolean =
        maxPercent != null && pythonRound(targetPercent) > chargeCeiling(maxPercent)

    /**
     * The wall energy the target needs, in kWh, or `null` when it cannot be said (no battery size,
     * no reading, no efficiency). Zero when the target is at or below the current level.
     */
    fun needKwh(
        soc: Double?,
        capacityKwh: Double?,
        targetPercent: Double,
        maxPercent: Double?,
        efficiency: Double?
    ): Double? {
        if (soc == null || capacityKwh == null || !(capacityKwh > 0.0) || !targetPercent.isFinite()) return null
        if (efficiency == null || !(efficiency > 0.0)) return null
        val needed = maxOf(0.0, (effectiveTarget(targetPercent, maxPercent) - soc) / 100.0 * capacityKwh)
        return if (needed == 0.0) 0.0 else needed / efficiency
    }
}

/** What the sentence under the target slider says, if anything. */
internal enum class TargetVerdict { NONE, NO_NEED, TO_LIMIT }

/**
 * The facts a paired target editor states for one vehicle: the charge now, the vehicle's own limit,
 * the battery size and Home Assistant's efficiency -- either the resolved vehicle's (from the `soc`
 * block) or, for a vehicle picked but not yet saved, only what `vehicles` states about it (its
 * charge is not read, so its need is unknown and never the other vehicle's number).
 */
internal data class PairedTargetFacts(
    val now: Double?,
    val limit: Double?,
    val capacityKwh: Double?,
    val efficiency: Double?,
    /** The percent and need Home Assistant itself stated for the saved target, for the exact case. */
    val statedTarget: Double?,
    val statedNeedKwh: Double?,
    /** True when the picked vehicle is not the one the `soc` block resolved. */
    val other: Boolean
)

/** The paired plan editor's rules, from the dashboard, as the card's settings editor states them. */
internal object PairedTarget {
    /** The slider's whole scale: nothing is floored at the current charge (a target below it is "no need"). */
    val RANGE: ChargeNeed.TargetRange = ChargeNeed.pairedRange

    /**
     * Whether a target state of charge can be planned for this charger: a charge-level source
     * resolves (the `soc` block is present). The card's own switch rule.
     */
    fun available(dashboard: Dashboard): Boolean = dashboard.soc != null

    fun facts(soc: DashboardSoc, vehicles: List<DashboardVehicle>, pickedVehicleId: String?): PairedTargetFacts {
        val picked = pickedVehicleId?.takeIf { it.isNotEmpty() }
        val other = picked != null && picked != (soc.vehicleId ?: "")
        val own = if (other) vehicles.firstOrNull { it.id == picked } else null
        return PairedTargetFacts(
            now = if (other) null else soc.value,
            limit = if (other) own?.maxPercent else soc.vehicleMaxPercent,
            capacityKwh = if (other) own?.capacityKwh else soc.capacityKwh,
            efficiency = soc.efficiency,
            statedTarget = soc.targetPercent,
            statedNeedKwh = soc.needKwh,
            other = other
        )
    }

    fun verdict(facts: PairedTargetFacts, target: Double): TargetVerdict {
        val now = facts.now
        return when {
            now != null && TargetNeed.effectiveTarget(target, facts.limit) <= now -> TargetVerdict.NO_NEED
            facts.limit != null && TargetNeed.roundedAbove(target, facts.limit) -> TargetVerdict.TO_LIMIT
            else -> TargetVerdict.NONE
        }
    }

    /**
     * The energy the slider's target needs, live. Home Assistant's own stated figure is used
     * exactly when the target is the saved one it was computed for; otherwise the formula. Unknown
     * for another vehicle.
     */
    fun needKwh(facts: PairedTargetFacts, target: Double): Double? {
        if (facts.other) return null
        val stated = facts.statedNeedKwh
        if (stated != null && facts.statedTarget != null && facts.statedTarget == target) return stated
        return TargetNeed.needKwh(facts.now, facts.capacityKwh, target, facts.limit, facts.efficiency)
    }

    /** The vehicles the picker offers: only when there is a choice to make. */
    fun choices(soc: DashboardSoc?): List<DashboardVehicleRef> =
        soc?.vehicles?.takeIf { it.size > 1 }.orEmpty()

    /** The resolved vehicle's name, when there is one. */
    fun vehicleName(soc: DashboardSoc): String? =
        soc.vehicleName?.takeIf { it.isNotBlank() }
            ?: soc.vehicles.firstOrNull { it.id == soc.vehicleId }?.name

    /** The amperage range the charger's slider spans, from `current_range`. */
    fun ampsRange(dashboard: Dashboard): IntRange = dashboard.currentRange.minA..dashboard.currentRange.maxA
}
