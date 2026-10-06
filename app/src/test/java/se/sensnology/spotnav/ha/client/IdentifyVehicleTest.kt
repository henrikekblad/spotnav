package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.DashboardIdentification
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.testing.HaFixtures

/**
 * The webhook's `identify_vehicle` (the person's answer, or a correction of the car after a decision),
 * and a car's target written through `update_vehicle` (`changes.target_percent`).
 */
class IdentifyVehicleTest {
    @Test fun theRequestNamesTheCarAndAsksForTheWithheldFields() {
        val body = IdentifyVehicle.payload("vehicle_niro")
        assertEquals(1, body.getInt("version"))
        assertEquals("identify_vehicle", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals("vehicle_niro", body.getString("vehicle_id"))
        assertTrue(body.has("reads"))
    }

    @Test fun anAnswerCarriesTheBlockAfterIt() {
        val answer = JSONObject().put("api_version", 1).put("ok", true).put("error", JSONObject.NULL)
            .put("action", "identify_vehicle")
            .put(
                "identification", JSONObject().put("state", "decided").put("method", "answered")
                    .put("vehicle_id", "vehicle_niro").put("since", JSONObject.NULL)
                    .put("candidates", org.json.JSONArray())
            )
        val outcome = IdentifyVehicle.answer(200, answer.toString()) as IdentifyVehicle.Outcome.Identified
        assertEquals(DashboardIdentification.Method.ANSWERED, outcome.block!!.method)
        // A correction where nothing was being identified may answer without a block.
        val bare = JSONObject().put("api_version", 1).put("ok", true).put("error", JSONObject.NULL).put("identification", JSONObject.NULL)
        assertEquals(IdentifyVehicle.Outcome.Identified(null), IdentifyVehicle.answer(200, bare.toString()))
    }

    @Test fun eachRefusalIsReadByItsCode() {
        fun refusal(code: String) = JSONObject().put("api_version", 1).put("ok", false).put("error", code)
            .put("action", "identify_vehicle").toString()
        assertEquals(IdentifyVehicle.Outcome.NotPluggedIn, IdentifyVehicle.answer(400, refusal("spotnav_not_identifying")))
        assertEquals(IdentifyVehicle.Outcome.NotACandidate, IdentifyVehicle.answer(400, refusal("spotnav_invalid_value")))
        assertEquals(IdentifyVehicle.Outcome.NotSupported, IdentifyVehicle.answer(400, refusal("spotnav_unsupported_api_version")))
        assertEquals(IdentifyVehicle.Outcome.Failed("spotnav_unknown_charger"), IdentifyVehicle.answer(400, refusal("spotnav_unknown_charger")))
        assertEquals(IdentifyVehicle.Outcome.Failed(null), IdentifyVehicle.answer(null, null))
        assertEquals(IdentifyVehicle.Outcome.Failed(null), IdentifyVehicle.answer(500, "<html>"))
    }

    // A car's target, beside its battery size

    private fun row(target: Double? = 80.0, stated: Boolean = true) =
        DashboardVehicle("ev6", "EV6", 77.4, "stored", 1.8, null, null, targetPercent = target, targetStated = stated)

    @Test fun theVehicleDialogWritesTheTargetAsAWholePercentAgainstTheOneShown() {
        val draft = VehicleUpdate.draft(row(), "77.4", "1.8", targetText = "85") as VehicleUpdate.Draft.Write
        assertEquals(listOf(VehicleField.TARGET), draft.changes.map { it.field })
        val body = VehicleUpdate.payload("ev6", draft.changes)
        assertEquals(85, body.getJSONObject("changes").get("target_percent"))
        assertEquals(80, body.getJSONObject("expected").get("target_percent"))
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(row(), "77.4", "1.8", targetText = "80"))
    }

    @Test fun aTargetOutsideZeroToAHundredIsRefusedAndABlankOneLeftAlone() {
        val draft = VehicleUpdate.draft(row(), "77.4", "1.8", targetText = "120") as VehicleUpdate.Draft.Invalid
        assertEquals(VehicleFieldIssue.OUT_OF_RANGE, draft.issues[VehicleField.TARGET])
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(row(target = null), "77.4", "1.8", targetText = " "))
        // A row from a Home Assistant that does not state a target is never given one.
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(row(target = null, stated = false), "77.4", "1.8", targetText = "90"))
    }

    @Test fun aRefusedTargetIsMarkedOnItsField() {
        val answer = JSONObject(HaFixtures.root.resolve("webhook/update_vehicle_refused.json").readText())
        answer.put("field_errors", org.json.JSONArray().put(JSONObject().put("field", "target_percent").put("code", "invalid_target")))
        val outcome = VehicleUpdate.answer(400, answer.toString()) as VehicleUpdate.Outcome.Refused
        assertEquals(VehicleFieldIssue.OUT_OF_RANGE, outcome.issues[VehicleField.TARGET])
    }
}
