package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.client.VehicleField
import se.sensnology.spotnav.ha.client.VehicleFieldIssue
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.HaFixtures

/** The paired vehicle card's mapping by the dashboard's own ids, and how it takes a write's answer. */
class PairedVehiclesTest {
    private fun v1(name: String) = Dashboard.parse(HaFixtures.json("dashboard/$name.json"))
    private fun status(id: String, name: String, soc: Double = 50.0) = VehicleStatus(id, name, soc)

    @Test fun theDashboardsThreeIdSourcesNameTheSameVehicles() {
        val d = v1("target_soc_two_vehicles")
        val rowIds = d.vehicles.map { it.id }.toSet()
        assertEquals(rowIds, d.soc!!.vehicles.map { it.id }.toSet())
        assertTrue(d.soc!!.vehicleId in rowIds)
        assertEquals(d.soc!!.vehicleId, d.targetVehicleId)
    }

    @Test fun theShownVehicleIsThePickThenTheResolvedOneThenTheTargetThenTheOnlyOne() {
        val d = v1("target_soc_two_vehicles")
        assertEquals("vehicle_niro", PairedVehicles.shownId(d, "vehicle_niro"))
        assertEquals("vehicle_ev6", PairedVehicles.shownId(d, null))
        // A pick that is not one of the dashboard's vehicles is not honoured.
        assertEquals("vehicle_ev6", PairedVehicles.shownId(d, "somebody_else"))
        val one = v1("target_soc_estimated")
        assertEquals(one.soc!!.vehicleId, PairedVehicles.shownId(one, null))
        assertNull(PairedVehicles.shownId(v1("cheapest_direct_site_admin"), null))
    }

    @Test fun rowsAndNamesComeFromTheDashboardAndAnAdoptedRowStandsInUntilTheNextAnswer() {
        val d = v1("target_soc_two_vehicles")
        assertEquals("Niro", PairedVehicles.row(d, emptyMap(), "vehicle_niro")!!.name)
        assertEquals("Niro", PairedVehicles.name(d, "vehicle_niro"))
        val adopted = d.vehicles.first { it.id == "vehicle_niro" }.copy(capacityKwh = 70.0)
        assertEquals(70.0, PairedVehicles.row(d, mapOf("vehicle_niro" to adopted), "vehicle_niro")!!.capacityKwh!!, 0.0)
        assertNull(PairedVehicles.row(d, emptyMap(), null))
        assertNull(PairedVehicles.row(d, emptyMap(), "nope"))
    }

    @Test fun theStatusListIsMatchedByIdNeverByNameAndNeverByFallback() {
        val list = listOf(status("vehicle_ev6", "EV6"), status("dev-2", "Niro", 61.0))
        assertEquals("EV6", PairedVehicles.statusVehicle(list, "vehicle_ev6")!!.name)
        // Same name, different id: not the same vehicle.
        assertNull(PairedVehicles.statusVehicle(list, "vehicle_niro"))
        // And a single-entry list is not a fallback for an unknown id.
        assertNull(PairedVehicles.statusVehicle(listOf(status("x", "EV6")), "vehicle_ev6"))
        assertNull(PairedVehicles.statusVehicle(list, null))
    }

    @Test fun theChargeLevelIsHomeAssistantsForTheResolvedVehicleAndTheStatusListsForAnother() {
        val d = v1("target_soc_two_vehicles")
        val list = listOf(status("vehicle_ev6", "EV6", 39.0), status("vehicle_niro", "Niro", 61.0))
        assertEquals(40.0, PairedVehicles.socPercent(d, list[0], "vehicle_ev6")!!, 0.0)
        assertEquals(61.0, PairedVehicles.socPercent(d, list[1], "vehicle_niro")!!, 0.0)
        // No reading anywhere for a vehicle: none is invented.
        assertNull(PairedVehicles.socPercent(d, null, "vehicle_niro"))
    }

    @Test fun thePickerOffersTheSocVehiclesElseTheDashboardsVehiclesAndOnlyWhenThereIsAChoice() {
        assertEquals(listOf("vehicle_ev6", "vehicle_niro"), PairedVehicles.choices(v1("target_soc_two_vehicles")).map { it.id })
        assertTrue(PairedVehicles.choices(v1("target_soc_estimated")).isEmpty())
        val json = HaFixtures.json("dashboard/target_soc_two_vehicles.json")
        json.put("soc", org.json.JSONObject.NULL)
        val noSoc = Dashboard.parse(json)
        assertEquals(listOf("vehicle_ev6", "vehicle_niro"), PairedVehicles.choices(noSoc).map { it.id })
    }

    @Test fun capacityAndConsumptionAreTheRowsOwnNotTheLocalStores() {
        val d = v1("target_soc_two_vehicles")
        val niro = PairedVehicles.row(d, emptyMap(), "vehicle_niro")
        assertEquals(64.8, PairedVehicles.capacityKwh(null, niro, null, null, niro!!.id)!!, 0.0)
        assertEquals(1.7, niro!!.consumptionKwhPer10km!!, 0.0)
        assertNull(PairedVehicles.capacityKwh(null, null, null, null, null))
    }

    @Test fun aVehicleWritesAnswerIsAdoptedOnlyWhenItCarriesARow() {
        val row = v1("target_soc_two_vehicles").vehicles.first()
        val updated = PairedVehicles.feedback(VehicleUpdate.Outcome.Updated(row))
        assertEquals(row, updated.adopted)
        assertTrue(updated.close && updated.reload)
        assertNull(updated.notice)

        val conflict = PairedVehicles.feedback(VehicleUpdate.Outcome.Conflict(row))
        assertEquals(row, conflict.adopted)
        assertEquals(PairedVehicles.Notice.CONFLICT, conflict.notice)
        assertTrue(conflict.close)
        assertNull(PairedVehicles.feedback(VehicleUpdate.Outcome.Conflict(null)).adopted)
    }

    @Test fun aFieldRefusalStaysOnTheFieldAndKeepsTheEditorOpen() {
        val issues = mapOf(VehicleField.CAPACITY to VehicleFieldIssue.OUT_OF_RANGE)
        val f = PairedVehicles.feedback(VehicleUpdate.Outcome.Refused(issues, unknownVehicle = false, row = null))
        assertEquals(issues, f.issues)
        assertFalse(f.close)
        assertFalse(f.reload)
        assertNull(f.notice)
    }

    @Test fun aVanishedVehicleClosesTheEditorAndSaysSo() {
        val f = PairedVehicles.feedback(VehicleUpdate.Outcome.Refused(emptyMap(), unknownVehicle = true, row = null))
        assertEquals(PairedVehicles.Notice.UNKNOWN_VEHICLE, f.notice)
        assertTrue(f.close)
        assertEquals(PairedVehicles.Notice.REFUSED, PairedVehicles.feedback(VehicleUpdate.Outcome.Refused(emptyMap(), false, null)).notice)
        assertEquals(PairedVehicles.Notice.NOT_SUPPORTED, PairedVehicles.feedback(VehicleUpdate.Outcome.NotSupported).notice)
        assertEquals(PairedVehicles.Notice.FAILED, PairedVehicles.feedback(VehicleUpdate.Outcome.Failed("x")).notice)
    }

    @Test fun aSiteWritesAnswerAdoptsTheBlockItCarries() {
        val site = v1("cheapest_direct_site_admin").site!!
        assertEquals(PairedVehicles.SiteFeedback(site, null, true), PairedVehicles.feedback(SiteUpdate.Outcome.Updated(site)))
        assertEquals(
            PairedVehicles.SiteFeedback(site, PairedVehicles.SiteNotice.CONFLICT, true),
            PairedVehicles.feedback(SiteUpdate.Outcome.Conflict(site))
        )
        assertEquals(
            PairedVehicles.SiteFeedback(site, PairedVehicles.SiteNotice.NOT_PERMITTED, false),
            PairedVehicles.feedback(SiteUpdate.Outcome.NotPermitted(site))
        )
        assertEquals(PairedVehicles.SiteNotice.INVALID, PairedVehicles.feedback(SiteUpdate.Outcome.Refused(null)).notice)
        assertEquals(PairedVehicles.SiteNotice.UNAVAILABLE, PairedVehicles.feedback(SiteUpdate.Outcome.Unavailable).notice)
        assertEquals(PairedVehicles.SiteNotice.NOT_SUPPORTED, PairedVehicles.feedback(SiteUpdate.Outcome.NotSupported).notice)
        assertEquals(PairedVehicles.SiteNotice.FAILED, PairedVehicles.feedback(SiteUpdate.Outcome.Failed(null)).notice)
    }

    @Test fun aCapacityHomeAssistantStoresIsShownWhileTheStatusVehicleReportsNone() {
        // The owner's EV6: Home Assistant stores 77.4 kWh, the car reports none, this phone
        // remembers none.
        val d = v1("target_soc_estimated")
        val id = PairedVehicles.shownId(d, null)
        val row = PairedVehicles.row(d, emptyMap(), id)!!.copy(capacityKwh = 77.4, capacitySource = "stored")
        val silent = VehicleStatus(id!!, "EV6", 50.0)
        assertNull(silent.batteryCapacityKwh)
        assertEquals(77.4, PairedVehicles.capacityKwh(d, row, silent, null, id)!!, 0.0)
        // The row not read yet (or missing it): the soc block's figure for the same vehicle, then
        // the status's.
        assertEquals(d.soc!!.capacityKwh, PairedVehicles.capacityKwh(d, null, silent, null, id))
        assertEquals(60.0, PairedVehicles.capacityKwh(null, null, silent.copy(batteryCapacityKwh = 60.0), null, id)!!, 0.0)
        assertEquals(55.0, PairedVehicles.capacityKwh(null, null, silent, 55.0, id)!!, 0.0)
        assertNull(PairedVehicles.capacityKwh(null, null, silent, null, id))
    }
}
