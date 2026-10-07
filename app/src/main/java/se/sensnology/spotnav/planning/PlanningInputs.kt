package se.sensnology.spotnav.planning

import java.time.LocalTime

/**
 * Departure as a calculation sees it: whether it applies, and the wall time it is — never a date,
 * because "leave by 07:30" is a time of day that each day's own market offset is applied to.
 */
data class DepartureIntent(val enabled: Boolean, val time: LocalTime)

/**
 * Everything Android's price calculation and plan rendering need, and nothing that belongs to
 * somebody else.
 */
data class PlanningInputs(
    /** Which price area's market this plan is for. */
    val areaId: String,
    /** VAT: multiplied in last, by its own effective percentage. */
    val vat: FiscalInput,
    /** Tax, added in minor units. */
    val tax: FiscalInput,
    /** Grid transfer fee, added in minor units. */
    val transfer: FiscalInput,
    /** One phase or three; nothing else is a charge this app can plan. */
    val phases: Int,
    val amps: Int,
    /** The requested amount in kWh, exactly as asked: a decimal stays a decimal. */
    val requestedEnergyKwh: Double,
    /** Consumption in kWh per 10 km, the unit the widget and the plan card both show. */
    val consumptionKwhPer10Km: Double,
    val maxPeriods: Int,
    val departure: DepartureIntent,
    /** What drives the plan. */
    val driver: PlanDriver,
    /** The target state of charge the plan was built for, when one was; never a live reading. */
    val targetSocPercent: Double? = null
) {
    init {
        require(areaId.isNotBlank()) { "a plan needs an area" }
        require(phases == 1 || phases == 3) { "phases must be 1 or 3" }
        require(amps >= 1) { "amps must be a positive whole number of amperes" }
        require(requestedEnergyKwh.isFinite() && requestedEnergyKwh > 0.0) {
            "requestedEnergyKwh must be finite and greater than zero"
        }
        require(consumptionKwhPer10Km.isFinite() && consumptionKwhPer10Km > 0.0) {
            "consumptionKwhPer10Km must be finite and greater than zero"
        }
        require(maxPeriods in 1..8) { "maxPeriods must be between 1 and 8" }
        // The three components check their own figures (see FiscalInput): nothing here has to
        // re-state that rule, and nothing can bypass it.
        require(targetSocPercent == null || (targetSocPercent.isFinite() && targetSocPercent in 0.0..100.0)) {
            "targetSocPercent must be finite and between 0 and 100 when it is set"
        }
    }

    /** One relay price into what a plan and a table show. */
    fun apply(localMajorPerKwh: Double): Double =
        FiscalArithmetic.apply(localMajorPerKwh, vat = vat, tax = tax, transfer = transfer)
}
