package se.sensnology.spotnav.ui.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.vehicles.ChargeLimitRange

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

    @Test fun theLimitsRangeIsTheOneTheRowStates() {
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONArray("vehicles").getJSONObject(0)
                .put("charge_limit_range", JSONObject().put("min", 50).put("max", 100).put("step", 10))
        }
        val ev6 = PairedOverview.vehicles(dashboard).first { it.id == "vehicle_ev6" }
        assertEquals(ChargeLimitRange(50.0, 100.0, 10.0), ev6.limitRange)
    }

    @Test fun anUnknownOrUnusableRangeIsNone() {
        // The fixture states `null`; an older Home Assistant leaves it out.
        assertNull(PairedOverview.vehicles(DashboardFixtures.dashboard("target_soc_two_vehicles.json"))
            .first { it.id == "vehicle_ev6" }.limitRange)
        val older = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONArray("vehicles").getJSONObject(0).remove("charge_limit_range")
        }
        assertNull(PairedOverview.vehicles(older).first { it.id == "vehicle_ev6" }.limitRange)
        for (bad in listOf(
            JSONObject().put("min", 0).put("max", 100).put("step", 1),
            JSONObject().put("min", 80).put("max", 50).put("step", 10),
            JSONObject().put("min", 50).put("max", 110).put("step", 10),
            JSONObject().put("min", 50).put("max", 100).put("step", 0),
            JSONObject().put("min", 50).put("max", 100),
            JSONObject().put("min", "50").put("max", 100).put("step", 10)
        )) {
            assertNull(bad.toString(), ChargeLimitRange.parse(bad))
        }
    }
}
