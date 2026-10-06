package se.sensnology.spotnav.ha.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures.response

/**
 * The settings record's `identify_camera` (the charger's camera for identification): withheld from an
 * app that does not ask, so this app asks; read when stated, sent back as read, changed only by the
 * camera edit (the frame stays with its camera), and absent from an older Home Assistant.
 */
class CameraSettingsCodecTest {
    private fun camera(entity: Any? = "camera.norr", aiTask: Any? = JSONObject.NULL, frame: Any? = JSONObject.NULL): JSONObject =
        JSONObject().put("camera_entity_id", entity).put("ai_task_entity_id", aiTask).put("frame", frame)

    private fun frame(x: Double = 0.5, y: Double = 0.25, w: Double = 0.4, h: Double = 0.5): JSONObject =
        JSONObject().put("x", x).put("y", y).put("w", w).put("h", h)

    private fun withCamera(value: Any? = JSONObject.NULL, revision: Int = 3): JSONObject =
        response(revision = revision).put("identify_mode", "automatic").put("vehicle_ids", JSONObject.NULL)
            .put("identify_camera", value)

    private fun ready(record: HaPlanningSettings, edit: HaSettingsEdit): HaPlanningSettings =
        (HaSettingsEditor.replacement(record, edit) as HaSettingsEditResult.Ready).settings

    @Test fun everyRequestAsksForTheCamera() {
        for (command in listOf(HomeAssistantCommand("dashboard"), HomeAssistantCommand("settings"), HomeAssistantCommand("start"))) {
            val reads = JSONObject(HomeAssistantClient.payload(command)).getJSONArray("reads")
            assertTrue(List(reads.length()) { reads.getString(it) }.contains("identify_camera"))
        }
    }

    @Test fun theVendoredAnswersStateNoCamera() {
        val record = HaSettingsCodec.parseResponse(HaFixtures.json("settings/v1/success.json").getJSONObject("settings"))
        assertEquals(HaCameraChoice(null), record.camera)
        val dashboard = Dashboard.parse(HaFixtures.json("dashboard/target_soc_two_vehicles.json"))
        assertEquals(HaCameraChoice(null), dashboard.settings!!.camera)
    }

    @Test fun aCameraWithItsAiTaskAndFrameIsReadAndSentBackAsRead() {
        val record = HaSettingsCodec.parseResponse(withCamera(camera(aiTask = "ai_task.ollama", frame = frame())))
        assertEquals(
            HaCameraChoice(HaCameraSettings("camera.norr", "ai_task.ollama", CameraFrame(0.5, 0.25, 0.4, 0.5))),
            record.camera
        )
        val body = HaSettingsCodec.encodeBody(record).getJSONObject("identify_camera")
        assertEquals("camera.norr", body.getString("camera_entity_id"))
        assertEquals("ai_task.ollama", body.getString("ai_task_entity_id"))
        assertEquals(0.4, body.getJSONObject("frame").getDouble("w"), 0.0)
        assertEquals(record, HaSettingsCodec.parseStored(HaSettingsCodec.encode(record)))
        // No camera is stated as `null`, and sent back as `null`.
        val none = HaSettingsCodec.encodeBody(HaSettingsCodec.parseResponse(withCamera()))
        assertTrue(none.has("identify_camera") && none.isNull("identify_camera"))
        // The default AI task and the whole picture are `null` too.
        val bare = HaSettingsCodec.encodeBody(HaSettingsCodec.parseResponse(withCamera(camera()))).getJSONObject("identify_camera")
        assertTrue(bare.isNull("ai_task_entity_id") && bare.isNull("frame"))
    }

    @Test fun anAnswerWithoutItStatesNothingAndWritesNothingBack() {
        val record = HaSettingsCodec.parseResponse(response(revision = 3))
        assertNull(record.camera)
        assertFalse(HaSettingsCodec.encodeBody(record).has("identify_camera"))
        assertFalse(HaSettingsCodec.encode(record).has("identify_camera"))
    }

    @Test fun anAnswerThisAppCannotReadIsNotStatedAndNeverRefusesTheRecord() {
        for (value in listOf(
            "camera.norr",
            camera(entity = "sensor.norr"),
            camera(aiTask = "conversation.ollama"),
            camera(frame = frame(w = 0.01)),
            camera(frame = frame(x = 0.8, w = 0.4)),
            camera(frame = JSONObject().put("x", 0.1))
        )) {
            assertNull(value.toString(), HaSettingsCodec.parseResponse(withCamera(value)).camera)
        }
    }

    @Test fun aBodyWithABadCameraIsRefusedWithTheContractsCode() {
        val body = HaSettingsCodec.encodeBody(HaSettingsCodec.parseResponse(withCamera()))
        for (bad in listOf(camera(entity = "sensor.norr"), camera(frame = frame(h = 0.9)), "camera.norr")) {
            val refusal = assertThrows(HaSettingsFormatException::class.java) {
                HaSettingsCodec.parseBody(JSONObject(body.toString()).put("identify_camera", bad))
            }
            assertEquals("invalid_camera", refusal.code)
        }
    }

    @Test fun theCameraEditKeepsTheFrameWithItsCameraAndEveryOtherEditKeepsTheCamera() {
        val record = HaSettingsCodec.parseResponse(withCamera(camera(aiTask = "ai_task.ollama", frame = frame())))
        val framed = CameraFrame(0.5, 0.25, 0.4, 0.5)
        // Another AI task: the camera and its frame stay.
        assertEquals(
            HaCameraChoice(HaCameraSettings("camera.norr", null, framed)),
            ready(record, HaSettingsEdit.Camera("camera.norr", null)).camera
        )
        // Another camera starts with the whole picture, and keeps the AI task chosen.
        assertEquals(
            HaCameraChoice(HaCameraSettings("camera.entre", "ai_task.ollama", null)),
            ready(record, HaSettingsEdit.Camera("camera.entre", "ai_task.ollama")).camera
        )
        assertEquals(HaCameraChoice(null), ready(record, HaSettingsEdit.Camera(null, null)).camera)
        assertEquals(record.camera, ready(record, HaSettingsEdit.Amps(10)).camera)
        assertEquals(record.camera, ready(record, HaSettingsEdit.Identification(IdentifyMode.ASK, null)).camera)
    }

    @Test fun theEditIsRefusedForARecordWithoutTheFieldOrWithABadEntity() {
        val older = HaSettingsCodec.parseResponse(response(revision = 3))
        assertEquals(
            HaSettingsEditResult.Refused("invalid_camera"),
            HaSettingsEditor.replacement(older, HaSettingsEdit.Camera("camera.norr", null))
        )
        val record = HaSettingsCodec.parseResponse(withCamera())
        assertEquals(
            HaSettingsEditResult.Refused("invalid_camera"),
            HaSettingsEditor.replacement(record, HaSettingsEdit.Camera("light.norr", null))
        )
    }

    @Test fun theStoredCopyTakesItWhenItIsFirstStatedAndNeverLosesIt() {
        val store = ConfirmedSettingsStore(FakeKeyValueStore())
        store.record("p", HaSettingsCodec.parseResponse(response(revision = 3)))
        store.record("p", HaSettingsCodec.parseResponse(withCamera(camera())))
        assertEquals("camera.norr", store.confirmed("p")!!.camera!!.camera!!.cameraEntityId)
        // A copy at the same revision that does not state it never takes it away.
        store.record("p", HaSettingsCodec.parseResponse(response(revision = 3).put("identify_mode", "automatic").put("vehicle_ids", JSONObject.NULL)))
        assertEquals("camera.norr", store.confirmed("p")!!.camera!!.camera!!.cameraEntityId)
    }
}
