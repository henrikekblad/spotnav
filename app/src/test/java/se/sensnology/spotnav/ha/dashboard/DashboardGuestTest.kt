package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

class DashboardGuestTest {
    @Test fun everyVendoredDashboardCarriesAGuestBlockTheAppReads() {
        for (file in HaFixtures.files("dashboard")) {
            val guest = Dashboard.parse(JSONObject(file.readText())).guest
            assertEquals(file.name, DashboardGuest(active = false, amountKwh = 10.0, first = false), guest)
        }
    }

    @Test fun aGuestPlanAndItsPlaceInTheQuestionAreRead() {
        assertEquals(
            DashboardGuest(active = true, amountKwh = 12.5, first = true),
            DashboardGuest.parse(JSONObject("""{"active": true, "amount_kwh": 12.5, "first": true}"""))
        )
    }

    @Test fun aMissingOrMalformedBlockIsDroppedNeverGuessed() {
        assertNull(DashboardGuest.parse(null))
        assertNull(DashboardGuest.parse(JSONObject.NULL))
        assertNull(DashboardGuest.parse("guest"))
        assertNull(DashboardGuest.parse(JSONObject("""{"active": true, "amount_kwh": 10}""")))
        assertNull(DashboardGuest.parse(JSONObject("""{"active": "yes", "amount_kwh": 10, "first": false}""")))
        assertNull(DashboardGuest.parse(JSONObject("""{"active": true, "amount_kwh": "10", "first": false}""")))
        assertNull(DashboardGuest.parse(JSONObject("""{"active": true, "amount_kwh": 0, "first": false}""")))
        assertNull(DashboardGuest.parse(JSONObject("""{"active": true, "amount_kwh": -3, "first": false}""")))
        val json = HaFixtures.json("dashboard/start_idle.json").apply { remove("guest") }
        assertNull(Dashboard.parse(json).guest)
        json.put("guest", JSONObject("""{"active": 1, "amount_kwh": 10, "first": false}"""))
        assertNull("the rest of the dashboard still reads", Dashboard.parse(json).guest)
    }

    @Test fun aGuestCarsSessionIsNamedInTheReadersLanguageWhateverHomeAssistantsIs() {
        for (name in listOf("guest", "Guest car", "Gästbil", "Gæstebil", "Gjestebil", "Vierasauto", "Gastauto", "Coche de invitado", "Voiture invitée")) {
            assertEquals(name, "Gästbil", DashboardGuest.sessionVehicle(name, "Gästbil"))
        }
        assertEquals("EV6", DashboardGuest.sessionVehicle("EV6", "Gästbil"))
        assertNull(DashboardGuest.sessionVehicle(null, "Gästbil"))
    }
}
