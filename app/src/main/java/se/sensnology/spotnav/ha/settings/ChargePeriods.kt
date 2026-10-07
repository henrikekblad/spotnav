package se.sensnology.spotnav.ha.settings

/**
 * Charge periods, a charger setting in Home Assistant (`max_periods`): automatic (`null`, the default: the
 * planner weighs a start cost per period and keeps each period at least half an hour) or a cap of 1 to 8.
 * The app's local planner keeps its own number in the plan card.
 */
internal object ChargePeriods {
    /** The choice the charger settings offer, in order: automatic, then 1 to 8. */
    val OPTIONS: List<Int?> = listOf(null) + (1..8).toList()

    /** Where [maxPeriods] stands in [OPTIONS]; anything outside them reads as automatic. */
    fun indexOf(maxPeriods: Int?): Int = OPTIONS.indexOf(maxPeriods).coerceAtLeast(0)
}
