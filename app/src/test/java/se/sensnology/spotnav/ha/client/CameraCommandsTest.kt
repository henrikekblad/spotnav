package se.sensnology.spotnav.ha.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.PictureKind
import se.sensnology.spotnav.ha.dashboard.ReferencePicture
import se.sensnology.spotnav.ha.settings.CameraFrame
import se.sensnology.spotnav.ha.settings.HaCameraSettings
import java.util.Base64

/**
 * The camera's webhook actions (`docs/api.md`, "The camera"): the snapshot to draw the frame on, the
 * frame, and a car's reference pictures. Home Assistant crops and scales; the app only shows.
 */
class CameraCommandsTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun picture(data: String = Base64.getEncoder().encodeToString(jpeg), width: Any = 1920, height: Any = 1080): JSONObject =
        JSONObject().put("content_type", "image/jpeg").put("data", data).put("width", width).put("height", height)

    private fun ok(action: String): JSONObject =
        JSONObject().put("api_version", 1).put("ok", true).put("error", JSONObject.NULL).put("action", action)

    private fun refused(code: String): String =
        JSONObject().put("api_version", 1).put("ok", false).put("error", code).toString()

    @Test fun eachActionIsAskedWithItsOwnFieldsAndTheVersions() {
        val snapshot = CameraCommands.Snapshot.payload()
        assertEquals("camera_snapshot", snapshot.getString("action"))
        assertEquals(1, snapshot.getInt("version"))
        assertEquals(1, snapshot.getInt("api_version"))
        assertTrue(snapshot.has("reads"))
        // The chosen camera is Home Assistant's default; the app never names another.
        assertFalse(snapshot.has("camera_entity_id"))

        val frame = CameraCommands.SaveFrame(CameraFrame(0.1, 0.2, 0.3, 0.4)).payload()
        assertEquals("save_camera_frame", frame.getString("action"))
        assertEquals(0.3, frame.getJSONObject("frame").getDouble("w"), 0.0)
        val whole = CameraCommands.SaveFrame(null).payload()
        assertTrue(whole.has("frame") && whole.isNull("frame"))

        val take = CameraCommands.TakeReference("vehicle_ev6", PictureKind.NIGHT).payload()
        assertEquals("take_reference_picture", take.getString("action"))
        assertEquals("vehicle_ev6", take.getString("vehicle_id"))
        assertEquals("night", take.getString("kind"))

        // Delete with no kind deletes every one of the car's pictures: `kind` is there, and `null`.
        val delete = CameraCommands.DeleteReference("vehicle_ev6", null).payload()
        assertEquals("delete_reference_picture", delete.getString("action"))
        assertTrue(delete.has("kind") && delete.isNull("kind"))

        val thumbnail = CameraCommands.Reference("vehicle_ev6", PictureKind.DAY).payload()
        assertEquals("reference_picture", thumbnail.getString("action"))
        assertEquals("day", thumbnail.getString("kind"))
    }

    @Test fun aPictureIsTheJpegAndItsSize() {
        val outcome = CameraCommands.answer(CameraCommands.Snapshot, 200, ok("camera_snapshot").put("picture", picture()).toString())
        val shown = (outcome as CameraCommands.Outcome.Picture).picture
        assertArrayEquals(jpeg, shown.jpeg)
        assertEquals(1920, shown.width)
        assertEquals(1080, shown.height)
        val thumbnail = CameraCommands.answer(
            CameraCommands.Reference("vehicle_ev6", PictureKind.DAY), 200,
            ok("reference_picture").put("vehicle_id", "vehicle_ev6").put("kind", "day").put("picture", picture(width = 240, height = 135)).toString()
        )
        assertEquals(240, (thumbnail as CameraCommands.Outcome.Picture).picture.width)
    }

    @Test fun aPictureThisAppCannotShowIsAFailure() {
        for (bad in listOf(
            picture(data = "not base64!"),
            picture(width = 0),
            picture(height = "1080"),
            picture().put("content_type", "image/png")
        )) {
            val outcome = CameraCommands.answer(CameraCommands.Snapshot, 200, ok("camera_snapshot").put("picture", bad).toString())
            assertEquals(bad.toString(), CameraCommands.Outcome.Failed(null), outcome)
        }
        assertEquals(CameraCommands.Outcome.Failed(null), CameraCommands.answer(CameraCommands.Snapshot, 200, ok("camera_snapshot").toString()))
    }

    @Test fun theFrameAnswerIsTheCameraAfterTheWrite() {
        val camera = JSONObject().put("camera_entity_id", "camera.norr").put("ai_task_entity_id", JSONObject.NULL)
            .put("frame", JSONObject().put("x", 0.5).put("y", 0.25).put("w", 0.4).put("h", 0.5))
        val outcome = CameraCommands.answer(
            CameraCommands.SaveFrame(CameraFrame(0.5, 0.25, 0.4, 0.5)), 200,
            ok("save_camera_frame").put("identify_camera", camera).toString()
        )
        assertEquals(
            CameraCommands.Outcome.Framed(HaCameraSettings("camera.norr", null, CameraFrame(0.5, 0.25, 0.4, 0.5))),
            outcome
        )
    }

    @Test fun aReferenceAnswerIsTheCarsPicturesAfterIt() {
        val references = JSONArray()
            .put(JSONObject().put("kind", "day").put("taken_at", "2026-10-07T12:12:00+00:00").put("colour", true))
        val outcome = CameraCommands.answer(
            CameraCommands.TakeReference("vehicle_ev6", PictureKind.DAY), 200,
            ok("take_reference_picture").put("vehicle_id", "vehicle_ev6").put("references", references).toString()
        )
        assertEquals(
            CameraCommands.Outcome.References("vehicle_ev6", listOf(ReferencePicture(PictureKind.DAY, "2026-10-07T12:12:00+00:00", true))),
            outcome
        )
        val gone = CameraCommands.answer(
            CameraCommands.DeleteReference("vehicle_ev6", null), 200,
            ok("delete_reference_picture").put("vehicle_id", "vehicle_ev6").put("references", JSONArray()).toString()
        )
        assertEquals(CameraCommands.Outcome.References("vehicle_ev6", emptyList()), gone)
    }

    @Test fun refusalsKeepTheirCodeAndNoAnswerIsAFailure() {
        assertEquals(CameraCommands.Outcome.Failed("spotnav_no_camera"), CameraCommands.answer(CameraCommands.Snapshot, 400, refused("spotnav_no_camera")))
        assertEquals(CameraCommands.Outcome.Failed("spotnav_no_picture"), CameraCommands.answer(CameraCommands.Snapshot, 400, refused("spotnav_no_picture")))
        assertEquals(CameraCommands.Outcome.Failed(null), CameraCommands.answer(CameraCommands.Snapshot, null, null))
        // An older Home Assistant does not know the action at all.
        assertEquals(CameraCommands.Outcome.Failed(null), CameraCommands.answer(CameraCommands.Snapshot, 400, "Unknown action"))
    }
}
