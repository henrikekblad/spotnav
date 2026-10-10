package se.sensnology.spotnav.vehicles

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import se.sensnology.spotnav.ha.client.FetchedDashboard
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.IdentifyVehicle
import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.session.HaTransport
import se.sensnology.spotnav.ha.settings.HaNotificationSettings
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.ui.settings.NotificationPhones
import se.sensnology.spotnav.ui.settings.NotificationsSave

/**
 * Review findings on vehicle identification: the read after an accepted Byt bil reaches the settings
 * authority, a Home Assistant without identification is never offered its event, a charger with two
 * cars and none chosen still offers a way to choose, and a fee that is on keeps its Off box clear.
 */
class IdentificationReviewTest {
    private class Transport : HaTransport {
        override fun dashboard() = FetchedDashboard(DashboardFixtures.dashboard("target_soc_two_vehicles.json"), "{}")
        override fun send(command: HomeAssistantCommand) = Unit
        override fun refreshVehicle(vehicleId: String) = VehicleRefresh.Answer.Refreshed
        override fun setChargeLimit(vehicleId: String, percent: Int) = ChargeLimit.Answer.Set
        override fun updateVehicle(vehicleId: String, changes: List<VehicleUpdate.FieldChange>) = VehicleUpdate.Outcome.Failed(null)
        override fun updateSettings(expectedRevision: Int, replacement: HaPlanningSettings) = SettingsUpdate.Outcome.Unavailable
        override fun updateSite(request: SiteUpdate.Request) = SiteUpdate.Outcome.Failed(null)
        override fun identifyVehicle(vehicleId: String) = IdentifyVehicle.Outcome.Identified(null)
    }

    /**
     * F1. Home Assistant writes the settings on an accepted identify_vehicle (its revision and
     * target.vehicle_id move). The read that follows must reach the authority like a command's
     * confirmation read does (`send` admits it), or the screen keeps planning, showing and writing
     * against the car and revision from before Byt bil.
     */
    @Test fun theReadAfterAnAcceptedIdentifyIsAdmittedForTheAuthority() {
        val session = HaSession(Transport(), { 3 }, { it.run() }, { it() }, { _, _ -> })
        var sent: HaSession.Sent? = null
        val admission = se.sensnology.spotnav.ha.authority.DashboardAdmission("charger", 1, 7L)
        session.identifyVehicle("vehicle_niro", admit = { admission }) { _, read -> sent = read }
        assertNotNull(sent)
        assertNotNull("identify's read carries no admission: the authority never adopts the new record", sent!!.admission)
    }

    /**
     * F3. Two cars can charge here and none is planned for yet (no target, no soc car, nothing
     * plugged in). The vehicle card is hidden on a paired charger, so the car line's Byt bil is the
     * only way left to choose the car.
     */
    @Test fun twoCarsAndNoneChosenStillOfferAWayToChooseOne() {
        val dash: Dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("identification", JSONObject.NULL)
            put("target_vehicle_id", JSONObject.NULL)
            getJSONObject("settings").getJSONObject("target").put("vehicle_id", JSONObject.NULL)
            optJSONObject("soc")?.put("vehicle_id", JSONObject.NULL)
        }
        assert(dash.vehicles.size == 2)
        assertNotNull("no car line, so no Byt bil, and the vehicle card is hidden", VehicleIdentification.carLine(dash))
    }

    /**
     * F2. A released Home Assistant (1.12.x) refuses `vehicle_identify` in `notifications.events`
     * (`invalid_notifications`, NOTIFICATION_EVENTS has seven events). The dialog offers the box to
     * every Home Assistant that states notifications, so ticking it fails the whole save there.
     */
    @Test fun aHomeAssistantWithoutIdentificationIsNeverSentTheIdentifyEvent() {
        val released = HaNotificationSettings(
            targets = listOf("mobile_app_pixel"),
            events = listOf("plan_stopped", "plan_at_risk", "charge_complete")
        )
        val choice = NotificationsSave.homeAssistantChoice(
            released, NotificationPhones.of(released), phoneTicked = listOf(true),
            eventTicked = NotificationEvent.entries.map { true }
        )
        assertFalse(choice.events.toString(), "vehicle_identify" in choice.events)
    }

    /** F2, the summary: a Home Assistant without identification counts the seven events it knows. */
    @Test fun theSummaryCountsOnlyTheEventsThatHomeAssistantTakes() {
        val released = HaNotificationSettings(events = listOf("plan_stopped"))
        assertFalse(NotificationEvent.VEHICLE_IDENTIFY in NotificationEvent.offered(released, identifies = false))
        org.junit.Assert.assertEquals(7, NotificationEvent.offered(released, identifies = false).size)
        org.junit.Assert.assertEquals(8, NotificationEvent.offered(released, identifies = true).size)
    }

    /** F4. A fee that is on but states no figure opens with its Off box clear; only an Off fee starts off. */
    @Test fun aFeeThatIsOnStartsWithItsOffBoxClear() {
        assertFalse(se.sensnology.spotnav.ui.settings.PriceRows.startsOff(se.sensnology.spotnav.ui.settings.FiscalLine.Unset))
        assertFalse(se.sensnology.spotnav.ui.settings.PriceRows.startsOff(se.sensnology.spotnav.ui.settings.FiscalLine.Figure(36.0, "öre/kWh")))
        org.junit.Assert.assertTrue(se.sensnology.spotnav.ui.settings.PriceRows.startsOff(se.sensnology.spotnav.ui.settings.FiscalLine.Off))
    }

    /** F3, the words: with no car chosen the line names none, and Byt bil is there. */
    @Test fun theLineWithNoCarChosenNamesNoneAndOffersBytBil() {
        val dash: Dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("target_vehicle_id", JSONObject.NULL)
            getJSONObject("settings").getJSONObject("target").put("vehicle_id", JSONObject.NULL)
            optJSONObject("soc")?.put("vehicle_id", JSONObject.NULL)
        }
        val line = VehicleIdentification.carLine(dash)!!
        org.junit.Assert.assertNull(line.vehicleId)
        org.junit.Assert.assertTrue(line.canSwitch)
        org.junit.Assert.assertNull(PairedCarLine.summary(dash, { "$it %" }, "%1\$s of %2\$s target"))
    }
}
