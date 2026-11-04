package se.sensnology.spotnav.vehicles

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * The guest car (Home Assistant 2.4) as the screen shows it: one more answer in the question, first when every car is
 * ruled out; Byt bil's last choice while a car is plugged in, also at a charger with one car; and the car planned for
 * during a guest plan, named "Guest car" with its amount and nothing of a car.
 */
class VehicleIdentificationGuestTest {
    private val candidates = JSONArray()
        .put(JSONObject().put("vehicle_id", "vehicle_niro").put("name", "Niro").put("likely", false))
        .put(JSONObject().put("vehicle_id", "vehicle_ev6").put("name", "EV6").put("likely", false))

    private fun block(state: String, vehicle: String = "vehicle_ev6", cars: JSONArray = candidates, method: String = "assumed") =
        JSONObject().put("state", state).put("method", method).put("vehicle_id", vehicle)
            .put("since", "2026-10-06T17:00:00+00:00").put("candidates", cars).put("evidence", JSONArray())

    private fun guest(active: Boolean = false, first: Boolean = false) =
        JSONObject().put("active", active).put("amount_kwh", 10.0).put("first", first)

    private fun twoCars(identification: JSONObject? = null, connection: String = "unknown", edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("identification", identification ?: JSONObject.NULL)
            put("connection", JSONObject().put("state", connection).put("source", JSONObject.NULL))
            edit()
        }

    /** A charger only EV6 can charge at. */
    private fun oneCar(identification: JSONObject? = null, connection: String = "unknown", edit: JSONObject.() -> Unit = {}): Dashboard =
        twoCars(identification, connection) {
            val rows = getJSONArray("vehicles")
            put("vehicles", JSONArray().put((0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == "vehicle_ev6" }))
            edit()
        }

    /** A guest plan as Home Assistant states it: the settings' target is the guest, planned by kWh, no car's facts. */
    private fun guestPlan(dashboard: (JSONObject.() -> Unit) -> Dashboard): Dashboard = dashboard {
        put("guest", guest(active = true))
        put("target_vehicle_id", JSONObject.NULL)
        put("soc", JSONObject.NULL)
        getJSONObject("settings").put("driver", "manual_kwh").put("requested_kwh", 12.5)
            .put("target", JSONObject().put("vehicle_id", "guest").put("target_percent", JSONObject.NULL))
    }

    @Test fun theQuestionHasGuestCarLastOrFirstWhenEveryCarIsRuledOut() {
        val last = VehicleIdentification.banner(twoCars(block("asking")), "Gästbil")!!
        assertEquals(listOf("vehicle_niro", "vehicle_ev6", "guest"), last.choices.map { it.vehicleId })
        assertEquals("Gästbil", last.choices.last().name)
        assertNull(last.choices.last().hint)
        assertFalse(last.singleCar)
        val first = VehicleIdentification.banner(twoCars(block("asking")) { put("guest", guest(first = true)) })!!
        assertEquals(listOf("guest", "vehicle_niro", "vehicle_ev6"), first.choices.map { it.vehicleId })
    }

    @Test fun anOlderHomeAssistantAsksWithoutAGuestCar() {
        val older = twoCars(block("asking"), connection = "connected") { remove("guest") }
        assertEquals(listOf("vehicle_niro", "vehicle_ev6"), VehicleIdentification.banner(older)!!.choices.map { it.vehicleId })
        assertEquals(listOf("vehicle_niro", "vehicle_ev6"), VehicleIdentification.switchChoices(older).choices.map { it.vehicleId })
        assertFalse(VehicleIdentification.guestOffered(older))
    }

    @Test fun aOneCarChargerAsksItsCarOrGuestCarAndSaysTheCarDoesNotSeemPluggedIn() {
        val ev6 = JSONArray().put(JSONObject().put("vehicle_id", "vehicle_ev6").put("name", "EV6").put("likely", false))
        val banner = VehicleIdentification.banner(oneCar(block("asking", cars = ev6), connection = "connected"), "Gästbil")!!
        assertEquals(listOf("vehicle_ev6", "guest"), banner.choices.map { it.vehicleId })
        assertTrue(banner.singleCar)
        assertEquals("EV6", banner.keptName)
    }

    @Test fun bytBilOffersGuestCarLastOnlyWhileACarIsPluggedIn() {
        assertEquals(listOf("vehicle_ev6", "vehicle_niro"), VehicleIdentification.switchChoices(twoCars()).choices.map { it.vehicleId })
        for (state in listOf("connected", "charging", "paused", "finished")) {
            val switch = VehicleIdentification.switchChoices(twoCars(connection = state), "Gästbil")
            assertEquals(state, listOf("vehicle_ev6", "vehicle_niro", "guest"), switch.choices.map { it.vehicleId })
            assertEquals("Gästbil", switch.choices.last().name)
        }
        assertFalse(VehicleIdentification.guestOffered(twoCars(connection = "disconnected")))
        assertFalse(VehicleIdentification.guestOffered(twoCars(connection = "error")))
    }

    @Test fun aOneCarChargerGetsBytBilWhileACarIsPluggedIn() {
        val idle = VehicleIdentification.carLine(oneCar(connection = "disconnected"))!!
        assertFalse(idle.canSwitch)
        val plugged = oneCar(connection = "connected")
        assertTrue(VehicleIdentification.carLine(plugged)!!.canSwitch)
        assertEquals(listOf("vehicle_ev6", "guest"), VehicleIdentification.switchChoices(plugged).choices.map { it.vehicleId })
        assertEquals(ChargerCarFace.Place.BUTTON, ChargerCarFace.of(plugged, { "$it %" }, "%1\$s of %2\$s target")!!.place)
    }

    @Test fun duringAGuestPlanTheCarIsGuestCarWithItsAmountAndNoLevel() {
        val dash = guestPlan { edit -> oneCar(connection = "charging", edit = edit) }
        assertTrue(VehicleIdentification.guestActive(dash))
        val line = VehicleIdentification.carLine(dash, "Gästbil")!!
        assertEquals("guest", line.vehicleId)
        assertEquals("Gästbil", line.name)
        assertTrue(line.canSwitch)
        val switch = VehicleIdentification.switchChoices(dash)
        assertEquals("guest", switch.current)
        assertEquals("Gästbil", VehicleIdentification.plannedName(dash, "Gästbil"))
        val face = ChargerCarFace.of(dash, { "$it %" }, "%1\$s of %2\$s target", "Gästbil") { "$it kWh" }!!
        assertEquals("Gästbil", face.name)
        assertEquals("12.5 kWh", face.levels)
        assertEquals("Gästbil · 12.5 kWh", PairedCarLine.summary(dash, { "$it %" }, "%1\$s of %2\$s target", "Gästbil") { "$it kWh" })
        // A guest car has nothing to re-read.
        assertNull(PairedCarLine.carToReRead(dash))
        // Still a guest after it is unplugged, until Home Assistant ends the plan.
        assertTrue(VehicleIdentification.guestOffered(guestPlan { edit -> twoCars(connection = "disconnected", edit = edit) }))
    }

    @Test fun anAnswerOfGuestCarInTheBlockIsTheGuestOnlyWhileItsPlanStands() {
        val answered = twoCars(block("decided", vehicle = "guest", method = "answered"), connection = "connected")
        // The block's guest without a guest plan names no car: the dashboard's target stands.
        assertEquals("vehicle_ev6", VehicleIdentification.carLine(answered)!!.vehicleId)
    }
}
