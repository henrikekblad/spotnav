package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House

/**
 * Which tabs the house page shows. A tab with nothing to show is left out, never drawn empty (owner rule, plan
 * section 7), decided from the answer's own fields: before the house has answered only Now is there; Pris och batteri
 * is there with a battery or with prices, and with neither it is gone. The page draws its tab row only from two tabs.
 */
internal object HouseTabs {
    fun shown(house: House?, built: List<HouseTab> = HouseTab.BUILT): List<HouseTab> =
        built.filter { tab -> tab == HouseTab.NOW || (house != null && has(house, tab)) }

    private fun has(house: House, tab: HouseTab): Boolean = when (tab) {
        HouseTab.PRICE -> house.battery != null || house.hours.any { it.buy != null || it.sell != null }
        else -> true
    }
}
