package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

/** "Release the import target for this charge": beside the at-risk line where the plan asks, and only where Home Assistant answers it. */
class ImportTargetReleaseTest {
    private fun dashboard(releasable: Any?, capability: Boolean = true): Dashboard {
        val json = HaFixtures.json("webhook/dashboard.json")
        json.getJSONObject("charger").getJSONObject("capabilities").put("release_import_target", capability)
        val params = JSONObject().put("departure", "2026-09-23T05:00:00+00:00").put("missing_kwh", 4.0).put("target_kw", 6.0)
        if (releasable != null) params.put("releasable", releasable)
        json.getJSONObject("status").getJSONArray("lines").put(JSONObject().put("code", "import_target_at_risk").put("params", params))
        return Dashboard.parse(json)
    }

    @Test fun offeredBesideTheAtRiskLineWhereThePlanAsks() {
        assertEquals("Släpp effektmålet för den här laddningen", ImportTargetRelease.label(dashboard(true), "sv"))
        assertEquals("Release the import target for this charge", ImportTargetRelease.label(dashboard(null), "en"))
    }

    @Test fun notOfferedWhereThePlanHoldsTheTargetOrHomeAssistantCannot() {
        assertNull(ImportTargetRelease.label(dashboard(false), "sv"))
        assertNull(ImportTargetRelease.label(dashboard(true, capability = false), "sv"))
        assertNull(ImportTargetRelease.label(Dashboard.parse(HaFixtures.json("webhook/dashboard.json")), "sv"))
    }
}

/** A person's Start obeys the import target: the release beside the held and paused lines, confirmed first. */
class ImportTargetReleaseConfirmTest {
    private val now = java.time.Instant.parse("2026-09-22T18:10:00Z")
    private val format = StatusFormat("sv", java.time.ZoneId.of("Europe/Stockholm"), "SEK", "kr")

    private fun dashboard(code: String, params: JSONObject): Dashboard {
        val json = HaFixtures.json("webhook/dashboard.json")
        json.getJSONObject("status").getJSONArray("lines").put(JSONObject().put("code", code).put("params", params))
        return Dashboard.parse(json)
    }

    private fun held(releasable: Boolean, until: Any?) = JSONObject().put("limit_a", 9).put("target_kw", 6.0).put("hour_kw", 4.2)
        .put("set_kw", 6.0).put("releasable", releasable).put("release_until", until ?: JSONObject.NULL).put("release_peak_kw", 11.0)

    @Test fun offeredUnderAHeldOrPausedLineThatIsReleasable() {
        assertEquals("Släpp effektmålet för den här laddningen", ImportTargetRelease.label(dashboard("import_target_held", held(true, null)), "sv"))
        assertNull(ImportTargetRelease.label(dashboard("import_target_held", held(false, null)), "sv"))
        assertNull(ImportTargetRelease.label(dashboard("import_target_paused", JSONObject().put("target_kw", 6.0)), "sv"))
    }

    @Test fun theConfirmationSaysUntilWhenAndHowHighTheMonthsPeakMayGo() {
        assertEquals(
            "Bilen laddar då med full effekt tills den kopplas ur eller laddningen är klar, senast kl. 12. Månadens topp kan bli upp till 11 kW, mot 6,0 kW i dag.",
            ImportTargetRelease.confirmText(dashboard("import_target_held", held(true, null)), format, now)
        )
        assertEquals(
            "Bilen laddar då med full effekt tills den kopplas ur (eller till avresan 07:00). Månadens topp kan bli upp till 11 kW, mot 6,0 kW i dag.",
            ImportTargetRelease.confirmText(dashboard("import_target_paused", held(true, "2026-09-23T05:00:00+00:00")), format, now)
        )
    }

    @Test fun aReleaseWithoutADepartureLastsUntilTheUnplug() {
        val line = StatusLine("import_target_released", mapOf("target_kw" to 6.0, "departure" to null, "automatic" to false))
        assertEquals(
            "Effektmålet är släppt av dig tills bilen kopplas ur eller laddningen är klar, senast kl. 12.",
            HaStatusText.line(line, format, now)
        )
        // Without the key at all (an older Home Assistant): the plain sentence.
        assertEquals("Effektmålet är släppt av dig till avresan.", HaStatusText.line(StatusLine("import_target_released", mapOf("target_kw" to 6.0)), format, now))
    }

    private fun withLevel(used: Int, next: String?, peak: Double = 11.0, until: Any? = null): Dashboard {
        val json = HaFixtures.json("webhook/dashboard.json")
        json.getJSONObject("status").getJSONArray("lines").put(
            JSONObject().put("code", "import_target_held").put("params", held(true, until).put("release_peak_kw", peak))
        )
        val demand = HaFixtures.json("grid_tariff/pilot_demand.json").getJSONObject("demand")
            .put("power_level", JSONObject().put("level_kw", 10.0).put("window", JSONObject.NULL).put("outside", false))
            .put("level_count", JSONObject().put("used", used).put("allowed", 3).put("exceeded", org.json.JSONArray()).put("counts_now", true).put("next_counted_start", next ?: JSONObject.NULL))
        json.put("grid_tariff", JSONObject("""{"entry_id": "p", "write_revision": 1,
            "transfer_percent": {"policy": "off", "explicit_value": null, "effective_value": null, "unit": "%"},
            "transfer_high": {"policy": "off", "explicit_value": null, "effective_value": null, "unit": "öre/kWh"},
            "high_load_window": null, "writable": true}""").put("demand", demand))
        return Dashboard.parse(json)
    }

    @Test fun aReleaseAboveThePowerLevelSaysWhichExceedanceItWouldBe() {
        val confirm = ImportTargetRelease.confirm(withLevel(1, "2026-09-22T19:00:00+00:00"), format, now)!!
        assertTrue(confirm.text, confirm.text.endsWith("Det blir överskridande 2 av 3 av effektnivån (10,0 kW) på 12 månader."))
        assertFalse(confirm.askTwice)
        assertEquals(confirm.text, ImportTargetRelease.confirmText(withLevel(1, "2026-09-22T19:00:00+00:00"), format, now))
    }

    @Test fun theLastAllowedExceedanceAsksTwiceAndSoDoesOneBeyondIt() {
        val last = ImportTargetRelease.confirm(withLevel(2, "2026-09-22T19:00:00+00:00"), format, now)!!
        assertTrue(last.text, last.text.contains("överskridande 3 av 3"))
        assertTrue(last.text, last.text.endsWith("Det är det sista som tillåts: ett till flyttar dig till en annan tariff."))
        assertTrue(last.askTwice)
        assertTrue(ImportTargetRelease.confirm(withLevel(3, "2026-09-22T19:00:00+00:00"), format, now)!!.askTwice)
    }

    @Test fun noLevelSentenceWhenThePeakStaysUnderItTheLevelDoesNotHoldBeforeTheReleaseEndsOrNothingIsCounted() {
        val plain = ImportTargetRelease.confirmText(dashboard("import_target_held", held(true, null)), format, now)!!
        // The peak at or under the level: the plain sentences.
        assertEquals(false, ImportTargetRelease.confirm(withLevel(2, "2026-09-22T19:00:00+00:00", peak = 10.0), format, now)!!.text.contains("överskridande"))
        // The level next holds after the release has ended (the departure at 07:00 on the 23rd).
        assertEquals(false, ImportTargetRelease.confirm(withLevel(2, "2026-09-23T05:00:00+00:00", until = "2026-09-23T05:00:00+00:00"), format, now)!!.askTwice)
        // No next hour that counts: no sentence either.
        assertEquals(false, ImportTargetRelease.confirm(withLevel(2, null), format, now)!!.text.contains("överskridande"))
        assertFalse(plain.contains("överskridande"))
    }

    @Test fun theLevelSentencesAreInEveryLanguage() {
        for (language in listOf("en", "sv", "da", "nb", "fi", "de", "nl", "es", "fr")) {
            val fmt = StatusFormat(language, java.time.ZoneId.of("Europe/Stockholm"), "SEK", "kr")
            val text = ImportTargetRelease.confirm(withLevel(2, "2026-09-22T19:00:00+00:00"), fmt, now)!!.text
            assertFalse(language, text.contains("release.confirm") || text.contains("{"))
        }
    }
}
