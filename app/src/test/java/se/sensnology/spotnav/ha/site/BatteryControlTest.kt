package se.sensnology.spotnav.ha.site

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

/** The battery control document and its one write (`battery_mode`) against the integration's own fixtures. */
class BatteryControlTest {
    private fun fixture(path: String) = HaFixtures.json(path).toString()

    private fun status(path: String): BatteryStatus =
        (BatteryControlRead.answer(200, fixture(path)) as BatteryControlRead.Outcome.Status).status

    @Test fun theWebhookAnswersDecodeToTheStatusTheCardShows() {
        val off = status("webhook/battery_control_success.json")
        assertEquals("balanced", off.mode)
        assertEquals(BatteryStatus.Control.OFF, off.control)
        assertEquals("off", off.line)
        assertEquals(0.0, off.gridChargeCap.capKw!!, 0.0)
        assertEquals("conservative", off.gridChargeCap.rule)
        assertEquals(14, off.gridChargeCap.daysLeft)
        assertEquals("max_profit", status("webhook/battery_control_mode.json").mode)
    }

    @Test fun theRicherStatusesSayOnOffOrTest() {
        // Off while shadow only is the switch's own default: off, not test.
        assertEquals(BatteryStatus.Control.OFF, status("battery_control/live.json").control)
        assertEquals(BatteryStatus.Control.ON, status("battery_control/needs_ack.json").control)
        val checking = status("battery_control/checking.json")
        assertEquals(BatteryStatus.Control.ON, checking.control)
        assertEquals(3.2, checking.gridChargeCap.capKw!!, 0.0)
        // Control on paper only is test, whatever the state says first.
        assertEquals(BatteryStatus.Control.TEST, checking.copy(shadowOnly = true).control)
        assertEquals(BatteryStatus.Control.TEST, checking.copy(state = "shadow_only", enabled = false).control)
    }

    @Test fun aCapWithoutTheLearningIsStillRead() {
        val noLearning = status("battery_control/needs_ack.json")
        assertEquals("import_limit", noLearning.gridChargeCap.rule)
        assertNull(noLearning.gridChargeCap.capKw)
        assertNull(noLearning.gridChargeCap.daysLeft)
        assertEquals(listOf("grid_charge", "hold"), noLearning.allowedActions)
    }

    @Test fun theRefusalsHaveTheirOwnOutcomes() {
        assertEquals(BatteryControlRead.Outcome.NotPermitted, BatteryControlRead.answer(403, fixture("webhook/battery_control_not_permitted.json")))
        assertEquals(BatteryControlRead.Outcome.Invalid, BatteryControlRead.answer(400, fixture("webhook/battery_control_invalid_value.json")))
        val noSite = """{"ok":false,"error":"spotnav_no_site","action":"battery_control"}"""
        assertEquals(BatteryControlRead.Outcome.NoSite, BatteryControlRead.answer(400, noSite))
        val version = """{"ok":false,"error":"spotnav_unsupported_api_version","action":"battery_control"}"""
        assertEquals(BatteryControlRead.Outcome.NotSupported, BatteryControlRead.answer(400, version))
        assertTrue(BatteryControlRead.answer(null, null) is BatteryControlRead.Outcome.Failed)
        assertTrue(BatteryControlRead.answer(200, "not json") is BatteryControlRead.Outcome.Failed)
    }

    @Test fun aRequiredKeyThatIsMissingOrMistypedIsNoDocumentAnUnknownKeyIsNotLookedAt() {
        val json = HaFixtures.json("webhook/battery_control_success.json")
        val extra = JSONObject(json.toString()).put("brand_new", 1)
        extra.getJSONObject("status").put("another", "x")
        assertTrue(BatteryControlRead.decode(extra) is BatteryControlRead.Outcome.Status)
        for (key in listOf("state", "line", "options", "grid_charge_cap", "allowed_actions")) {
            val broken = JSONObject(json.toString())
            broken.getJSONObject("status").remove(key)
            assertTrue(key, BatteryControlRead.decode(broken) is BatteryControlRead.Outcome.Failed)
        }
        val mistyped = JSONObject(json.toString())
        mistyped.getJSONObject("status").getJSONObject("options").put("battery_mode", 3)
        assertTrue(BatteryControlRead.decode(mistyped) is BatteryControlRead.Outcome.Failed)
        val another = JSONObject(json.toString()).put("api_version", 2)
        assertEquals(BatteryControlRead.Outcome.NotSupported, BatteryControlRead.decode(another))
    }

    @Test fun theRequestCarriesTheModeAloneAndNothingWithoutOne() {
        val read = BatteryControlRead.payload()
        assertEquals("battery_control", read.getString("action"))
        assertEquals(1, read.getInt("api_version"))
        assertTrue(!read.has("changes"))
        val write = BatteryControlRead.payload("max_profit")
        assertEquals(listOf("battery_mode"), write.getJSONObject("changes").keys().asSequence().toList())
        assertEquals("max_profit", write.getJSONObject("changes").getString("battery_mode"))
    }
}
