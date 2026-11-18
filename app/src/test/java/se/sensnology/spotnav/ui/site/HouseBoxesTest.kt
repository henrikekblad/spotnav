package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.HaFixtures

/**
 * Owner rule (plan section 7): a box with nothing to show is left out, never drawn empty, decided from the answer's
 * own fields. The house page's period boxes (Energigraf, Pris och batteri) stack under the Now box.
 */
class HouseBoxesTest {
    private fun house(name: String = "villa_battery_sun.json"): House =
        (HouseRead.decode(HaFixtures.json("house/$name")) as SiteRead.Read).value

    private fun House.noPrices() = copy(hours = hours.map { it.copy(buy = null, sell = null) })

    @Test fun beforeTheHouseHasAnsweredThereAreNoPeriodBoxes() {
        assertEquals(emptyList<HouseBox>(), HouseBoxes.shown(null))
    }

    @Test fun aSiteWithABatteryHasBothBoxes() {
        assertEquals(listOf(HouseBox.GRAPH, HouseBox.PRICE), HouseBoxes.shown(house()))
    }

    @Test fun noBatteryButPricesKeepsPriceAndNoPricesAtAllDropsIt() {
        val noBattery = house().copy(battery = null)
        assertEquals(listOf(HouseBox.GRAPH, HouseBox.PRICE), HouseBoxes.shown(noBattery))
        assertEquals(listOf(HouseBox.GRAPH), HouseBoxes.shown(noBattery.noPrices()))
    }

    @Test fun aBatteryWithoutPricesStillHasItsPriceBox() {
        assertEquals(true, HouseBox.PRICE in HouseBoxes.shown(house().noPrices()))
    }
}
