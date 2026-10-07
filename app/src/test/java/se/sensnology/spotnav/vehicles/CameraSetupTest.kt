package se.sensnology.spotnav.vehicles

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.dashboard.CameraEntity
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.PictureKind
import se.sensnology.spotnav.ha.dashboard.ReferencePicture
import se.sensnology.spotnav.testing.DashboardFixtures
import java.time.ZoneId
import java.util.Locale

/**
 * What Settings shows of the charger's camera: the camera and its frame (its AI task is the Home
 * Assistant card's alone) under the charger's identification, each car's reference pictures, and nothing at all
 * where Home Assistant offers no camera or does not state the field. The car line names a car the
 * camera recognised.
 */
class CameraSetupTest {
    private fun block(references: JSONObject = JSONObject().put("vehicle_ev6", JSONArray()).put("vehicle_niro", JSONArray())) =
        JSONObject()
            .put("cameras", JSONArray()
                .put(JSONObject().put("entity_id", "camera.norr").put("name", "Norr"))
                .put(JSONObject().put("entity_id", "camera.entre").put("name", "Entré")))
            .put("ai_tasks", JSONArray().put(JSONObject().put("entity_id", "ai_task.ollama").put("name", "Ollama qwen3-vl")))
            .put("references", references)

    private fun chosen(aiTask: Any? = JSONObject.NULL, frame: Any? = JSONObject.NULL) =
        JSONObject().put("camera_entity_id", "camera.norr").put("ai_task_entity_id", aiTask).put("frame", frame)

    private fun dashboard(camera: Any? = JSONObject.NULL, block: JSONObject? = block(), edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONObject("settings").put("identify_camera", camera)
            block?.let { put("camera_identification", it) }
            edit()
        }

    @Test fun nothingIsShownWithoutTheBlockOrWithoutTheField() {
        val none = dashboard(block = null)
        assertNull(CameraSetup.section(none, none.settings))
        val older = dashboard { getJSONObject("settings").remove("identify_camera") }
        assertNull(CameraSetup.section(older, older.settings))
        assertNull(CameraSetup.references(older, older.settings, "vehicle_ev6"))
    }

    @Test fun noCameraIsOneRowThatSaysSo() {
        val dash = dashboard()
        val section = CameraSetup.section(dash, dash.settings)!!
        assertNull(section.chosen)
        assertNull(section.cameraName)
        // No camera, no reference pictures to take.
        assertNull(CameraSetup.references(dash, dash.settings, "vehicle_ev6"))
    }

    @Test fun aChosenCameraNamesItsFrameAndItsAiTask() {
        val dash = dashboard(chosen())
        val section = CameraSetup.section(dash, dash.settings)!!
        assertEquals("Norr", section.cameraName)
        assertFalse(section.frameDrawn)

        val framed = dashboard(chosen(aiTask = "ai_task.ollama", frame = JSONObject().put("x", 0.5).put("y", 0.2).put("w", 0.4).put("h", 0.5)))
        val drawn = CameraSetup.section(framed, framed.settings)!!
        assertTrue(drawn.frameDrawn)
        // The whole picture drawn as a frame reads as the whole picture.
        val whole = dashboard(chosen(frame = JSONObject().put("x", 0).put("y", 0).put("w", 1).put("h", 1)))
        assertFalse(CameraSetup.section(whole, whole.settings)!!.frameDrawn)
    }

    @Test fun aCameraThatIsGoneIsNamedByItsEntity() {
        val dash = dashboard(chosen().put("camera_entity_id", "camera.gone"))
        assertEquals("camera.gone", CameraSetup.section(dash, dash.settings)!!.cameraName)
    }

    @Test fun eachCarOfTheChargerHasItsReferencePicturesWithACameraChosen() {
        val day = JSONObject().put("kind", "day").put("taken_at", "2026-10-07T12:12:00+00:00").put("colour", true)
        val dash = dashboard(chosen(), block(JSONObject().put("vehicle_ev6", JSONArray().put(day)).put("vehicle_niro", JSONArray())))
        assertEquals(
            listOf(ReferencePicture(PictureKind.DAY, "2026-10-07T12:12:00+00:00", true)),
            CameraSetup.references(dash, dash.settings, "vehicle_ev6")
        )
        assertEquals(emptyList<ReferencePicture>(), CameraSetup.references(dash, dash.settings, "vehicle_niro"))
        // A car that is not this charger's has no row.
        assertNull(CameraSetup.references(dash, dash.settings, "vehicle_other"))
    }

    @Test fun aPictureSaysWhenItWasTaken() {
        val zone = ZoneId.of("Europe/Stockholm")
        assertEquals("Wed 7 Oct 14:12", CameraSetup.takenAt("2026-10-07T12:12:00+00:00", zone, Locale.ENGLISH))
        assertEquals("Tue 6 Oct 22:40", CameraSetup.takenAt("2026-10-06T22:40:00+02:00", zone, Locale.ENGLISH))
        assertEquals("not a time", CameraSetup.takenAt("not a time", zone, Locale.ENGLISH))
    }

    @Test fun theCarLineSaysACarTheCameraRecognised() {
        val identification = JSONObject().put("state", "decided").put("method", "camera").put("vehicle_id", "vehicle_ev6")
            .put("since", "2026-10-06T17:00:00+00:00").put("candidates", JSONArray())
            .put(
                "evidence", JSONArray()
                    .put(JSONObject().put("vehicle_id", "vehicle_ev6").put("verdict", JSONObject.NULL))
                    // The camera's own entry names no car: a client reading the list by car skips it.
                    .put(JSONObject().put("camera", JSONObject().put("entity_id", "camera.norr").put("answer", "vehicle_ev6")
                        .put("confidence", "high").put("used", true)))
            )
        val dash = dashboard { put("identification", identification) }
        assertEquals(VehicleIdentification.Basis.CAMERA, VehicleIdentification.carLine(dash)!!.basis)
        assertEquals(listOf("vehicle_ev6"), dash.identification!!.evidence.map { it.vehicleId })
    }

    @Test fun theChoicesAreTheBlocksOwn() {
        val dash = dashboard(chosen())
        assertEquals(
            listOf(CameraEntity("camera.norr", "Norr"), CameraEntity("camera.entre", "Entré")),
            CameraSetup.section(dash, dash.settings)!!.cameras
        )
    }

    @Test fun aCarsReferenceEditorHasADayAndANightSlotEachWithItsOwnButton() {
        val empty = CameraSetup.slots(emptyList())
        assertEquals(listOf(PictureKind.DAY, PictureKind.NIGHT), empty.map { it.kind })
        assertEquals(listOf(R.string.reference_take_day, R.string.reference_take_night), empty.map { it.takeLabel })
        assertEquals(listOf(false, false), empty.map { it.canDelete })

        val day = ReferencePicture(PictureKind.DAY, "2026-10-07T10:14:00+02:00", true)
        val dayOnly = CameraSetup.slots(listOf(day))
        assertEquals(day, dayOnly[0].picture)
        assertEquals(listOf(R.string.reference_retake, R.string.reference_take_night), dayOnly.map { it.takeLabel })
        assertEquals(listOf(true, false), dayOnly.map { it.canDelete })

        val night = ReferencePicture(PictureKind.NIGHT, "2026-10-06T22:40:00+02:00", false)
        val both = CameraSetup.slots(listOf(night, day))
        assertEquals(listOf(day, night), both.map { it.picture })
        assertEquals(listOf(R.string.reference_retake, R.string.reference_retake), both.map { it.takeLabel })
        assertEquals(listOf(true, true), both.map { it.canDelete })
    }
}
