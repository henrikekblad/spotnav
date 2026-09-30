package se.sensnology.spotnav.chart

import se.sensnology.spotnav.planning.PlanningInputs
import java.time.OffsetDateTime

/**
 * The facts a graph's footer is worded from; the renderer owns the words (locale, plurals, fitting).
 *
 * Bands are a merged union and cannot carry these facts, so this travels with the request (see
 * [ChartRequest.footer]) and takes part in its equality. The instants are the plan's own, never
 * reconstructed from bands. Holds no localized strings and no [PlanningInputs].
 */
internal data class ChartFooterPlan(
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    /** How many periods the plan itself named, before any band was merged. */
    val periodCount: Int,
    /** Whether the plan charges without a published price (its own `unpricedSlots > 0`). */
    val unpriced: Boolean,
    val energyKwh: Double,
    /** Distance in the plan's own unit: `distanceMil`, which the renderer labels per language. */
    val distanceMil: Double
) {
    init {
        require(end.isAfter(start)) { "a footer's plan must go forwards" }
        require(periodCount >= 1) { "a footer names at least one period" }
        // Same bounds as a plan's own numbers; a NaN would be painted as unreadable text.
        require(energyKwh.isFinite() && energyKwh >= 0.0) { "energyKwh must be finite and not negative" }
        require(distanceMil.isFinite() && distanceMil >= 0.0) { "distanceMil must be finite and not negative" }
    }
}
