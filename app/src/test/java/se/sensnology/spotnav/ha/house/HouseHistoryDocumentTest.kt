package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import se.sensnology.spotnav.testing.HaFixtures
import java.time.LocalDate

/** The history document against the integration's own fixtures: each one listed, a new or renamed one fails until handled. */
class HouseHistoryDocumentTest {
    private fun history(name: String): HouseHistory = (HouseHistoryRead.decode(HaFixtures.json("house_history/$name")) as SiteRead.Read).value

    @Test fun everyHistoryFixtureIsListedAndDecodes() {
        // name -> period, buckets, buckets with something measured
        val expected = mapOf(
            "house_alone_year.json" to listOf(HistoryKind.YEAR, 12),
            "solar_alone_day.json" to listOf(HistoryKind.DAY, 24),
            "solar_alone_month.json" to listOf(HistoryKind.MONTH, 30),
            "villa_day.json" to listOf(HistoryKind.DAY, 24),
            "villa_month.json" to listOf(HistoryKind.MONTH, 30),
            "villa_year.json" to listOf(HistoryKind.YEAR, 12)
        )
        assertEquals(expected.keys, HaFixtures.files("house_history").map { it.name }.toSet())
        for ((name, row) in expected) {
            val decoded = history(name)
            assertEquals(name, row, listOf(decoded.period, decoded.buckets.size))
        }
    }

    @Test fun theVillaMonthHasTheBatterysEarningsAndWhatTheyAreMadeOf() {
        val month = history("villa_month.json")
        assertEquals(LocalDate.parse("2026-11-01"), month.date)
        assertEquals(House.Money("SEK", "kr", "öre"), month.money)
        val bucket = month.buckets.first { it.key == "2026-11-16" }
        assertEquals(375.0, bucket.arbitrage!!, 0.0)
        assertEquals(HouseHistory.BatteryFlow(5.0, 1500.0, 1125.0, 2.5, 200.0), bucket.batteryFlow)
        assertEquals(150.0, bucket.buy!!, 0.0)
        assertEquals(11.2, bucket.heat!!, 0.0)
        assertEquals(6.0, bucket.solar!!, 0.0)
    }

    @Test fun aSiteWithoutBatteryOrSunHasNullsNotZeros() {
        val year = history("house_alone_year.json")
        val bucket = year.buckets.first { it.periods > 0 }
        assertNull(bucket.arbitrage)
        assertNull(bucket.solar)
        assertNull(bucket.heat)
        assertTrue(year.buckets.any { it.periods == 0 })
    }

    @Test fun theWebhookAnswerDecodesAndAnotherVersionIsUnsupported() {
        val answer = HouseHistoryRead.answer(200, HaFixtures.json("webhook/get_house_history_success.json").toString())
        assertEquals("site_entry_a", (answer as SiteRead.Read).value.siteId)
        assertTrue(answer.value.buckets.isEmpty())
        val refused = HaFixtures.json("webhook/get_house_history_invalid_date.json").toString()
        assertTrue(HouseHistoryRead.answer(400, refused) is SiteRead.Failed)
        val document = HaFixtures.json("house_history/villa_day.json")
        assertEquals(SiteRead.Unsupported, HouseHistoryRead.decode(JSONObject(document.toString()).put("api_version", 2)))
        val payload = HouseHistoryRead.payload(HistoryKind.MONTH, LocalDate.parse("2026-10-01"))
        assertEquals(listOf("get_house_history", "month", "2026-10-01", 1), listOf(payload.get("action"), payload.get("period"), payload.get("date"), payload.get("api_version")))
        assertTrue(!HouseHistoryRead.payload(HistoryKind.DAY).has("date"))
    }

    @Test fun anUnknownKeyIsIgnoredAndARequiredOneMissingIsNoDocument() {
        val document = HaFixtures.json("house_history/villa_month.json").put("pool", 1)
        document.getJSONArray("buckets").getJSONObject(0).put("pool_kwh", 3)
        assertEquals(history("villa_month.json"), (HouseHistoryRead.decode(document) as SiteRead.Read).value)
        for (key in listOf("site_id", "time_zone", "period", "date", "end", "today", "complete", "cost", "buckets")) {
            val broken = JSONObject(HaFixtures.json("house_history/villa_month.json").toString()).apply { remove(key) }
            assertEquals(key, SiteRead.Failed(null), HouseHistoryRead.decode(broken))
        }
        for (key in listOf("key", "house", "car", "solar", "use", "periods")) {
            val broken = JSONObject(HaFixtures.json("house_history/villa_month.json").toString())
            broken.getJSONArray("buckets").getJSONObject(2).remove(key)
            assertEquals(key, SiteRead.Failed(null), HouseHistoryRead.decode(broken))
        }
    }
}
