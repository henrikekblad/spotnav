package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * The car's charge limit (Laddgräns) in Settings → the car: shown where the car reports one, and
 * editable there when Home Assistant can write it (`set_charge_limit`), as the vehicle card had it.
 */
class PairedChargeLimitTest {
    @Test fun aCarThatReportsItsLimitShowsItAndItIsEditableWhenHomeAssistantCanWriteIt() {
        val cards = PairedOverview.vehicles(DashboardFixtures.dashboard("target_soc_two_vehicles.json"))
        val ev6 = cards.first { it.id == "vehicle_ev6" }
        assertEquals(80, ev6.chargeLimit)
        assertTrue(ev6.limitWritable)
        // A car that reports no limit has none to show or write.
        val niro = cards.first { it.id == "vehicle_niro" }
        assertNull(niro.chargeLimit)
        assertFalse(niro.limitWritable)
    }

    @Test fun withoutTheCapabilityTheLimitIsReadOnly() {
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONObject("charger").getJSONObject("capabilities").put("set_charge_limit", false)
        }
        val ev6 = PairedOverview.vehicles(dashboard).first { it.id == "vehicle_ev6" }
        assertEquals(80, ev6.chargeLimit)
        assertFalse(ev6.limitWritable)
    }
}
