package se.sensnology.spotnav.ha.site

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.ui.settings.SiteSettingsText
import java.util.Locale

/** "No export at negative prices" as `get_site_settings_view` states it, shown read-only in the card's words. */
class ExportCurtailTest {
    private fun view(name: String): SiteView = (SiteViewRead.answer(200, HaFixtures.json("webhook/$name").toString()) as SiteRead.Read).value

    private fun found(edit: JSONObject.() -> Unit = {}): ExportCurtail {
        val json = HaFixtures.json("webhook/site_webhook_get_site_settings_view.json")
        json.getJSONObject("export_curtail").apply { put("found", true); put("brand", "sigenergy"); put("state", "off") }.apply(edit)
        return (SiteViewRead.decode(json) as SiteRead.Read).value.exportCurtail!!
    }

    private fun text(language: String = "sv") = SiteSettingsText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    @Test fun everyVendoredViewHasTheBlockAndTheListBesideIt() {
        for (name in listOf("get_site_settings_view_success.json", "get_site_settings_view_priority.json", "site_webhook_get_site_settings_view.json")) {
            val block = view(name).exportCurtail!!
            assertFalse(name, block.found)
            assertEquals(name, "no_control", block.state)
            assertEquals(name, 30, block.saving.days)
        }
        assertEquals(7, view("get_site_settings_view_priority.json").priority!!.rows.size)
        assertNull(view("get_site_settings_view_priority.json").exportCurtail!!.saving.majorUnit)
        assertEquals("get_site_settings_view", SiteViewRead.payload().getString("action"))
    }

    @Test fun aHomeAssistantBeforeItHasNoBlockAndAMistypedOneIsNoView() {
        val old = HaFixtures.json("webhook/get_site_settings_view_success.json").apply { remove("export_curtail") }
        assertNull((SiteViewRead.decode(old) as SiteRead.Read).value.exportCurtail)
        val notRunning = HaFixtures.json("webhook/get_site_settings_view_success.json").put("export_curtail", JSONObject.NULL)
        assertNull((SiteViewRead.decode(notRunning) as SiteRead.Read).value.exportCurtail)
        fun broken(edit: JSONObject.() -> Unit) =
            SiteViewRead.decode(HaFixtures.json("webhook/get_site_settings_view_success.json").apply { getJSONObject("export_curtail").apply(edit) })
        assertTrue(broken { put("enabled", "yes") } is SiteRead.Failed)
        assertTrue(broken { remove("saving") } is SiteRead.Failed)
        assertTrue(broken { getJSONObject("saving").put("minor", "8") } is SiteRead.Failed)
        // An unknown key is not looked at.
        assertTrue(broken { put("schedule", "always") } is SiteRead.Read)
        assertEquals(SiteRead.Failed("spotnav_no_site"), SiteViewRead.answer(400, """{"ok": false, "error": "spotnav_no_site"}"""))
    }

    @Test fun whileOffItSaysWhatExportingAtANegativePriceCostAndNothingWhenOn() {
        val off = found { put("saving", JSONObject("""{"days": 30, "kwh": 12.4, "minor": 845.0, "blocked_minor": 0.0, "major_unit": "kr", "minor_unit": "öre"}""")) }
        assertEquals("In the last 30 days, exporting at negative prices cost 8.45 kr (12.4 kWh).", text("en").exportSaving(off))
        assertNull(text().exportSaving(off.copy(enabled = true)))
        assertNull(text().exportSaving(off.copy(saving = off.saving.copy(minor = 0.0))))
        assertTrue(text().exportSaving(off.copy(saving = off.saving.copy(minor = 2400.0)))!!.contains("24 kr"))
        // Without a price area: the minor figure.
        assertTrue(text().exportSaving(off.copy(saving = off.saving.copy(majorUnit = null, minorUnit = "öre")))!!.contains("845 öre"))
    }

    @Test fun theBetaNoteAndTheOneLineThePersonCanActOn() {
        val beta = found { put("beta", true) }
        assertEquals("Still being tested with Sigenergy: for beta testers only so far.", text("en").exportBeta(beta))
        assertNull(text().exportBeta(beta.copy(need = "beta_only")))
        assertNull(text().exportBeta(found()))
        val en = text("en")
        assertNull(en.exportNeed(found()))
        assertEquals(en.exportNeed(found { put("need", "control_disabled") }), CardStringsXml.words("en").say("exportCurtail.need.controlDisabled"))
        assertTrue(en.exportNeed(found { put("need", "needs_hacs_integration:owner/sigenergy-local") })!!.contains("(owner/sigenergy-local)"))
        assertTrue(en.exportNeed(found { put("need", "beta_only") })!!.startsWith("With Sigenergy this works"))
        for (code in listOf("limit_unset", "not_obeyed", "import_rose", "not_taken", "no_grid_reading")) {
            assertTrue(code, en.exportNeed(found { put("need", code) })!!.isNotBlank())
        }
        assertNull(en.exportNeed(found { put("need", "something_new") }))
        assertEquals("Deye / Sunsynk", SiteSettingsText.brandName("deye"))
        assertEquals("Victron", SiteSettingsText.brandName("victron"))
    }

    @Test fun everyLanguageHasEveryWordOfIt() {
        val block = found { put("beta", true); put("need", "needs_hacs_integration:owner/repo"); getJSONObject("saving").put("minor", 845.0) }
        for (language in CardStringsXml.LANGUAGES) {
            val text = text(language)
            val words = CardStringsXml.words(language)
            for (line in listOfNotNull(words.say("exportCurtail.title"), words.say("exportCurtail.help"), text.exportSaving(block), text.exportBeta(block), text.exportNeed(block))) {
                assertFalse("$language: $line", "{" in line || "exportCurtail." in line)
            }
        }
    }
}
