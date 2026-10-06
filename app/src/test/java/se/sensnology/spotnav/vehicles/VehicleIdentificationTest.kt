package se.sensnology.spotnav.vehicles

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.IdentificationSource
import se.sensnology.spotnav.ha.settings.IdentifyMode
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * What the screen shows of "which car is plugged in?": the banner with one button per car, the car
 * line and how the car was decided, Byt bil's choices and hints, the vehicle card's assumed chip, the
 * Settings section's cars and mode, and a car's sources.
 */
class VehicleIdentificationTest {
    private fun block(state: String, method: String = "assumed", vehicle: String = "vehicle_ev6", evidence: JSONArray = JSONArray()) =
        JSONObject().put("state", state).put("method", method).put("vehicle_id", vehicle)
            .put("since", "2026-10-06T17:00:00+00:00")
            .put(
                "candidates", JSONArray()
                    .put(JSONObject().put("vehicle_id", "vehicle_niro").put("name", "Niro").put("likely", true))
                    .put(JSONObject().put("vehicle_id", "vehicle_ev6").put("name", "EV6").put("likely", false))
            )
            .put("evidence", evidence)

    private fun dashboard(identification: JSONObject? = null, edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            put("identification", identification ?: JSONObject.NULL)
            edit()
        }

    private fun older(): Dashboard = dashboard {
        getJSONObject("settings").remove("identify_mode")
        getJSONObject("settings").remove("vehicle_ids")
    }

    @Test fun anOlderHomeAssistantShowsNothingNewAndKeepsThePicker() {
        val dash = older()
        assertFalse(VehicleIdentification.advertised(dash))
        // The car line is there, but says nothing of how the car was decided.
        val line = VehicleIdentification.carLine(dash)!!
        assertNull(line.basis)
        assertTrue(line.canSwitch)
        assertNull(VehicleIdentification.banner(dash))
        assertFalse(VehicleIdentification.replacesPicker(dash))
        assertNull(VehicleIdentification.section(dash, dash.settings))
    }

    @Test fun anOpenQuestionIsABannerWithACarButtonEachInTheGivenOrderAndTheCarKept() {
        val dash = dashboard(block("asking"))
        val banner = VehicleIdentification.banner(dash)!!
        assertEquals(listOf("vehicle_niro", "vehicle_ev6"), banner.choices.map { it.vehicleId })
        assertEquals("EV6", banner.keptName)
        assertTrue(banner.carsCouldNotTell)
        assertTrue(VehicleIdentification.waitingForAnswer(dash))
        // While it asks, the car line stays above the banner, saying the car is assumed.
        assertEquals(VehicleIdentification.Basis.ASSUMED, VehicleIdentification.carLine(dash)!!.basis)
        // "Always ask" asks without the cars having tried.
        val ask = dashboard(block("asking")) { getJSONObject("settings").put("identify_mode", "ask") }
        assertFalse(VehicleIdentification.banner(ask)!!.carsCouldNotTell)
    }

    @Test fun theCarLineSaysHowTheCarWasDecided() {
        for ((method, basis) in listOf(
            "plug_sensor" to VehicleIdentification.Basis.PLUG_SENSOR,
            "location" to VehicleIdentification.Basis.LOCATION,
            // An answer and a choice in the settings read the same on the line: chosen manually.
            "answered" to VehicleIdentification.Basis.CHOSEN_MANUALLY,
            "manual" to VehicleIdentification.Basis.CHOSEN_MANUALLY,
            "assumed" to VehicleIdentification.Basis.ASSUMED
        )) {
            val line = VehicleIdentification.carLine(dashboard(block("decided", method = method)))!!
            assertEquals("vehicle_ev6", line.vehicleId)
            assertEquals("EV6", line.name)
            assertEquals(basis, line.basis)
        }
        assertEquals(VehicleIdentification.Basis.IDENTIFYING, VehicleIdentification.carLine(dashboard(block("waiting")))!!.basis)
        assertFalse(VehicleIdentification.waitingForAnswer(dashboard(block("waiting"))))
    }

    @Test fun withNothingBeingIdentifiedTheCarLineNamesThePlannedCarAndSaysNoMore() {
        val line = VehicleIdentification.carLine(dashboard())!!
        assertEquals("vehicle_ev6", line.vehicleId)
        assertNull(line.basis)
        assertTrue(line.canSwitch)
        // One car at the charger: the car and its levels, nothing to identify and nothing to change.
        val one = dashboard(block("decided", method = "plug_sensor")) {
            getJSONObject("settings").put("vehicle_ids", JSONArray().put("vehicle_ev6"))
            getJSONArray("vehicles").remove(1)
            put("identification", JSONObject.NULL)
        }
        val single = VehicleIdentification.carLine(one)!!
        assertEquals("EV6", single.name)
        assertNull(single.basis)
        assertFalse(single.canSwitch)
    }

    @Test fun bytBilOffersTheCandidatesWithTheirEvidenceAndTheCurrentCarChosen() {
        val evidence = JSONArray()
            .put(JSONObject().put("vehicle_id", "vehicle_niro").put("plug", JSONObject.NULL).put("location", JSONObject.NULL).put("verdict", "plugged_in"))
            .put(JSONObject().put("vehicle_id", "vehicle_ev6").put("plug", JSONObject.NULL).put("location", JSONObject.NULL).put("verdict", "not_plugged_in"))
        val switch = VehicleIdentification.switchChoices(dashboard(block("decided", evidence = evidence)))
        assertEquals("vehicle_ev6", switch.current)
        assertEquals(listOf("vehicle_niro", "vehicle_ev6"), switch.choices.map { it.vehicleId })
        assertEquals(
            listOf(VehicleIdentification.Hint.PLUGGED_IN, VehicleIdentification.Hint.NOT_PLUGGED_IN),
            switch.choices.map { it.hint }
        )
        fun hint(verdict: String?) = VehicleIdentification.Hint.of(verdict)
        assertEquals(VehicleIdentification.Hint.PLUGGED_IN, hint("likely"))
        assertEquals(VehicleIdentification.Hint.AWAY, hint("away"))
        assertEquals(VehicleIdentification.Hint.ELSEWHERE, hint("elsewhere"))
        assertNull(hint(null))
        assertNull(hint("unheard_of"))
        // Nothing being identified: the cars at the charger, in the dashboard's order.
        val plain = VehicleIdentification.switchChoices(dashboard())
        assertEquals(listOf("vehicle_ev6", "vehicle_niro"), plain.choices.map { it.vehicleId })
        assertTrue(plain.choices.all { it.hint == null })
    }

    @Test fun theVehicleCardMarksAnAssumedCarAndLeavesThePickerToBytBil() {
        assertTrue(VehicleIdentification.assumed(dashboard(block("asking"))))
        assertTrue(VehicleIdentification.assumed(dashboard(block("decided", method = "assumed"))))
        assertFalse(VehicleIdentification.assumed(dashboard(block("decided", method = "plug_sensor"))))
        assertFalse(VehicleIdentification.assumed(dashboard()))
        assertTrue(VehicleIdentification.replacesPicker(dashboard()))
    }

    @Test fun theSettingsSectionListsTheCarsTickedAndTheMode() {
        val dash = dashboard()
        val section = VehicleIdentification.section(dash, dash.settings)!!
        assertEquals(IdentifyMode.AUTOMATIC, section.mode)
        assertEquals(listOf("vehicle_ev6" to true, "vehicle_niro" to true), section.cars.map { it.vehicleId to it.ticked })
        val some = dashboard { getJSONObject("settings").put("vehicle_ids", JSONArray().put("vehicle_niro")).put("identify_mode", "off") }
        val limited = VehicleIdentification.section(some, some.settings)!!
        assertEquals(IdentifyMode.OFF, limited.mode)
        assertEquals(listOf(false, true), limited.cars.map { it.ticked })
        // One car and nothing restricted: there is nothing to identify.
        val one = dashboard { getJSONArray("vehicles").remove(1); getJSONArray("vehicle_choices").remove(1) }
        assertNull(VehicleIdentification.section(one, one.settings))
    }

    @Test fun theCarsAreEveryDetectedCarSoOneLeftOutCanBeTickedAgain() {
        // `vehicles` names only the charger's cars; `vehicle_choices` every detected car.
        val limited = dashboard {
            getJSONObject("settings").put("vehicle_ids", JSONArray().put("vehicle_niro"))
            getJSONArray("vehicles").remove(0)
        }
        val section = VehicleIdentification.section(limited, limited.settings)!!
        assertEquals(listOf("vehicle_ev6" to false, "vehicle_niro" to true), section.cars.map { it.vehicleId to it.ticked })
        assertEquals(listOf("EV6", "Niro"), section.cars.map { it.name })
        // Ticking every car is every car.
        assertNull(VehicleIdentification.vehicleIdsFor(section.cars.map { it.vehicleId }, setOf("vehicle_ev6", "vehicle_niro")))
    }

    @Test fun anOlderHomeAssistantWithoutTheChoicesListsTheChargersCars() {
        val older = dashboard { remove("vehicle_choices") }
        assertEquals(listOf("vehicle_ev6", "vehicle_niro"), VehicleIdentification.section(older, older.settings)!!.cars.map { it.vehicleId })
        val one = dashboard {
            put("vehicle_choices", JSONArray().put(JSONObject().put("id", "vehicle_ev6").put("name", "EV6")))
        }
        // Fewer than two cars to choose from: no section.
        assertNull(VehicleIdentification.section(one, one.settings))
    }

    @Test fun theCarsRowNamesTheTickedCarsOrSaysEveryCar() {
        val dash = dashboard()
        assertNull(VehicleIdentification.tickedNames(VehicleIdentification.section(dash, dash.settings)!!))
        val some = dashboard { getJSONObject("settings").put("vehicle_ids", JSONArray().put("vehicle_niro")) }
        assertEquals(listOf("Niro"), VehicleIdentification.tickedNames(VehicleIdentification.section(some, some.settings)!!))
    }

    @Test fun savingTheCarsWritesEveryCarAsNullAndNeverNone() {
        val all = listOf("vehicle_ev6", "vehicle_niro")
        assertNull(VehicleIdentification.vehicleIdsFor(all, setOf("vehicle_niro", "vehicle_ev6")))
        assertEquals(listOf("vehicle_niro"), VehicleIdentification.vehicleIdsFor(all, setOf("vehicle_niro")))
        assertEquals(emptyList<String>(), VehicleIdentification.vehicleIdsFor(all, emptySet()))
    }

    @Test fun aSourceIsNamedNoneChooseOrNotFound() {
        assertEquals(
            VehicleIdentification.SourceText.Named("EV6 Plugged in"),
            VehicleIdentification.sourceText(IdentificationSource("binary_sensor.p", "EV6 Plugged in", false, emptyList()))
        )
        assertEquals(
            VehicleIdentification.SourceText.Named("binary_sensor.p"),
            VehicleIdentification.sourceText(IdentificationSource("binary_sensor.p", null, true, emptyList()))
        )
        assertEquals(VehicleIdentification.SourceText.None, VehicleIdentification.sourceText(IdentificationSource(null, null, true, emptyList())))
        assertEquals(
            VehicleIdentification.SourceText.Choose,
            VehicleIdentification.sourceText(
                IdentificationSource(null, null, false, listOf(
                    se.sensnology.spotnav.ha.dashboard.IdentificationEntity("a", "A"),
                    se.sensnology.spotnav.ha.dashboard.IdentificationEntity("b", "B")
                ))
            )
        )
        assertEquals(VehicleIdentification.SourceText.NotFound, VehicleIdentification.sourceText(IdentificationSource(null, null, false, emptyList())))
    }
}
