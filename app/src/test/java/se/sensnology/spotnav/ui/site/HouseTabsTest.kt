package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.HaFixtures

/**
 * Owner rule (plan section 7): a tab with nothing to show is left out, never drawn empty, decided from the answer's own
 * fields; the page draws a tab row only from two tabs.
 */
class HouseTabsTest {
    private fun house(name: String = "villa_battery_sun.json"): House =
        (HouseRead.decode(HaFixtures.json("house/$name")) as SiteRead.Read).value

    private fun House.noPrices() = copy(hours = hours.map { it.copy(buy = null, sell = null) })

    @Test fun beforeTheHouseHasAnsweredOnlyNowIsThere() {
        assertEquals(listOf(HouseTab.NOW), HouseTabs.shown(null))
    }

    @Test fun aSiteWithABatteryHasAllTheBuiltTabs() {
        assertEquals(listOf(HouseTab.NOW, HouseTab.GRAPH, HouseTab.PRICE), HouseTabs.shown(house()))
    }

    @Test fun noBatteryButPricesKeepsPriceAndNoPricesAtAllDropsIt() {
        val noBattery = house().copy(battery = null)
        assertEquals(listOf(HouseTab.NOW, HouseTab.GRAPH, HouseTab.PRICE), HouseTabs.shown(noBattery))
        val nothingPriced = noBattery.noPrices()
        assertEquals(listOf(HouseTab.NOW, HouseTab.GRAPH), HouseTabs.shown(nothingPriced))
    }

    @Test fun aBatteryWithoutPricesStillHasItsPriceTab() {
        assertEquals(true, HouseTab.PRICE in HouseTabs.shown(house().noPrices()))
    }

    @Test fun aRowIsDrawnOnlyFromTwoTabs() {
        // Nothing of the later tabs built: one tab, no row.
        assertEquals(1, HouseTabs.shown(house(), built = listOf(HouseTab.NOW)).size)
        assertEquals(false, HouseTabs.shown(house(), built = listOf(HouseTab.NOW)).size > 1)
        assertEquals(true, HouseTabs.shown(house()).size > 1)
    }
}
