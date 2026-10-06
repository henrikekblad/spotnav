package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures

/**
 * The dashboard's additive `camera_identification` block: the cameras and AI Task entities to choose
 * from and each car's reference pictures (never the pictures), read leniently and absent from an older
 * Home Assistant or one without a camera.
 */
class DashboardCameraTest {
    private fun block(): JSONObject = JSONObject()
        .put("cameras", JSONArray().put(JSONObject().put("entity_id", "camera.norr").put("name", "Norr")))
        .put("ai_tasks", JSONArray().put(JSONObject().put("entity_id", "ai_task.ollama").put("name", "Ollama qwen3-vl")))
        .put(
            "references", JSONObject()
                .put(
                    "vehicle_ev6", JSONArray()
                        .put(JSONObject().put("kind", "day").put("taken_at", "2026-10-07T12:12:00+00:00").put("colour", true))
                        .put(JSONObject().put("kind", "night").put("taken_at", "2026-10-06T22:40:00+00:00").put("colour", false))
                )
                .put("vehicle_niro", JSONArray())
        )

    @Test fun theVendoredDashboardsHaveNoBlock() {
        assertNull(DashboardFixtures.dashboard("target_soc_two_vehicles.json").cameraIdentification)
        assertNull(DashboardFixtures.dashboard().cameraIdentification)
    }

    @Test fun theBlockIsReadWithItsChoicesAndEachCarsPictures() {
        val camera = DashboardFixtures.dashboard("target_soc_two_vehicles.json") { put("camera_identification", block()) }
            .cameraIdentification!!
        assertEquals(listOf(CameraEntity("camera.norr", "Norr")), camera.cameras)
        assertEquals(listOf(CameraEntity("ai_task.ollama", "Ollama qwen3-vl")), camera.aiTasks)
        assertEquals(
            listOf(
                ReferencePicture(PictureKind.DAY, "2026-10-07T12:12:00+00:00", colour = true),
                ReferencePicture(PictureKind.NIGHT, "2026-10-06T22:40:00+00:00", colour = false)
            ),
            camera.references["vehicle_ev6"]
        )
        assertEquals(emptyList<ReferencePicture>(), camera.references["vehicle_niro"])
        assertNull(camera.references["vehicle_other"])
    }

    @Test fun aPictureTakenWithAnotherFrameIsMarkedStale() {
        val stale = block().apply {
            getJSONObject("references").getJSONArray("vehicle_ev6").getJSONObject(0).put("stale", true)
            getJSONObject("references").getJSONArray("vehicle_ev6").getJSONObject(1).put("stale", "yes")
        }
        val pictures = DashboardFixtures.dashboard { put("camera_identification", stale) }.cameraIdentification!!.references["vehicle_ev6"]!!
        assertEquals(listOf(true, false), pictures.map { it.stale })
    }

    @Test fun anEntryThisAppCannotReadIsLeftOutAndABlockItCannotReadIsNone() {
        val odd = block().apply {
            getJSONArray("cameras").put(JSONObject().put("name", "No id")).put("camera.bare")
            getJSONObject("references").getJSONArray("vehicle_ev6")
                .put(JSONObject().put("kind", "dusk").put("taken_at", "2026-10-07T12:00:00+00:00"))
                .put(JSONObject().put("kind", "day"))
        }
        val camera = DashboardFixtures.dashboard { put("camera_identification", odd) }.cameraIdentification!!
        assertEquals(listOf("camera.norr"), camera.cameras.map { it.entityId })
        assertEquals(listOf(PictureKind.DAY, PictureKind.NIGHT), camera.references["vehicle_ev6"]!!.map { it.kind })
        // A camera without a name is named by its entity.
        val unnamed = block().put("cameras", JSONArray().put(JSONObject().put("entity_id", "camera.norr")))
        assertEquals("camera.norr", DashboardFixtures.dashboard { put("camera_identification", unnamed) }.cameraIdentification!!.cameras[0].name)
        for (bad in listOf<Any>("yes", JSONObject.NULL, JSONObject().put("cameras", "camera.norr"))) {
            assertNull(DashboardFixtures.dashboard { put("camera_identification", bad) }.cameraIdentification)
        }
    }
}
