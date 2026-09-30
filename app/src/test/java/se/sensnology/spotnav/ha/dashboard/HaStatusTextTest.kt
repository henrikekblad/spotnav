package se.sensnology.spotnav.ha.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.time.ZoneId

class HaStatusTextTest {
    private val now = Instant.parse("2026-09-22T06:00:00Z")
    private fun format(language: String) = StatusFormat(language, ZoneId.of("Europe/Stockholm"), "SEK", "kr")
    private fun status(name: String) =
        Dashboard.parse(HaFixtures.json("dashboard/$name.json")).status

    @Test fun everyStatusCodeInTheVendoredCodesFixtureHasAWordingInEveryLocale() {
        val codes = HaFixtures.statusCodes().keys
        assertTrue(codes.size >= 40)
        assertEquals("the wording table covers exactly the codes", codes, HaStatusWording.CODE_KEYS.keys)
        for (language in HaStatusWording.LANGUAGES) {
            for (code in codes) {
                val key = HaStatusWording.CODE_KEYS.getValue(code)
                val text = HaStatusWording.text(language, key)
                assertTrue("$language has no wording for $code ($key)", !text.isNullOrBlank())
            }
            for (key in HaStatusWording.VARIANT_KEYS + HaStatusWording.UNKNOWN_CODE + HaStatusWording.STALE_SUFFIX) {
                assertTrue("$language has no $key", !HaStatusWording.text(language, key).isNullOrBlank())
            }
        }
    }

    @Test fun everyCodeRendersInEveryLocaleWithItsParamsAndWithNone() {
        val fact = mapOf<String, Any?>(
            "start" to "2026-09-22T08:15:00+00:00", "until" to "2026-09-22T09:00:00+00:00",
            "window_start" to "2026-09-22T10:15:00+00:00", "window_end" to "2026-09-22T13:15:00+00:00",
            "publication_at" to "2026-09-22T11:45:00+00:00", "currency" to "SEK",
            "basis" to "estimate", "reason" to "ready", "missing" to listOf("area"), "choice" to "next_period"
        )
        for ((code, spec) in HaFixtures.statusCodes()) {
            val withParams = spec.first.associateWith { fact[it] ?: 12.5 }
            for (language in HaStatusWording.LANGUAGES) {
                for (params in listOf(withParams, emptyMap())) {
                    val text = HaStatusText.line(StatusLine(code, params), format(language), now)
                    assertTrue("$language $code is blank", text.isNotBlank())
                    assertFalse("$language $code has an unfilled slot: $text", text.contains('{'))
                }
            }
        }
    }

    @Test fun wordsAPlannedLineInTheMarketsClockAndTheReadersLanguage() {
        val s = status("target_soc_estimated")
        assertEquals(
            "Planerat från 10:15 · 34,5 kWh · 82,92 kr · 17,3 mil",
            HaStatusText.render(s, format("sv"), now)
        )
        assertEquals(
            "Planned from 10:15 · 34.5 kWh · 82.92 kr · 173 km",
            HaStatusText.render(s, format("en"), now)
        )
        assertEquals(
            "Suunniteltu klo 10:15 alkaen · 34,5 kWh · 82,92 kr · 173 km",
            HaStatusText.render(s, format("fi"), now)
        )
    }

    @Test fun aTargetStoppedOnAnEstimateSaysSoAndHowOldTheReadingIs() {
        val text = HaStatusText.render(status("target_soc_stopped_on_estimate"), format("sv"), now)!!
        assertTrue(text, text.endsWith("Stoppad vid 81 % (uppskattat, avläsningen 30 min gammal)"))
    }

    @Test fun anInstantIsNotWrittenWithoutAZone() {
        val noZone = StatusFormat("en", null, null, null)
        assertEquals("Charging is scheduled · 34.5 kWh · 82.92 SEK · 173 km",
            HaStatusText.render(status("target_soc_estimated"), noZone, now))
    }

    @Test fun waitingAndBuyingAndHybridAreWorded() {
        assertEquals("Väntar på morgondagens priser (~13:45), planerar då.",
            HaStatusText.render(status("waiting_for_publication"), format("sv"), now))
        assertEquals("Köper 6,4 kWh nu, resten när priserna publiceras.",
            HaStatusText.render(status("buying_before_publication"), format("sv"), now))
        assertEquals("Hybrid · 20 kWh from grid 12:15–15:15",
            HaStatusText.render(status("hybrid_derived_site_with_forecast"), format("en"), now))
    }

    @Test fun everyFixtureRendersNonBlankInEveryLocale() {
        for (file in HaFixtures.files("dashboard")) {
            val d = Dashboard.parse(org.json.JSONObject(file.readText()))
            for (language in HaStatusWording.LANGUAGES) {
                val text = HaStatusText.render(d.status, StatusFormat.of(language, d.market), now)
                assertTrue("${file.name} $language", !text.isNullOrBlank())
            }
        }
    }

    @Test fun unfinishedSettingsNameWhatIsMissingAndSuggestedOnesPointToSettings() {
        fun say(code: String, params: Map<String, Any?>) = HaStatusText.line(StatusLine(code, params), format("en"), now)
        assertEquals("Finish setting up: choose a price area in Settings.", say("settings_incomplete", mapOf("missing" to listOf("area"))))
        assertEquals(
            "Finish setting up in Settings: price area, phases.",
            say("settings_incomplete", mapOf("missing" to listOf("area", "phases")))
        )
        assertEquals(
            "Finish setting up: some settings are still missing. Open Settings.",
            say("settings_incomplete", mapOf("missing" to listOf("area", "something_new")))
        )
        assertTrue(say("settings_suggested", mapOf("fields" to listOf("area", "amps"))).endsWith("check Settings."))
        assertEquals(HaStatusWording.text("en", HaStatusWording.UNKNOWN_CODE), say("some_future_code", emptyMap()))
    }

    @Test fun aBlockWithNoLinesHasNothingToSay() {
        assertEquals(null, HaStatusText.render(DashboardStatus(emptyList(), StatusTone.NORMAL), format("en"), now))
    }

    @Test fun blockingAndNoticeTonesComeFromTheBlock() {
        assertEquals(StatusTone.NOTICE, status("solar_derived_site").tone)
        assertEquals(StatusTone.NOTICE, status("charging_without_prices").tone)
        assertEquals(StatusTone.NORMAL, status("target_soc_estimated").tone)
        assertEquals(
            StatusTone.BLOCKING,
            Dashboard.parseStatus(org.json.JSONObject("""{"lines":[{"code":"planning_error","params":{}}],"tone":"blocking"}""")).tone
        )
    }

    private fun plan(vararg extra: StatusLine) = DashboardStatus(
        listOf(
            StatusLine("auto_planned", mapOf("start" to "2026-09-22T04:00:00+00:00")),
            StatusLine("plan_energy", mapOf("kwh" to 22.2)),
            StatusLine("plan_cost", mapOf("amount_minor" to 2765.0, "currency" to "SEK")),
            StatusLine("plan_distance", mapOf("mil" to 11.1)),
        ) + extra,
        StatusTone.NORMAL
    )

    private val suggested = StatusLine("settings_suggested", mapOf("fields" to listOf("area")))

    @Test fun theSuggestionIsALineOfItsOwnAndTheHeadlineStaysOnePlanLine() {
        val parts = HaStatusText.parts(plan(suggested), format("en"), now)
        assertEquals("Planned from 06:00 · 22.2 kWh · 27.65 kr · 111 km", parts.headline)
        assertEquals(listOf("Suggested from your location and charger – check Settings."), parts.notes)
        val sv = HaStatusText.parts(plan(suggested), format("sv"), now)
        assertEquals("Planerat från 06:00 · 22,2 kWh · 27,65 kr · 11,1 mil", sv.headline)
        assertEquals(listOf("Förslag utifrån din plats och laddare – kontrollera Inställningar."), sv.notes)
    }

    @Test fun theWidgetsLineIsTheHeadlineAlone() {
        assertEquals(
            "Planned from 06:00 · 22.2 kWh · 27.65 kr · 111 km",
            HaStatusText.render(plan(suggested), format("en"), now)
        )
        assertEquals(null, HaStatusText.render(DashboardStatus(listOf(suggested), StatusTone.NORMAL), format("en"), now))
    }

    @Test fun numbersFollowTheScreensOwnLocaleWhateverTheLanguageOfTheWords() {
        // English words on a Swedish-region phone: the rows write "11,1", so the status does too.
        val enSe = StatusFormat("en", ZoneId.of("Europe/Stockholm"), "SEK", "kr", java.util.Locale.forLanguageTag("en-SE"))
        assertEquals("Planned from 06:00 · 22,2 kWh · 27,65 kr · 111 km", HaStatusText.render(plan(), enSe, now))
        val enUs = StatusFormat("en", ZoneId.of("Europe/Stockholm"), "SEK", "kr", java.util.Locale.US)
        assertEquals("Planned from 06:00 · 22.2 kWh · 27.65 kr · 111 km", HaStatusText.render(plan(), enUs, now))
    }

    @Test fun theDistanceUnitFollowsTheLanguage() {
        val zone = ZoneId.of("Europe/Stockholm")
        fun line(language: String, locale: java.util.Locale) =
            HaStatusText.line(StatusLine("plan_distance", mapOf("mil" to 15.2)), StatusFormat(language, zone, "SEK", "kr", locale), now)
        assertEquals("15,2 mil", line("sv", java.util.Locale.forLanguageTag("sv")))
        assertEquals("15,2 mil", line("nb", java.util.Locale.forLanguageTag("nb")))
        assertEquals("152 km", line("en", java.util.Locale.forLanguageTag("en-SE")))
        assertEquals("152 km", line("da", java.util.Locale.forLanguageTag("da")))
        assertEquals("152 km", line("fi", java.util.Locale.forLanguageTag("fi")))
    }
}
