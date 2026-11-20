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

    @Test fun aSiteWithABatteryHasAllThreeBoxesInTheCardsOrder() {
        assertEquals(listOf(HouseBox.GRAPH, HouseBox.EARN, HouseBox.PRICE), HouseBoxes.shown(house()))
    }

    @Test fun noBatteryButPricesKeepsPriceAndNoPricesAtAllDropsIt() {
        val noBattery = house().copy(battery = null)
        assertEquals(listOf(HouseBox.GRAPH, HouseBox.EARN, HouseBox.PRICE), HouseBoxes.shown(noBattery))
        assertEquals(listOf(HouseBox.GRAPH, HouseBox.EARN), HouseBoxes.shown(noBattery.noPrices()))
    }

    @Test fun theIntaktBoxIsThereOnlyWhereSomethingCanBeSavedAHeatMeterABatteryACarOrASavingToldOf() {
        val bare = house().copy(battery = null, heat = null, saving = null, energyFlow = house().energyFlow!!.let { flow -> flow.copy(nodes = flow.nodes.filter { it.role != "car" }) })
        assertEquals(false, HouseBox.EARN in HouseBoxes.shown(bare))
        assertEquals(true, HouseBox.EARN in HouseBoxes.shown(bare.copy(battery = house().battery)))
        assertEquals(true, HouseBox.EARN in HouseBoxes.shown(bare.copy(heat = House.Heat("found", "Värme"))))
        assertEquals(false, HouseBox.EARN in HouseBoxes.shown(bare.copy(heat = House.Heat("ambiguous", null))))
        assertEquals(true, HouseBox.EARN in HouseBoxes.shown(bare.copy(saving = House.SavingDay(java.time.LocalDate.now(), null, null, 100.0, null, null, null))))
        assertEquals(true, HouseBox.EARN in HouseBoxes.shown(house().copy(battery = null, heat = null, saving = null)))
    }

    @Test fun aBatteryWithoutPricesStillHasItsPriceBox() {
        assertEquals(true, HouseBox.PRICE in HouseBoxes.shown(house().noPrices()))
    }
}
