package se.sensnology.spotnav.ha.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import se.sensnology.spotnav.ha.client.DoesNotFitChoice
import se.sensnology.spotnav.ha.client.FetchedDashboard
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.ReleaseImportTarget
import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.VehicleRefresh

/** The Plan box's choices name the lane's charger on a home's webhook, and nothing more on a charger's own. */
class PlanChoiceAddressTest {
    private class Transport(override val namesCharger: Boolean = false) : HaTransport {
        val calls = mutableListOf<String>()
        override fun dashboard(): FetchedDashboard = error("not read")
        override fun send(command: HomeAssistantCommand) = Unit
        override fun refreshVehicle(vehicleId: String): VehicleRefresh.Answer = error("not asked")
        override fun setChargeLimit(vehicleId: String, percent: Int): ChargeLimit.Answer = error("not asked")
        override fun updateVehicle(vehicleId: String, changes: List<VehicleUpdate.FieldChange>): VehicleUpdate.Outcome = error("not asked")
        override fun updateSettings(expectedRevision: Int, replacement: HaPlanningSettings): SettingsUpdate.Outcome = error("not asked")
        override fun updateSite(request: SiteUpdate.Request): SiteUpdate.Outcome = error("not asked")
        override fun doesNotFitChoice(action: String): DoesNotFitChoice.Outcome {
            calls += action
            return DoesNotFitChoice.Outcome.Done
        }
        override fun doesNotFitChoiceFor(action: String, chargerId: String?): DoesNotFitChoice.Outcome {
            calls += "$action@$chargerId"
            return DoesNotFitChoice.Outcome.Done
        }
        override fun releaseImportTarget(): ReleaseImportTarget.Outcome {
            calls += "release"
            return ReleaseImportTarget.Outcome.Released
        }
        override fun releaseImportTargetFor(chargerId: String?): ReleaseImportTarget.Outcome {
            calls += "release@$chargerId"
            return ReleaseImportTarget.Outcome.Released
        }
    }

    private fun session(transport: HaTransport) = HaSession(transport, { null }, { it.run() }, { it() }, { _, _ -> })

    @Test fun aChargerIsNamedOnlyWhenGiven() {
        val transport = Transport(namesCharger = true)
        val s = session(transport)
        s.doesNotFitChoice(DoesNotFitChoice.TAKE_WHAT_FITS) { }
        s.doesNotFitChoice(DoesNotFitChoice.LATER_DEPARTURE, "entry_b") { }
        s.releaseImportTarget { }
        s.releaseImportTarget("entry_b") { }
        assertEquals(listOf("take_what_fits", "later_departure@entry_b", "release", "release@entry_b"), transport.calls)
        assertTrue(s.namesCharger)
        assertFalse(session(Transport()).namesCharger)
    }

    @Test fun theAddressNamesTheLanesCharger() {
        val home = HomeAssistantSettings("http://ha.local:8123", "hook")
        assertEquals("entry_b", JSONObject(home.copy(chargerId = "entry_b").addressed(DoesNotFitChoice.payload("take_what_fits").toString())).getString("charger_id"))
        assertFalse(JSONObject(home.addressed(DoesNotFitChoice.payload("take_what_fits").toString())).has("charger_id"))
    }
}
