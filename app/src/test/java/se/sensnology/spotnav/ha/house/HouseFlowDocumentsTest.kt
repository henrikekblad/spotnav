package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures
import java.io.File
import java.time.LocalDate

/**
 * The Energy flow tab's documents against the integration's own fixtures: the history (`get_house_history`) and the
 * pie (`get_house_loads`). Every vendored one is listed with what it decodes to; a required key missing or mistyped is
 * no document, an unknown key is not looked at.
 */
class HouseFlowDocumentsTest {
    private fun history(json: JSONObject): HouseHistory = (HouseHistoryRead.decode(json) as SiteRead.Read).value

    private fun loads(json: JSONObject): HouseLoads = (HouseLoadsRead.decode(json) as SiteRead.Read).value

    @Test fun everyHistoryFixtureIsListedAndDecodes() {
        // name -> period, first day, end, running, energy flow nodes
        val expected = mapOf(
            "house_alone_year.json" to listOf("year", "2026-01-01", "2027-01-01", true, 4),
            "villa_day.json" to listOf("day", "2026-11-15", "2026-11-16", false, 8),
            "villa_month.json" to listOf("month", "2026-11-01", "2026-12-01", true, 8),
            "villa_year.json" to listOf("year", "2026-01-01", "2027-01-01", true, 8)
        )
        assertEquals(expected.keys, HaFixtures.files("house_history").map { it.name }.toSet())
        for ((name, row) in expected) {
            val read = history(HaFixtures.json("house_history/$name"))
            assertEquals(name, row, listOf(read.period.key, read.date.toString(), read.end.toString(), read.running, read.energyFlow?.nodes?.size))
        }
        val month = history(HaFixtures.json("house_history/villa_month.json")).energyFlow!!
        assertEquals(321.8, month.kwh(source = true, role = "grid")!!, 0.0)
        assertEquals(60, month.stepMin)
        // The year of a house with only a grid meter: no heat, no car.
        assertEquals(0, history(HaFixtures.json("house_history/house_alone_year.json")).energyFlow!!.heatSources)
    }

    @Test fun theWebhookTwinOfTheHistoryDecodesAndRefusesAsHomeAssistantDoes() {
        val ok = File(HaFixtures.root, "webhook/get_house_history_success.json").readText()
        val read = (HouseHistoryRead.answer(200, ok) as SiteRead.Read).value
        assertEquals("day", read.period.key)
        // A site with no meter: the flow is null, not missing.
        assertNull(read.energyFlow)
        assertEquals(LocalDate.parse("2026-09-22"), read.date)
        val refused = File(HaFixtures.root, "webhook/get_house_history_invalid_date.json").readText()
        assertEquals(SiteRead.Failed("spotnav_invalid_date"), HouseHistoryRead.answer(400, refused))
        assertEquals(SiteRead.Failed(null), HouseHistoryRead.answer(null, null))
    }

    @Test fun everyLoadsFixtureIsListedAndDecodes() {
        // name -> kind, house kWh, loads, moved, implausible
        val expected = mapOf(
            "heat_villa_day.json" to listOf("heat", 21.4, 4, 0, 0),
            "meters_over_house.json" to listOf("house", 40.0, 2, 0, 0),
            "moved_and_implausible_month.json" to listOf("house", 1030.0, 3, 2, 1),
            "no_devices.json" to listOf("house", 6120.4, 0, 0, 0),
            "villa_day.json" to listOf("house", 14.62, 5, 0, 0)
        )
        assertEquals(expected.keys, HaFixtures.files("house_loads").map { it.name }.toSet())
        for ((name, row) in expected) {
            val read = loads(HaFixtures.json("house_loads/$name"))
            assertEquals(name, row, listOf(read.kind, read.houseKwh, read.loads.size, read.moved.size, read.implausible.size))
        }
        val heat = loads(HaFixtures.json("house_loads/heat_villa_day.json"))
        assertEquals(listOf("heat_pump", "zone", "zone", "meter"), heat.loads.map { it.source })
        assertEquals(listOf(false, false, false, true), heat.loads.map { it.changed })
        assertEquals(3.5, loads(HaFixtures.json("house_loads/meters_over_house.json")).overKwh, 0.0)
        assertEquals(HouseLoads.Implausible("sensor.smart_energy_switch_electric_consumption_kwh", "NAS", 12, false), loads(HaFixtures.json("house_loads/moved_and_implausible_month.json")).implausible.single())
        val webhook = File(HaFixtures.root, "webhook/get_house_loads_success.json").readText()
        assertTrue((HouseLoadsRead.answer(200, webhook) as SiteRead.Read).value.loads.isEmpty())
    }

    @Test fun anotherVersionIsUnsupportedAndNoVersionIsNoDocument() {
        val h = HaFixtures.json("house_history/villa_day.json")
        assertEquals(SiteRead.Unsupported, HouseHistoryRead.decode(JSONObject(h.toString()).put("api_version", 2)))
        assertEquals(SiteRead.Failed(null), HouseHistoryRead.decode(JSONObject(h.toString()).put("api_version", "1")))
        val l = HaFixtures.json("house_loads/villa_day.json")
        assertEquals(SiteRead.Unsupported, HouseLoadsRead.decode(JSONObject(l.toString()).put("api_version", 2)))
        assertEquals(SiteRead.Failed(null), HouseLoadsRead.decode(JSONObject(l.toString()).apply { remove("api_version") }))
    }

    @Test fun anUnknownKeyIsNotLookedAt() {
        val h = HaFixtures.json("house_history/villa_day.json").put("pool", JSONObject().put("kw", 1))
        h.getJSONObject("energy_flow").put("pool_kwh", 3)
        h.getJSONObject("energy_flow").getJSONArray("nodes").getJSONObject(0).put("tint", "yellow")
        assertEquals(history(HaFixtures.json("house_history/villa_day.json")), history(h))
        val l = HaFixtures.json("house_loads/villa_day.json").put("pool", true)
        l.getJSONArray("loads").getJSONObject(0).put("phase", 3)
        assertEquals(loads(HaFixtures.json("house_loads/villa_day.json")), loads(l))
    }

    @Test fun aRequiredKeyMissingOrMistypedIsNoDocument() {
        for (key in listOf("site_id", "period", "date", "end", "today", "energy_flow")) {
            val h = HaFixtures.json("house_history/villa_day.json").apply { remove(key) }
            assertEquals("history without $key", SiteRead.Failed(null), HouseHistoryRead.decode(h))
        }
        assertEquals(SiteRead.Failed(null), HouseHistoryRead.decode(HaFixtures.json("house_history/villa_day.json").put("period", "week")))
        assertEquals(SiteRead.Failed(null), HouseHistoryRead.decode(HaFixtures.json("house_history/villa_day.json").put("date", "15 Nov")))
        assertEquals(SiteRead.Failed(null), HouseHistoryRead.decode(HaFixtures.json("house_history/villa_day.json").apply {
            getJSONObject("energy_flow").getJSONArray("links").getJSONObject(0).put("kwh", -1)
        }))
        for (key in listOf("site_id", "period", "date", "end", "house_kwh", "meters_kwh", "other_kwh", "over_kwh", "loads")) {
            val l = HaFixtures.json("house_loads/villa_day.json").apply { remove(key) }
            assertEquals("loads without $key", SiteRead.Failed(null), HouseLoadsRead.decode(l))
        }
        assertEquals(SiteRead.Failed(null), HouseLoadsRead.decode(HaFixtures.json("house_loads/villa_day.json").put("house_kwh", "14")))
        assertEquals(SiteRead.Failed(null), HouseLoadsRead.decode(HaFixtures.json("house_loads/villa_day.json").apply {
            getJSONArray("loads").getJSONObject(0).remove("name")
        }))
    }

    @Test fun anOlderLoadsAnswerIsTheHousesWithNothingMoved() {
        val l = HaFixtures.json("house_loads/villa_day.json").apply {
            remove("kind"); remove("moved"); remove("belongs"); remove("implausible")
            getJSONArray("loads").getJSONObject(0).apply { remove("source"); remove("changed") }
        }
        val read = loads(l)
        assertEquals("house", read.kind)
        assertTrue(read.moved.isEmpty() && read.implausible.isEmpty())
        assertEquals("meter", read.loads[0].source)
        assertFalse(read.loads[0].changed)
    }

    @Test fun theRequestsNameTheirActionVersionAndPeriodAndNeverASite() {
        val h = HouseHistoryRead.payload(HouseHistory.HistoryKind.MONTH, LocalDate.parse("2026-10-05"))
        assertEquals(setOf("version", "action", "api_version", "period", "date"), h.keys().asSequence().toSet())
        assertEquals("get_house_history", h.getString("action"))
        assertFalse(HouseHistoryRead.payload(HouseHistory.HistoryKind.DAY, null).has("date"))
        val l = HouseLoadsRead.payload("heat", "year", null)
        assertEquals(setOf("version", "action", "api_version", "kind", "period"), l.keys().asSequence().toSet())
        assertEquals("get_house_loads", l.getString("action"))
    }
}
