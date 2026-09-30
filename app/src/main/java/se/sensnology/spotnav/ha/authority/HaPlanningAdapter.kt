package se.sensnology.spotnav.ha.authority

import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.prices.PriceMarket

/** Aggregation is a phone presentation choice, never a paired planning input. */
data class HaPresentation(val intervalMinutes: Int) {
    val hourly: Boolean get() = intervalMinutes == 60
    companion object {
        val QUARTER_HOUR = HaPresentation(15)
        val HOURLY = HaPresentation(60)
    }
}

/** A supported paired record always belongs to HA, regardless of completeness or catalogue. */
internal object HaPlanningAdapter {
    @Suppress("UNUSED_PARAMETER")
    fun of(record: HaPlanningSettings, catalogue: List<PriceMarket>, presentation: HaPresentation): HaPlanningInputs =
        HaPlanningInputs.Auto(record)
}
