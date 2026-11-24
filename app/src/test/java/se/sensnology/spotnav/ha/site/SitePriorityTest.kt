package se.sensnology.spotnav.ha.site

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.ui.settings.SitePriorityText

/** The site's priority list as `get_site_settings_view` states it, shown read-only. */
class SitePriorityTest {
    private fun read(view: JSONObject): SitePriority? = (SitePriorityRead.decode(view) as SiteRead.Read).value

    @Test fun theIntegrationsAnswerHasTheChargersRow() {
        val answer = SitePriorityRead.answer(200, HaFixtures.json("webhook/get_site_settings_view_success.json").toString())
        val list = (answer as SiteRead.Read).value!!
        assertEquals(listOf(SitePriority.Row("charger:entry_a", "charger", "entry_a")), list.rows)
        assertEquals("get_site_settings_view", SitePriorityRead.payload().getString("action"))
    }

    @Test fun aRowOfAnUnknownIdIsSkippedAndTheRestKeepTheirOrder() {
        val view = JSONObject(
            """{"priority": {"writable": false, "order": [
                {"id": "spa", "name": null}, {"id": "hot_tub_2", "name": null}, {"id": "charger:g", "name": "Garage"},
                {"id": "battery", "name": null}, {"id": "pool_heat", "name": null}, {"id": "charger:", "name": null}]}}"""
        )
        val list = read(view)!!
        assertEquals(listOf("spa", "charger:g", "battery", "pool_heat"), list.rows.map { it.id })
        val sv = CardStringsXml.words("sv")
        assertEquals(listOf("1. Spa, bokat bad", "2. Garage", "3. Husbatteri", "4. Poolens värmepump"), SitePriorityText.lines(sv, list))
        assertEquals("Överst får energi först och backar sist.", sv.say("site.priority.intro"))
        assertEquals("Charger", SitePriorityText.label(CardStringsXml.words("en"), SitePriority.Row("charger:x", "charger", null)))
    }

    @Test fun anOlderViewHasNoListAndABrokenOneIsNoRead() {
        assertNull(read(JSONObject("""{"ok": true}""")))
        assertNull(read(JSONObject("""{"priority": null}""")))
        assertTrue(SitePriorityRead.decode(JSONObject("""{"priority": {"order": [{"id": 3}], "writable": false}}""")) is SiteRead.Failed)
        assertTrue(SitePriorityRead.answer(400, """{"ok": false, "error": "spotnav_no_site"}""") is SiteRead.Failed)
    }
}
