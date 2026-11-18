package se.sensnology.spotnav.ha.heat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.HaFixtures
import java.time.LocalDate

/**
 * The heat history against the integration's own fixtures: each one listed (a new or renamed one fails until handled);
 * a required key that is missing or mistyped is no document, a key the app does not know is not looked at.
 */
class HeatHistoryDocumentTest {
    private fun read(json: JSONObject) = HeatHistoryRead.decode(json)

    private fun history(name: String): HeatHistory = (read(HaFixtures.json("heat_history/$name")) as SiteRead.Read).value

    @Test fun everyHistoryFixtureIsListedAndDecodes() {
        // name -> period, buckets, parts, hot-water runs
        val expected = mapOf(
            "meter_only_year.json" to listOf(HistoryKind.YEAR, 12, 2, 0),
            "villa_day.json" to listOf(HistoryKind.DAY, 24, 2, 3),
            "villa_month.json" to listOf(HistoryKind.MONTH, 30, 2, 0),
            "zones_day.json" to listOf(HistoryKind.DAY, 24, 4, 3),
            "zones_month.json" to listOf(HistoryKind.MONTH, 30, 4, 0),
            "zones_year.json" to listOf(HistoryKind.YEAR, 12, 4, 0)
        )
        assertEquals(expected.keys, HaFixtures.files("heat_history").map { it.name }.toSet())
        for ((name, row) in expected) {
            val h = history(name)
            assertEquals(name, row, listOf(h.period, h.buckets.size, h.parts.size, h.runs.size))
        }
    }

    @Test fun theZonesMonthHasItsPartsAndWhatSpotNavSaved() {
        val month = history("zones_month.json")
        assertEquals(LocalDate.parse("2026-11-01"), month.date)
        assertEquals(listOf("pump", "hot_water", "zone:climate.ovanvaning", "other"), month.parts.map { it.key })
        assertEquals(listOf("pump", "hot_water", "zone", "other"), month.parts.map { it.kind })
        assertEquals("Ovanvåning", month.parts[2].name)
        assertNull(month.parts[0].name)
        assertEquals(456.9, month.saving!!.value, 0.0)
        assertEquals(3, month.saving!!.days)
        assertEquals("kr", month.money!!.majorUnit)
        assertTrue(month.buckets.any { it.saving != null })
        assertTrue(month.running)
    }

    @Test fun aDayHasItsIndoorCurveItsReviewAndItsRuns() {
        val day = history("villa_day.json")
        val indoor = day.indoorDay!!
        assertTrue(indoor.points.isNotEmpty())
        assertTrue(indoor.setpoint!!.isNotEmpty())
        assertTrue(indoor.rooms.isNotEmpty())
        assertEquals(3, day.runs.size)
        assertTrue(day.review!!.relay.hours >= 0)
        assertTrue(day.complete)
        assertEquals(listOf("draw", "standby", null).toSet(), day.runs.map { it.cause }.toSet() + null)
    }

    @Test fun aMeterOnlyYearHasNoZonesAndNoRoomsAndMayHaveNoSaving() {
        val year = history("meter_only_year.json")
        assertNull(year.saving)
        assertNull(year.indoorDay)
        assertNull(year.review)
        assertTrue(year.parts.none { it.kind == "zone" })
    }

    @Test fun theWebhookAnswerDecodesAndAnotherVersionIsUnsupported() {
        val answer = HeatHistoryRead.answer(200, HaFixtures.json("webhook/get_heat_history_success.json").toString())
        assertEquals("site_entry_a", (answer as SiteRead.Read).value.siteId)
        assertTrue(answer.value.buckets.isEmpty())
        assertTrue(answer.value.parts.isEmpty())
        assertNull(answer.value.energy)
        val document = HaFixtures.json("heat_history/villa_day.json")
        assertEquals(SiteRead.Unsupported, read(JSONObject(document.toString()).put("api_version", 2)))
        assertTrue(read(JSONObject(document.toString()).also { it.remove("api_version") }) is SiteRead.Failed)
        assertTrue(HeatHistoryRead.answer(null, null) is SiteRead.Failed)
        val payload = HeatHistoryRead.payload(HistoryKind.MONTH, LocalDate.parse("2026-10-01"))
        assertEquals("get_heat_history", payload.getString("action"))
        assertEquals("month", payload.getString("period"))
        assertEquals("2026-10-01", payload.getString("date"))
        assertTrue(!HeatHistoryRead.payload(HistoryKind.DAY).has("date"))
    }

    @Test fun aRequiredKeyMissingOrMistypedIsNoDocument() {
        fun broken(edit: (JSONObject) -> Unit): SiteRead<HeatHistory> = read(HaFixtures.json("heat_history/zones_day.json").also(edit))
        for (key in listOf("site_id", "time_zone", "period", "date", "end", "today", "complete", "buckets", "runs", "energy", "degree_days", "comfort", "shadow")) {
            assertTrue("$key missing", broken { it.remove(key) } is SiteRead.Failed)
        }
        assertTrue(broken { it.put("period", "week") } is SiteRead.Failed)
        assertTrue(broken { it.put("complete", "yes") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONObject("energy").put("total_kwh", "much") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONObject("energy").remove("hours") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONArray("buckets").getJSONObject(0).remove("kwh") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONArray("parts").getJSONObject(0).put("kind", "boiler") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONArray("parts").getJSONObject(0).remove("kwh") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONArray("runs").getJSONObject(0).put("cause", "leak") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONObject("indoor_day").remove("points") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONObject("review").getJSONObject("plan").remove("hours") } is SiteRead.Failed)
        assertTrue(broken { it.getJSONObject("shadow").put("value", JSONObject.NULL) } is SiteRead.Failed)
    }

    @Test fun aKeyTheAppDoesNotKnowIsNotLookedAt() {
        val extra = HaFixtures.json("heat_history/zones_day.json").also {
            it.put("later", JSONObject().put("anything", 1))
            it.getJSONObject("energy").put("later", "x")
            it.getJSONArray("buckets").getJSONObject(0).put("later", 1)
            it.getJSONArray("parts").getJSONObject(0).put("later", 1)
            it.getJSONObject("shadow").put("later", 1)
        }
        assertEquals(history("zones_day.json"), (read(extra) as SiteRead.Read).value)
    }

    @Test fun anOlderIntegrationWithoutThePartsStillDecodes() {
        val old = HaFixtures.json("heat_history/zones_month.json").also {
            it.remove("parts")
            it.remove("saving")
            it.remove("model")
            for (i in 0 until it.getJSONArray("buckets").length()) it.getJSONArray("buckets").getJSONObject(i).apply { remove("parts"); remove("saving"); remove("zones_kwh") }
        }
        val h = (read(old) as SiteRead.Read).value
        assertTrue(h.parts.isEmpty())
        assertNull(h.saving)
        assertNull(h.model)
    }
}
