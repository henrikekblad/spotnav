package se.sensnology.spotnav.ha.house

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SiteDoor
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.time.ZoneId

/** `get_plan` (3.0.0b3) as the webhook answers it, against the integration's own fixtures. */
class PlanDocumentTest {
    private fun read(name: String, edit: (JSONObject) -> Unit = {}): SiteRead<Plan> =
        PlanRead.answer(200, HaFixtures.json("webhook/$name").also(edit).toString())

    private fun plan(name: String, edit: (JSONObject) -> Unit = {}): Plan = (read(name, edit) as SiteRead.Read).value

    @Test fun theRequestIsTheActionAlone() {
        val body = PlanRead.payload()
        assertEquals("get_plan", body.getString("action"))
        assertEquals(1, body.getInt("version"))
        assertEquals(1, body.getInt("api_version"))
        assertFalse(body.has("reads"))
        assertFalse(body.has("charger_id"))
    }

    @Test fun everyPlanFixtureIsRead() {
        for (name in listOf("get_plan_success.json", "get_plan_does_not_fit.json", "get_plan_takes_what_fits.json")) {
            assertTrue(name, read(name) is SiteRead.Read)
        }
    }

    @Test fun aPlanWithOneCarThatFits() {
        val p = plan("get_plan_success.json")
        assertEquals(ZoneId.of("Europe/Stockholm"), p.zone)
        assertEquals(House.Money("SEK", "kr", "öre"), p.money)
        assertEquals(36, p.hours.size)
        val first = p.hours.first()
        assertEquals(Instant.parse("2026-09-22T07:00:00Z"), first.start)
        assertEquals(363.38, first.price!!, 0.0)
        assertEquals(3.97, first.ceilingKw!!, 0.0)
        assertEquals("power_tariff", first.ceiling)
        assertEquals(Plan.MonthPeak(3.97, Instant.parse("2026-09-22T06:00:00Z")), p.monthPeak)
        assertFalse(p.would)
        assertTrue(p.sunInHouse)
        val car = p.loads.single()
        assertEquals("car", car.kind)
        assertFalse(car.would)
        assertEquals(36, car.kw.size)
        assertNull(car.level)
        assertEquals(1, car.runs.size)
        assertEquals("charge", car.runs[0].what)
        assertEquals(20.78, car.summary["kwh"]!!, 0.0)
        assertFalse("a departure is no figure", "departure" in car.summary)
        assertNull(car.doesNotFit)
    }

    @Test fun theCarsLaneCarriesTheDoesNotFitBlock() {
        val block = plan("get_plan_does_not_fit.json").loads.single().doesNotFit!!
        assertNull(block.chosen)
        assertEquals(listOf("takes_what_fits", "later", "accept_peak"), block.choices.map { it.code })
        val chosen = plan("get_plan_takes_what_fits.json").loads.single().doesNotFit!!
        assertEquals("takes_what_fits", chosen.chosen)
        assertFalse(chosen.choices.any { it.code == "takes_what_fits" })
    }

    @Test fun unknownKeysKindsAndCeilingsAreIgnoredNotRefused() {
        val p = plan("get_plan_success.json") { json ->
            json.put("something_new", JSONObject().put("x", 1))
            json.getJSONObject("summary").put("later_figure", 3)
            json.getJSONArray("hours").getJSONObject(0).put("new_kw", 1).put("ceiling", "grid_contract")
            val car = json.getJSONArray("loads").getJSONObject(0)
            car.put("extra", true)
            car.getJSONArray("runs").getJSONObject(0).put("note", "x")
            json.getJSONArray("loads").put(JSONObject(car.toString()).put("kind", "pool_pump").put("state", "watched_later"))
        }
        assertNull("a ceiling of a later kind is named none", p.hours.first().ceiling)
        assertEquals(3.97, p.hours.first().ceilingKw!!, 0.0)
        assertEquals(listOf("car", "pool_pump"), p.loads.map { it.kind })
        assertFalse(p.loads[1].would)
    }

    @Test fun aBrokenPromiseIsNoPlan() {
        assertTrue(read("get_plan_success.json") { it.remove("hours") } is SiteRead.Failed)
        assertTrue(read("get_plan_success.json") { it.put("hours", JSONArray()) } is SiteRead.Failed)
        assertTrue(read("get_plan_success.json") { it.getJSONArray("loads").getJSONObject(0).put("kw", JSONArray().put(1.0)) } is SiteRead.Failed)
        assertTrue(read("get_plan_success.json") { it.put("time_zone", "Mars/Olympus") } is SiteRead.Failed)
        assertEquals(SiteRead.Unsupported, read("get_plan_success.json") { it.put("api_version", 2) })
    }

    @Test fun refusalsAndAnOlderHomeAssistant() {
        assertEquals(SiteRead.Failed("Unsupported action"), PlanRead.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(SiteRead.Failed("spotnav_no_site"), PlanRead.answer(400, """{"ok": false, "error": "spotnav_no_site", "action": "get_plan"}"""))
        assertEquals(SiteRead.Failed("spotnav_site_unloaded"), PlanRead.answer(503, """{"ok": false, "error": "spotnav_site_unloaded", "action": "get_plan"}"""))
        assertEquals(SiteRead.Failed(null), PlanRead.answer(null, null))
    }

    @Test fun getSiteSaysWhetherThePlanIsServed() {
        val b3 = SiteDoor.getSite(200, HaFixtures.json("webhook/get_site.json").toString(), null) as SiteDoor.GetSite.Read
        assertTrue(b3.site.capabilities.getPlan)
        val older = HaFixtures.json("webhook/get_site.json").apply { getJSONObject("capabilities").remove("get_plan") }
        assertFalse((SiteDoor.getSite(200, older.toString(), null) as SiteDoor.GetSite.Read).site.capabilities.getPlan)
        assertNotNull(b3.site.siteId)
    }
}
