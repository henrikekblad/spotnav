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
import java.io.File

/**
 * The car on the charger card: a button over Start and Pause where a car can be chosen, a value row
 * under the charge history where there is one car, and nothing where no car is known; what each
 * opens, and the button marked while the question is open.
 */
class ChargerCarFaceTest {
    private val percent: (Int) -> String = { "$it %" }
    private val ofTarget = "%1\$s of %2\$s target"

    private fun asking(): JSONObject =
        JSONObject().put("state", "asking").put("method", "assumed").put("vehicle_id", "vehicle_ev6")
            .put("since", "2026-10-06T17:00:00+00:00")
            .put(
                "candidates", JSONArray()
                    .put(JSONObject().put("vehicle_id", "vehicle_ev6").put("name", "EV6").put("likely", false))
                    .put(JSONObject().put("vehicle_id", "vehicle_niro").put("name", "Niro").put("likely", false))
            )
            .put("evidence", JSONArray())

    private fun decided(method: String): JSONObject = asking().put("state", "decided").put("method", method)

    private fun dashboard(edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("identification", JSONObject.NULL)
            edit()
        }

    private fun oneCar(edit: JSONObject.() -> Unit = {}): Dashboard = dashboard {
        getJSONObject("settings").put("vehicle_ids", JSONArray().put("vehicle_ev6"))
        getJSONArray("vehicles").remove(1)
        edit()
    }

    private fun face(dash: Dashboard) = ChargerCarFace.of(dash, percent, ofTarget)

    @Test fun twoCarsAtTheChargerMakeTheCarAButtonThatOpensTheChooser() {
        val face = face(dashboard { put("identification", decided("plug_sensor")) })!!
        assertEquals(ChargerCarFace.Place.BUTTON, face.place)
        assertEquals(ChargerCarFace.Tap.CHOOSER, face.tap)
        assertEquals("vehicle_ev6", face.vehicleId)
        assertEquals("EV6", face.name)
        assertEquals("40 % of 80 % target", face.levels)
        assertEquals(VehicleIdentification.Basis.PLUG_SENSOR, face.basis)
        assertFalse(face.highlighted)
    }

    @Test fun theButtonIsMarkedWhileTheQuestionIsOpen() {
        val face = face(dashboard { put("identification", asking()) })!!
        assertEquals(ChargerCarFace.Place.BUTTON, face.place)
        assertTrue(face.highlighted)
        // Still the chooser: the button is a second way to answer.
        assertEquals(ChargerCarFace.Tap.CHOOSER, face.tap)
    }

    @Test fun oneCarAtTheChargerIsAValueRowThatOpensThatCarsSettings() {
        val face = face(oneCar())!!
        assertEquals(ChargerCarFace.Place.ROW, face.place)
        assertEquals(ChargerCarFace.Tap.CAR_SETTINGS, face.tap)
        assertEquals("vehicle_ev6", face.vehicleId)
        assertEquals("40 % of 80 % target", face.levels)
        assertFalse(face.highlighted)
    }

    @Test fun withoutATargetTheLineSaysTheLevelAlone() {
        val kwh = face(oneCar { getJSONObject("settings").put("driver", "manual_kwh") })!!
        assertEquals("40 %", kwh.levels)
        val button = face(dashboard { getJSONObject("settings").put("driver", "manual_kwh") })!!
        assertEquals(ChargerCarFace.Place.BUTTON, button.place)
        assertEquals("40 %", button.levels)
    }

    @Test fun twoCarsAndNoneKnownStillOfferTheChooserOnTheButton() {
        val face = face(dashboard {
            put("target_vehicle_id", JSONObject.NULL)
            getJSONObject("settings").getJSONObject("target").put("vehicle_id", JSONObject.NULL)
            optJSONObject("soc")?.put("vehicle_id", JSONObject.NULL)
        })!!
        assertEquals(ChargerCarFace.Place.BUTTON, face.place)
        assertNull(face.vehicleId)
        assertNull(face.levels)
        assertEquals(ChargerCarFace.Tap.CHOOSER, face.tap)
    }

    @Test fun noCarAtAllShowsNeither() {
        val none = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("identification", JSONObject.NULL)
            put("target_vehicle_id", JSONObject.NULL)
            put("vehicles", JSONArray())
            getJSONObject("settings").getJSONObject("target").put("vehicle_id", JSONObject.NULL)
            optJSONObject("soc")?.put("vehicle_id", JSONObject.NULL)
        }
        assertNull(face(none))
    }

    @Test fun theCaptionSaysHowTheCarWasIdentifiedOrAsksToChoose() {
        val words = ChargerCarFace.Words(car = "Car", carWith = "Car · %1\$s", choose = "Choose car") { basis ->
            if (basis == VehicleIdentification.Basis.PLUG_SENSOR) "identified by the car's charging cable" else "assumed"
        }
        val identified = face(dashboard { put("identification", decided("plug_sensor")) })!!
        assertEquals("Car · identified by the car's charging cable", ChargerCarFace.caption(identified, words))
        assertEquals("Choose car", ChargerCarFace.caption(face(dashboard { put("identification", asking()) })!!, words))
        // Nothing said of how it was decided: the caption is the word alone.
        assertEquals("Car", ChargerCarFace.caption(face(dashboard())!!, words))
        assertEquals("Car · EV6", ChargerCarFace.rowLabel(face(oneCar())!!, words))
    }

    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    private fun text(directory: String, name: String): String? =
        Regex("<string name=\"$name\">(.*?)</string>").find(xml(directory))?.groupValues?.get(1)

    @Test fun theButtonsWordsAreInEveryLanguage() {
        val expected = mapOf(
            "values" to listOf("Car", "Car · %1\$s", "Choose car"),
            "values-sv" to listOf("Bil", "Bil · %1\$s", "Välj bil"),
            "values-da" to listOf("Bil", "Bil · %1\$s", "Vælg bil"),
            "values-nb" to listOf("Bil", "Bil · %1\$s", "Velg bil"),
            "values-fi" to listOf("Auto", "Auto · %1\$s", "Valitse auto"),
            "values-de" to listOf("Auto", "Auto · %1\$s", "Auto wählen"),
            "values-nl" to listOf("Auto", "Auto · %1\$s", "Auto kiezen"),
            "values-es" to listOf("Coche", "Coche · %1\$s", "Elegir coche"),
            "values-fr" to listOf("Voiture", "Voiture · %1\$s", "Choisir la voiture")
        )
        for ((directory, words) in expected) {
            assertEquals(directory, words, listOf("car_caption", "car_with", "car_caption_choose").map { text(directory, it) })
        }
    }
}
