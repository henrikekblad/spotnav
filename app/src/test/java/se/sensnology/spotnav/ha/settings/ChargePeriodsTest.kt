package se.sensnology.spotnav.ha.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures.response
import java.io.File

/**
 * Charge periods, a charger setting in Home Assistant: `max_periods` is `null` for automatic (the default) or a
 * cap of 1 to 8. The app asks for `null` (`auto_periods`), reads and writes it, and offers the choice as one
 * row of nine options; the local planner keeps its own setting.
 */
class ChargePeriodsTest {
    private fun ready(record: HaPlanningSettings, edit: HaSettingsEdit): HaPlanningSettings =
        (HaSettingsEditor.replacement(record, edit) as HaSettingsEditResult.Ready).settings

    @Test fun theAppAsksForAutomaticPeriods() {
        assertTrue("auto_periods" in WebhookReads.FIELDS)
    }

    @Test fun nullIsAutomaticAndIsSentBackAsNull() {
        val record = HaSettingsCodec.parseResponse(response(maxPeriods = null))
        assertNull(record.maxPeriods)
        val body = HaSettingsCodec.encodeBody(record)
        assertTrue(body.has("max_periods") && body.isNull("max_periods"))
        assertEquals(record, HaSettingsCodec.parseStored(HaSettingsCodec.encode(record)))
    }

    @Test fun aNumberStaysANumber() {
        assertEquals(3, HaSettingsCodec.parseResponse(response(maxPeriods = 3)).maxPeriods)
    }

    @Test fun anythingElseIsRefusedWithTheContractsCode() {
        for (value in listOf<Any>(0, 9, "auto", 1.5)) {
            val refusal = assertThrows(HaSettingsFormatException::class.java) {
                HaSettingsCodec.parseResponse(response(maxPeriods = value))
            }
            assertEquals("$value", "invalid_periods", refusal.code)
        }
    }

    @Test fun theEditSetsAutomaticOrANumber() {
        val auto = HaSettingsCodec.parseResponse(response(maxPeriods = null))
        assertEquals(5, ready(auto, HaSettingsEdit.MaxPeriods(5)).maxPeriods)
        assertNull(ready(ready(auto, HaSettingsEdit.MaxPeriods(5)), HaSettingsEdit.MaxPeriods(null)).maxPeriods)
    }

    @Test fun theChoiceIsAutomaticThenOneToEight() {
        assertEquals(listOf(null, 1, 2, 3, 4, 5, 6, 7, 8), ChargePeriods.OPTIONS)
        assertEquals(0, ChargePeriods.indexOf(null))
        assertEquals(3, ChargePeriods.indexOf(3))
        // A value out of the choice's range is shown as automatic, never clamped into a number.
        assertEquals(0, ChargePeriods.indexOf(12))
    }

    @Test fun theVendoredFixturesSayAutomaticOverTheSocketAndTheNumberToAnAppThatDoesNotAsk() {
        val dashboard = Dashboard.parse(HaFixtures.json("dashboard/target_soc_estimated.json"))
        assertNull(dashboard.settings!!.maxPeriods)
        val settings: JSONObject = HaFixtures.json("webhook/dashboard.json").getJSONObject("settings")
        assertEquals(8, settings.getInt("max_periods"))
    }

    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    private fun text(directory: String, name: String) =
        Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(xml(directory))?.groupValues?.get(1)

    @Test fun theWordsAreThereInEveryLanguage() {
        assertEquals("Laddperioder", text("values-sv", "charge_periods_title"))
        assertEquals("Automatiskt", text("values-sv", "charge_periods_auto"))
        for (directory in listOf("values", "values-sv", "values-da", "values-nb", "values-fi", "values-de", "values-nl", "values-es", "values-fr")) {
            for (name in listOf("charge_periods_title", "charge_periods_auto", "charge_periods_auto_help", "charge_periods_help")) {
                val value = text(directory, name)
                assertTrue("$directory $name", value != null && value.isNotBlank())
            }
        }
    }
}
