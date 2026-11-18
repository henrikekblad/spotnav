package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.time.LocalDate

/**
 * The house page's documents against the integration's own fixtures: every vendored one is listed here with what it
 * decodes to, so a new or renamed fixture fails until the app handles it; a required key that is missing or mistyped
 * is no document, an unknown key is not looked at.
 */
class HouseDocumentsTest {
    private fun now(name: String): HouseNow = (HouseNowRead.decode(HaFixtures.json("house_now/$name")) as SiteRead.Read).value

    private fun house(name: String): House = (HouseRead.decode(HaFixtures.json("house/$name")) as SiteRead.Read).value

    @Test fun everyLiveFixtureIsListedAndDecodes() {
        // name -> grid kW, house kW, what the site has (sun, battery, car, heat), whether a car is plugged in
        val expected = mapOf(
            "house_alone_now.json" to listOf(1.8, 1.8, "----", null),
            "villa_car.json" to listOf(7.6, 1.7, "sbch", true),
            "villa_export.json" to listOf(-2.6, 1.0, "sbch", false),
            "villa_now.json" to listOf(0.4, 3.1, "sbch", true)
        )
        assertEquals(expected.keys, HaFixtures.files("house_now").map { it.name }.toSet())
        for ((name, row) in expected) {
            val live = now(name)
            val has = listOf('s' to live.has.sun, 'b' to live.has.battery, 'c' to live.has.car, 'h' to live.has.heat)
                .joinToString("") { (letter, there) -> if (there) letter.toString() else "-" }
            assertEquals(name, row, listOf(live.gridKw, live.houseKw, has, live.carConnected))
            assertEquals(name, Instant.parse("2026-11-16T16:20:00Z"), live.at)
        }
        val villa = now("villa_now.json")
        assertEquals(-1.5, villa.batteryKw!!, 0.0)
        assertEquals(54.0, villa.socPct!!, 0.0)
        assertEquals(73.0, villa.carSocPct!!, 0.0)
        assertEquals("power", villa.sunFrom)
        assertEquals("recent", villa.heatFrom)
        assertNull(now("house_alone_now.json").sunKw)
    }

    @Test fun everyHouseFixtureIsListedAndDecodes() {
        // name -> hours, whether it has a battery, the shown day, whether that is today, energy flow nodes
        val expected = mapOf(
            "house_learning.json" to listOf(36, false, "2026-11-13", true, null),
            "villa_battery_sun.json" to listOf(36, true, "2026-11-13", true, 8),
            "villa_past_day.json" to listOf(24, true, "2026-11-12", false, 7)
        )
        assertEquals(expected.keys, HaFixtures.files("house").map { it.name }.toSet())
        for ((name, row) in expected) {
            val decoded = house(name)
            assertEquals(
                name, row,
                listOf(decoded.hours.size, decoded.battery != null, decoded.day!!.date.toString(), decoded.day!!.today, decoded.energyFlow?.nodes?.size)
            )
        }
        val villa = house("villa_battery_sun.json")
        assertEquals("Europe/Stockholm", villa.zone.id)
        assertEquals(House.Money("SEK", "kr", "öre"), villa.money)
        assertEquals("sigenergy", villa.battery!!.brand)
        assertEquals("off", villa.battery!!.control)
        assertEquals(-0.62, villa.battery!!.now!!.powerKw!!, 0.0)
        assertEquals(1089.88, villa.today!!.arbitrage, 0.0)
        assertEquals(1720.5, villa.saving!!.total!!, 0.0)
        assertEquals(LocalDate.parse("2026-11-10"), villa.saving!!.carSince)
        assertEquals(listOf(House.LearningHold("phase_peaks", 13, 0.0)), villa.learning)
        assertEquals(76.89, villa.energyFlow!!.kwh(source = true, role = "grid")!!, 0.0)
        assertEquals(0.12, villa.energyFlow!!.kwh(source = false, role = "grid")!!, 0.0)
        assertEquals("found", villa.heat!!.status)
        assertEquals("learning", villa.sun!!.status)
        assertEquals(2, villa.sun!!.daysLeft)
    }

    @Test fun theWebhookAnswersDecodeAsTheirDocuments() {
        val live = HouseNowRead.answer(200, HaFixtures.json("webhook/get_house_now_success.json").toString())
        assertEquals(4.0, (live as SiteRead.Read).value.gridKw!!, 0.0)
        assertTrue(live.value.has.car)
        val house = HouseRead.answer(200, HaFixtures.json("webhook/get_house_success.json").toString())
        assertEquals("site_entry_a", (house as SiteRead.Read).value.siteId)
        assertNull(house.value.battery)
        // A charger in no site: Home Assistant's own code, for both reads.
        val refused = HaFixtures.json("webhook/get_house_no_site.json").toString()
        assertEquals(SiteRead.Failed("spotnav_no_site"), HouseRead.answer(400, refused))
        assertEquals(SiteRead.Failed("spotnav_no_site"), HouseNowRead.answer(400, refused))
        // A Home Assistant before 3.0 does not know the action.
        assertEquals(SiteRead.Failed("Unsupported action"), HouseNowRead.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        // No answer at all, and one that is no envelope.
        assertEquals(SiteRead.Failed(null), HouseRead.answer(null, null))
        assertEquals(SiteRead.Failed(null), HouseNowRead.answer(200, "[]"))
    }

    @Test fun anotherVersionIsUnsupportedAndNoVersionIsNoDocument() {
        val live = HaFixtures.json("house_now/villa_now.json")
        assertEquals(SiteRead.Unsupported, HouseNowRead.decode(JSONObject(live.toString()).put("api_version", 2)))
        assertEquals(SiteRead.Failed(null), HouseNowRead.decode(JSONObject(live.toString()).apply { remove("api_version") }))
        val house = HaFixtures.json("house/villa_battery_sun.json")
        assertEquals(SiteRead.Unsupported, HouseRead.decode(JSONObject(house.toString()).put("api_version", 2)))
        assertEquals(SiteRead.Failed(null), HouseRead.decode(JSONObject(house.toString()).put("api_version", "1")))
    }

    @Test fun anUnknownKeyIsNotLookedAt() {
        val live = HaFixtures.json("house_now/villa_now.json").put("pool_kw", 1.2)
        live.getJSONObject("has").put("pool", true)
        assertEquals(now("villa_now.json"), (HouseNowRead.decode(live) as SiteRead.Read).value)
        val house = HaFixtures.json("house/villa_battery_sun.json").put("pool", JSONObject().put("kw", 1.2))
        house.getJSONObject("battery").put("warranty_years", 10)
        house.getJSONArray("hours").getJSONObject(0).put("pool_kw", 0.4)
        assertEquals(house("villa_battery_sun.json"), (HouseRead.decode(house) as SiteRead.Read).value)
    }

    @Test fun aRequiredKeyMissingOrMistypedIsNoDocument() {
        for (key in listOf("site_id", "at", "grid_kw", "sun_kw", "sun_from", "battery_kw", "soc_pct", "car_kw", "car_connected", "car_soc_pct", "heat_kw", "heat_from", "house_kw", "has")) {
            val live = HaFixtures.json("house_now/villa_now.json").apply { remove(key) }
            assertEquals("without $key", SiteRead.Failed(null), HouseNowRead.decode(live))
        }
        assertEquals(SiteRead.Failed(null), HouseNowRead.decode(HaFixtures.json("house_now/villa_now.json").put("grid_kw", "0.4")))
        assertEquals(SiteRead.Failed(null), HouseNowRead.decode(HaFixtures.json("house_now/villa_now.json").put("car_connected", 1)))
        assertEquals(SiteRead.Failed(null), HouseNowRead.decode(HaFixtures.json("house_now/villa_now.json").apply { getJSONObject("has").remove("heat") }))
        for (key in listOf("site_id", "time_zone", "generated_at", "money", "hours", "sun", "learning", "battery", "today", "energy_flow", "day", "heat", "saving")) {
            val house = HaFixtures.json("house/villa_battery_sun.json").apply { remove(key) }
            assertEquals("without $key", SiteRead.Failed(null), HouseRead.decode(house))
        }
        fun broken(edit: JSONObject.() -> Unit) = HouseRead.decode(HaFixtures.json("house/villa_battery_sun.json").apply(edit))
        assertEquals(SiteRead.Failed(null), broken { getJSONObject("battery").remove("management") })
        assertEquals(SiteRead.Failed(null), broken { getJSONObject("battery").put("soc_pct", "48") })
        assertEquals(SiteRead.Failed(null), broken { getJSONArray("hours").getJSONObject(3).remove("soc_plan_pct") })
        assertEquals(SiteRead.Failed(null), broken { getJSONArray("hours").getJSONObject(3).put("start", "yesterday") })
        assertEquals(SiteRead.Failed(null), broken { put("hours", org.json.JSONArray()) })
        assertEquals(SiteRead.Failed(null), broken { getJSONObject("today").remove("arbitrage") })
        assertEquals(SiteRead.Failed(null), broken { getJSONObject("saving").put("date", "13 November") })
        assertEquals(SiteRead.Failed(null), broken { getJSONObject("energy_flow").getJSONArray("nodes").getJSONObject(0).put("side", "middle") })
        assertEquals(SiteRead.Failed(null), broken { put("time_zone", "Mars/Olympus") })
    }

    @Test fun aBatteryActionOfANewerHomeAssistantIsDrawnAsNone() {
        val house = HaFixtures.json("house/villa_battery_sun.json")
        house.getJSONArray("hours").getJSONObject(20).put("battery_action", "balance_cells")
        assertNull((HouseRead.decode(house) as SiteRead.Read).value.hours[20].batteryAction)
        assertTrue(house("villa_battery_sun.json").hours.any { it.batteryAction == "export_discharge" })
    }

    @Test fun theRequestsNameTheirActionAndVersionAndNeverASite() {
        val live = HouseNowRead.payload()
        assertEquals(setOf("version", "action", "api_version"), live.keys().asSequence().toSet())
        assertEquals("get_house_now", live.getString("action"))
        assertEquals(1, live.getInt("api_version"))
        assertEquals(setOf("version", "action", "api_version"), HouseRead.payload().keys().asSequence().toSet())
        val past = HouseRead.payload(LocalDate.parse("2026-11-12"))
        assertEquals("get_house", past.getString("action"))
        assertEquals("2026-11-12", past.getString("date"))
    }

    @Test fun theHousePageIsOfferedOnlyWhereTheDashboardSaysSo() {
        // As each vendored dashboard states it: a charger in a site answers for its house, one in none does not.
        val offered = HaFixtures.files("dashboard").associate { file ->
            val stated = DashboardFixtures.json(file.name).getJSONObject("charger").getJSONObject("capabilities")
            val caps = DashboardFixtures.dashboard(file.name).capabilities
            assertEquals(file.name, stated.opt("get_house") == true, caps.getHouse)
            assertEquals(file.name, stated.opt("get_heat") == true, caps.getHeat)
            file.name to caps.getHouse
        }
        assertEquals(true, offered["cheapest_direct_site_admin.json"])
        assertEquals(false, offered["cheapest_no_site.json"])
        fun caps(edit: JSONObject.() -> Unit) =
            DashboardFixtures.dashboard { getJSONObject("charger").getJSONObject("capabilities").apply(edit) }.capabilities
        assertTrue(caps { put("get_house", true) }.getHouse)
        assertFalse(caps { put("get_house", true) }.getHeat)
        assertTrue(caps { put("get_heat", true) }.getHeat)
        // A Home Assistant 2.x has neither key; a value that is not really true is no.
        assertFalse(caps { remove("get_house"); remove("get_heat") }.getHouse)
        assertFalse(caps { put("get_house", "true") }.getHouse)
        assertNotNull(caps { put("get_house", 1) })
        assertFalse(caps { put("get_house", 1) }.getHouse)
    }
}
