package se.sensnology.spotnav.ui.charging

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.vehicles.VehicleStatus

/**
 * The planning card's heading ("Planning for EV6" when Home Assistant plans for a known car) and its
 * "Charge by" row: which value it shows, and when the target can be chosen at all.
 */
class PlanCardFaceTest {
    private val plain = "Planering"
    private val forCar = "Planering för %1\$s"

    private fun twoCars(edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json", edit)

    private fun car(capacityKwh: Double? = 77.4) =
        VehicleStatus(id = "ev6", name = "EV6", socPercent = 52.0, batteryCapacityKwh = capacityKwh)

    // The heading

    @Test fun withoutHomeAssistantTheTitleIsPlain() {
        assertEquals("Planering", PlanCardFace.title(plain, forCar, null))
    }

    @Test fun homeAssistantNamesThePlannedCar() {
        assertEquals("Planering för EV6", PlanCardFace.title(plain, forCar, twoCars()))
    }

    @Test fun theIdentifiedCarIsTheOneNamed() {
        val identified = twoCars {
            put(
                "identification", JSONObject().put("state", "decided").put("method", "plug_sensor")
                    .put("vehicle_id", "vehicle_niro")
                    .put("candidates", JSONArray()
                        .put(JSONObject().put("vehicle_id", "vehicle_niro").put("name", "Niro"))
                        .put(JSONObject().put("vehicle_id", "vehicle_ev6").put("name", "EV6")))
            )
        }
        assertEquals("Niro", PlanCardFace.plannedCarName(identified))
        assertEquals("Planering för Niro", PlanCardFace.title(plain, forCar, identified))
    }

    @Test fun noKnownCarKeepsThePlainTitle() {
        val none = twoCars {
            put("target_vehicle_id", JSONObject.NULL)
            put("soc", JSONObject.NULL)
            put("identification", JSONObject.NULL)
        }
        assertNull(PlanCardFace.plannedCarName(none))
        assertEquals("Planering", PlanCardFace.title(plain, forCar, none))
    }

    // The "Charge by" row

    @Test fun theChoicesAreEnergyThenTarget() {
        assertEquals(listOf(PlanDriver.KWH, PlanDriver.TARGET_SOC), PlanCardFace.CHARGE_BY_CHOICES)
    }

    @Test fun homeAssistantOffersTheTargetWhenALevelResolves() {
        val available = PlanCardFace.availableDrivers(twoCars(), PlanDriver.KWH, null, null)
        assertEquals(setOf(PlanDriver.KWH, PlanDriver.TARGET_SOC), available)
        assertTrue(PlanCardFace.chargeByShown(available))
        assertEquals(PlanDriver.TARGET_SOC, PlanCardFace.effectiveDriver(PlanDriver.TARGET_SOC, available))
    }

    @Test fun homeAssistantWithoutALevelOffersEnergyOnly() {
        val noLevel = twoCars { put("soc", JSONObject.NULL) }
        val available = PlanCardFace.availableDrivers(noLevel, PlanDriver.KWH, null, null)
        assertEquals(setOf(PlanDriver.KWH), available)
        assertFalse(PlanCardFace.chargeByShown(available))
    }

    @Test fun aRecordAlreadyOnTheTargetKeepsItShownSoItCanBeUndone() {
        val noLevel = twoCars { put("soc", JSONObject.NULL) }
        val available = PlanCardFace.availableDrivers(noLevel, PlanDriver.TARGET_SOC, null, null)
        assertEquals(setOf(PlanDriver.KWH, PlanDriver.TARGET_SOC), available)
        assertTrue(PlanCardFace.chargeByShown(available))
        assertEquals(PlanDriver.TARGET_SOC, PlanCardFace.effectiveDriver(PlanDriver.TARGET_SOC, available))
    }

    @Test fun thePhonesOwnPlanOffersTheTargetForACarWithACapacity() {
        val available = PlanCardFace.availableDrivers(null, PlanDriver.KWH, car(), null)
        assertEquals(setOf(PlanDriver.KWH, PlanDriver.TARGET_SOC), available)
        assertTrue(PlanCardFace.chargeByShown(available))
    }

    @Test fun thePhonesOwnPlanWithoutACapacityFallsBackToEnergy() {
        val available = PlanCardFace.availableDrivers(null, PlanDriver.TARGET_SOC, car(capacityKwh = null), null)
        assertEquals(setOf(PlanDriver.KWH), available)
        assertFalse(PlanCardFace.chargeByShown(available))
        assertEquals(PlanDriver.KWH, PlanCardFace.effectiveDriver(PlanDriver.TARGET_SOC, available))
        // A remembered capacity is enough:
        assertTrue(PlanCardFace.chargeByShown(PlanCardFace.availableDrivers(null, PlanDriver.KWH, car(capacityKwh = null), 64.0)))
    }

    @Test fun noCarAtAllIsEnergyOnly() {
        assertEquals(setOf(PlanDriver.KWH), PlanCardFace.availableDrivers(null, PlanDriver.KWH, null, null))
    }
}
