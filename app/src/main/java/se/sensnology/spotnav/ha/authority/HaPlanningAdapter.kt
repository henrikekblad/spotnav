package se.sensnology.spotnav.ha.authority

import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.prices.PriceMarket

/** A supported paired record always belongs to HA, regardless of completeness or catalogue. */
internal object HaPlanningAdapter {
    @Suppress("UNUSED_PARAMETER")
    fun of(record: HaPlanningSettings, catalogue: List<PriceMarket>): HaPlanningInputs =
        HaPlanningInputs.Auto(record)
}
