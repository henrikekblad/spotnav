package se.sensnology.spotnav.ha.heat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.HaFixtures
import java.io.File
import java.time.Instant

/**
 * The heat page's documents against the integration's own fixtures: every vendored `get_heat` answer is listed with
 * what it decodes to, so a new or renamed fixture fails until the app handles it; a required key that is missing or
 * mistyped is no document, an unknown key is not looked at. The writes' answers are the webhook's.
 */
class HeatDocumentsTest {
    private fun heat(json: JSONObject): Heat = (HeatRead.decode(json) as SiteRead.Read).value

    private fun fixture(name: String): Heat = heat(HaFixtures.json("heat_card/$name"))

    @Test fun everyHeatFixtureIsListedAndDecodes() {
        // name -> target, mode, shadow, measured, pump, meter, zones, something to show, hours ahead, notes
        val expected = mapOf(
            "controlled.json" to listOf(21.0, "home", false, 20.6, false, true, 4, true, 7, 0),
            "network_pump.json" to listOf(20.5, "away", true, null, true, false, 0, true, 7, 3),
            "sg_api_pump.json" to listOf(21.0, "home", true, null, true, false, 0, true, 7, 0),
            "villa.json" to listOf(21.0, "home", true, 20.6, false, true, 4, true, 7, 2)
        )
        assertEquals(expected.keys, HaFixtures.files("heat_card").map { it.name }.toSet())
        for ((name, row) in expected) {
            val h = fixture(name)
            assertEquals(name, row, listOf(h.config.targetC, h.config.mode, h.shadow, h.indoor.valueC, h.hasPump, h.hasMeter, h.zones, h.hasSomething, h.ahead.size, h.notes.size))
            assertEquals(name, "Europe/Stockholm", h.zone.id)
        }
        val villa = fixture("villa.json")
        assertEquals(1.13, villa.status.kw!!, 0.0)
        assertEquals("lower", villa.status.would!!.action)
        assertEquals(Instant.parse("2026-11-16T18:00:00Z"), villa.status.would!!.until)
        assertEquals(2199.3, villa.today!!.cost!!, 0.0)
        assertEquals("kr", villa.today!!.money!!.majorUnit)
        assertEquals(3.6, villa.today!!.zonesKwh!!, 0.0)
        assertEquals(3, villa.indoor.sensorsRead)
        assertEquals(6.2, villa.outdoorC!!, 0.0)
        assertEquals(true, villa.relay!!.closed)
        assertEquals(false, fixture("controlled.json").shadow)
        assertTrue(fixture("controlled.json").control!!.lowered)
        val away = fixture("network_pump.json")
        assertEquals("2026-11-20T15:00:00+00:00", away.config.awayUntilRaw)
        assertEquals(Instant.parse("2026-11-20T15:00:00Z"), away.comfort.awayUntil)
    }

    @Test fun theWebhookAnswerIsTheSameDocumentWithItsEnvelope() {
        val h = heat(HaFixtures.json("webhook/get_heat_success.json"))
        assertEquals("site_entry_a", h.siteId)
        assertEquals(21.0, h.comfort.targetC, 0.0)
        assertEquals(12, h.ahead.size)
        assertEquals(2, h.indoor.sensorsRead)
        // Two indoor sensors are something to show.
        assertTrue(h.hasSomething)
        val body = HaFixtures.json("webhook/get_heat_success.json").toString()
        assertEquals(h, (HeatRead.answer(200, body) as SiteRead.Read).value)
    }

    @Test fun aSiteWithNothingToShowHasNoHeatPage() {
        val json = HaFixtures.json("heat_card/network_pump.json")
        json.put("pump", JSONObject.NULL).put("meter", JSONObject.NULL).put("zones", org.json.JSONArray())
        assertFalse(heat(json).hasSomething)
        // A meter that is not found is something to set up, not to show.
        val unfound = HaFixtures.json("heat_card/villa.json")
        unfound.getJSONObject("meter").put("status", "unusable")
        unfound.put("zones", org.json.JSONArray()).getJSONObject("indoor").put("sensors", org.json.JSONArray()).put("value_c", JSONObject.NULL)
        assertFalse(heat(unfound).hasSomething)
    }

    @Test fun aRequiredKeyMissingOrMistypedIsNoDocument() {
        for (key in listOf("site_id", "now", "config", "comfort", "status", "ahead", "band", "notes", "shadow", "time_zone")) {
            val json = HaFixtures.json("heat_card/villa.json")
            json.remove(key)
            assertEquals(key, SiteRead.Failed(null), HeatRead.decode(json))
        }
        val retyped = HaFixtures.json("heat_card/villa.json")
        retyped.getJSONObject("comfort").put("target_c", "21")
        assertEquals(SiteRead.Failed(null), HeatRead.decode(retyped))
        val badHour = HaFixtures.json("heat_card/villa.json")
        badHour.getJSONArray("ahead").getJSONObject(0).put("start", "yesterday")
        assertEquals(SiteRead.Failed(null), HeatRead.decode(badHour))
    }

    @Test fun anUnknownKeyIsNotLookedAt() {
        val json = HaFixtures.json("heat_card/villa.json")
        json.put("brand_new", JSONObject().put("x", 1))
        json.getJSONObject("status").put("later", true)
        assertEquals(fixture("villa.json"), heat(json))
    }

    @Test fun anotherVersionIsUnsupported() {
        val json = HaFixtures.json("heat_card/villa.json").put("api_version", 2)
        assertEquals(SiteRead.Unsupported, HeatRead.decode(json))
        assertEquals(SiteRead.Failed(null), HeatRead.decode(HaFixtures.json("heat_card/villa.json").apply { remove("api_version") }))
        assertEquals(SiteRead.Failed("spotnav_no_site"), HeatRead.answer(400, """{"ok": false, "error": "spotnav_no_site", "action": "get_heat"}"""))
        assertEquals(SiteRead.Failed(null), HeatRead.answer(null, null))
    }

    @Test fun theReadAsksForTheHeat() {
        val payload = HeatRead.payload()
        assertEquals("get_heat", payload.getString("action"))
        assertEquals(1, payload.getInt("api_version"))
    }

    private fun write(name: String): HeatWrite.Outcome {
        val body = File(HaFixtures.root, "webhook/$name").readText()
        return HeatWrite.answer(200, body)
    }

    @Test fun everyUpdateHeatAnswerIsListedAndRead() {
        val expected = mapOf(
            "update_heat_success.json" to HeatWrite.Outcome.Written,
            "update_heat_away_delta.json" to HeatWrite.Outcome.Written,
            "update_heat_away.json" to HeatWrite.Outcome.Written,
            "update_heat_night.json" to HeatWrite.Outcome.Written,
            "update_heat_zone_offsets.json" to HeatWrite.Outcome.Written,
            "update_heat_indoor_offsets.json" to HeatWrite.Outcome.Written,
            "update_heat_conflict.json" to HeatWrite.Outcome.Conflict,
            "update_heat_invalid_value.json" to HeatWrite.Outcome.Refused(listOf("out_of_range")),
            "update_heat_not_permitted.json" to HeatWrite.Outcome.NotPermitted,
            "update_heat_zones_not_permitted.json" to HeatWrite.Outcome.NotPermitted,
            "update_heat_indoor_offsets_not_permitted.json" to HeatWrite.Outcome.NotPermitted
        )
        assertEquals(expected.keys, HaFixtures.files("webhook").map { it.name }.filter { it.startsWith("update_heat_") }.toSet())
        for ((name, outcome) in expected) assertEquals(name, outcome, write(name))
        assertEquals(HeatWrite.Outcome.Failed(null), HeatWrite.answer(null, null))
        assertEquals(HeatWrite.Outcome.Failed(null), HeatWrite.answer(200, "[]"))
        assertEquals(
            HeatWrite.Outcome.Unsupported,
            HeatWrite.answer(400, """{"ok": false, "error": "spotnav_unsupported_api_version", "action": "update_heat"}""")
        )
    }

    @Test fun theDialIsWrittenAgainstWhatWasLastRead() {
        val body = HeatWrite.payload(HeatWrite.Change.Target(21.5, 21.0))
        assertEquals("update_heat", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals(21.5, body.getJSONObject("changes").getDouble("target_c"), 0.0)
        assertEquals(21.0, body.getJSONObject("expected").getDouble("target_c"), 0.0)
        // Nothing but the dial is named.
        assertEquals(setOf("target_c"), body.getJSONObject("changes").keys().asSequence().toSet())
    }

    @Test fun awayNamesItsReturnAndHomeClearsIt() {
        val away = HeatWrite.payload(HeatWrite.Change.Mode("away", Instant.parse("2026-11-20T15:00:00.123Z"), "home", null))
        assertEquals("away", away.getJSONObject("changes").getString("mode"))
        assertEquals("2026-11-20T15:00:00Z", away.getJSONObject("changes").getString("away_until"))
        assertEquals("home", away.getJSONObject("expected").getString("mode"))
        assertTrue(away.getJSONObject("expected").isNull("away_until"))
        val home = HeatWrite.payload(HeatWrite.Change.Mode("home", null, "away", "2026-11-20T15:00:00+00:00"))
        assertEquals(setOf("mode"), home.getJSONObject("changes").keys().asSequence().toSet())
        assertEquals("2026-11-20T15:00:00+00:00", home.getJSONObject("expected").getString("away_until"))
        assertNull(home.getJSONObject("changes").opt("away_until"))
    }

    @Test fun theHotWaterIsRead() {
        val water = fixture("villa.json").hotWater!!
        assertEquals("running", water.boost!!.state)
        assertEquals("user", water.boost!!.source)
        assertTrue(water.boost!!.endsEstimated)
        assertFalse(water.boost!!.stoppable)
        assertEquals(listOf("05:00", "17:00"), water.peaks.map { it.readyBy })
        assertEquals(3, water.planned.size)
        assertEquals(Instant.parse("2026-11-16T04:10:00Z"), water.planned[0].pumpRun)
        assertEquals(75.0, water.planned[0].price, 0.0)
        assertEquals(listOf("pump", "button"), water.runsToday.map { it.source })
        assertEquals(2, water.review!!.perPeak.size)
        assertEquals(1, water.review!!.coldRisks)
        assertEquals("B", water.level)
        assertEquals("shadow", water.mode)
        assertEquals(7, water.gate.daysNeeded)
        assertEquals(14, water.blocking!!.gate.daysNeeded)
        assertEquals(listOf("peak", "peak"), water.blocking!!.cuts.map { it.reason })
        val idle = fixture("network_pump.json").hotWater!!
        assertEquals("idle", idle.boost!!.state)
        assertEquals("watch", idle.mode)
        assertNull(idle.blocking)
        assertNull(fixture("sg_api_pump.json").hotWater)
        assertNull(heat(HaFixtures.json("webhook/get_heat_success.json")).hotWater!!.boost)
    }

    @Test fun theHotWaterIsAdditiveAndStrict() {
        // An older Home Assistant has no hot water: still a document.
        val old = HaFixtures.json("heat_card/villa.json").apply { remove("hot_water") }
        assertNull(heat(old).hotWater)
        // A required key missing or mistyped is no document.
        val bad = HaFixtures.json("heat_card/villa.json")
        bad.getJSONObject("hot_water").getJSONObject("boost").put("stoppable", "no")
        assertTrue(HeatRead.decode(bad) is SiteRead.Failed)
        val noGate = HaFixtures.json("heat_card/villa.json")
        noGate.getJSONObject("hot_water").remove("gate")
        assertTrue(HeatRead.decode(noGate) is SiteRead.Failed)
        val badPeak = HaFixtures.json("heat_card/villa.json")
        badPeak.getJSONObject("hot_water").getJSONArray("peaks").getJSONObject(0).put("ready_by", "5")
        assertTrue(HeatRead.decode(badPeak) is SiteRead.Failed)
    }

    @Test fun theBoostButtonsWriteAndItsAnswers() {
        val payload = HeatBoostWrite.payload("start")
        assertEquals("heat_boost", payload.getString("action"))
        assertEquals("start", payload.getString("boost_action"))
        assertEquals(1, payload.getInt("api_version"))
        val running = """{"api_version":1,"ok":true,"error":null,"action":"heat_boost","boost":{"state":"starting","source":"user","started":"2026-11-16T16:20:00+00:00","ends":null,"ends_estimated":false,"stoppable":false}}"""
        val done = HeatBoostWrite.answer(200, running) as HeatBoostWrite.Outcome.Done
        assertEquals("starting", done.boost!!.state)
        assertEquals(HeatBoostWrite.Outcome.Done(null), HeatBoostWrite.answer(200, """{"api_version":1,"ok":true,"error":null,"boost":null}"""))
        // 429 and 409 carry the refusal's envelope.
        assertEquals(HeatBoostWrite.Outcome.Refused("spotnav_rate_limited"), HeatBoostWrite.answer(429, """{"api_version":1,"ok":false,"error":"spotnav_rate_limited","boost":null,"action":"heat_boost"}"""))
        assertEquals(HeatBoostWrite.Outcome.Refused("spotnav_no_headroom"), HeatBoostWrite.answer(409, """{"api_version":1,"ok":false,"error":"spotnav_no_headroom","boost":null}"""))
        assertEquals(HeatBoostWrite.Outcome.Unsupported, HeatBoostWrite.answer(400, """{"ok":false,"error":"spotnav_unsupported_api_version","action":"heat_boost"}"""))
        assertEquals(HeatBoostWrite.Outcome.Failed(null), HeatBoostWrite.answer(null, null))
        assertEquals(HeatBoostWrite.Outcome.Failed(null), HeatBoostWrite.answer(200, "not json"))
        assertEquals(HeatBoostWrite.Outcome.Failed(null), HeatBoostWrite.answer(200, """{"api_version":1,"ok":true,"error":null,"boost":{"state":"idle"}}"""))
    }
}
