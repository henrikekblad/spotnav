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

    @Test fun waitingForHistoryNamesTheWeekdayInThePluralOfEachLanguage() {
        fun say(language: String, params: Map<String, Any?>) =
            HaStatusText.line(StatusLine("waiting_for_history", params), format(language), now)
        val sunday = mapOf<String, Any?>("weekday" to 7.0, "percent" to 22.0, "weeks" to 4.0)
        assertEquals("Waiting: Sundays were 22 % cheaper the last 4 weeks.", say("en", sunday))
        assertEquals("Väntar: söndagar har varit 22 % billigare de senaste 4 veckorna.", say("sv", sunday))
        assertEquals("Venter: søndage har været 22 % billigere de seneste 4 uger.", say("da", sunday))
        assertEquals("Venter: søndager har vært 22 % billigere de siste 4 ukene.", say("nb", sunday))
        assertEquals(
            "Odotetaan: sunnuntaisin on ollut 22 % halvempaa viimeisten 4 viikon aikana.", say("fi", sunday)
        )
        assertEquals("Waiting: Mondays were 22 % cheaper the last 4 weeks.", say("en", sunday + ("weekday" to 1.0)))
        assertEquals("Odotetaan: keskiviikkoisin", say("fi", sunday + ("weekday" to 3.0)).substringBefore(" on "))
        // Without all three facts, or with a weekday that is none, only the plain fact:
        val plain = "Waiting for hours that usually cost less, will plan then."
        assertEquals(plain, say("en", emptyMap()))
        assertEquals(plain, say("en", sunday + ("weekday" to 9.0)))
        assertEquals(plain, say("en", sunday - "weeks"))
        assertEquals("Väntar på timmar som brukar vara billigare, planerar då.", say("sv", emptyMap()))
    }

    @Test fun theHoldCodesAreWorded() {
        fun say(code: String, params: Map<String, Any?> = emptyMap()) =
            HaStatusText.line(StatusLine(code, params), format("en"), now)
        assertEquals("Charging waits for the planned start at 10:15.",
            say("held_until_window", mapOf("time" to "2026-09-22T08:15:00+00:00")))
        assertEquals("Charging is scheduled.", say("held_until_window"))
        assertTrue(say("held_by_charger").startsWith("The charger's own schedule or load balancing"))
        assertTrue(say("charger_disabled").contains("enable switch is off"))
        assertEquals("Charging was started outside the plan and is allowed to continue.", say("hold_overridden"))
        assertEquals(
            "Laddningen väntar till planerad start kl. 10:15.",
            HaStatusText.line(
                StatusLine("held_until_window", mapOf("time" to "2026-09-22T08:15:00+00:00")), format("sv"), now
            )
        )
    }

    @Test fun theWaitingForHistoryFixtureIsWordedInEveryLocale() {
        for (language in HaStatusWording.LANGUAGES) {
            val text = HaStatusText.render(status("waiting_for_history"), format(language), now)
            assertTrue("$language: $text", !text.isNullOrBlank() && !text!!.contains('{'))
        }
    }

    private fun measurement(language: String, params: Map<String, Any?>) =
        HaStatusText.line(StatusLine("site_measurement_problem", params), format(language), now)

    @Test fun aDuplicateChargerNamesTheOtherOne() {
        val params = mapOf<String, Any?>("other" to "Garage")
        assertTrue(HaStatusText.line(StatusLine("duplicate_charger", params), format("en"), now)
            .startsWith("Garage and this charger are the same physical charger."))
        assertTrue(HaStatusText.line(StatusLine("duplicate_charger", params), format("sv"), now)
            .startsWith("Garage och den här laddaren är samma fysiska laddare."))
        val none = HaStatusText.line(StatusLine("duplicate_charger", emptyMap()), format("en"), now)
        assertFalse(none, none.contains('{'))
    }

    @Test fun aMeasurementProblemNamesTheMissingAndStalePhases() {
        fun p(vararg pairs: Pair<String, Any?>) = mapOf(*pairs)
        assertEquals("L1 has no value.", measurement("en", p("no_value_phases" to listOf("L1"))))
        assertEquals("L1 and L2 have no value.", measurement("en", p("no_value_phases" to listOf("L1", "L2"))))
        assertEquals("L1, L2, and L3 have no value.",
            measurement("en", p("no_value_phases" to listOf("L1", "L2", "L3"))))
        assertEquals("L2 and L3 have no value (sensor.a, sensor.b).",
            measurement("en", p("no_value_phases" to listOf("L2", "L3"), "no_value_entities" to listOf("sensor.a", "sensor.b"))))
        assertEquals("L1 is older than 60 s.",
            measurement("en", p("stale_phases" to listOf("L1"), "max_age_s" to 60.0)))
        assertEquals("L2 and L3 have no value. L1 is older than 90 s.",
            measurement("en", p("no_value_phases" to listOf("L2", "L3"), "stale_phases" to listOf("L1"), "max_age_s" to 90.0)))
        assertEquals("L2 och L3 saknar värde. L1 är äldre än 90 s.",
            measurement("sv", p("no_value_phases" to listOf("L2", "L3"), "stale_phases" to listOf("L1"), "max_age_s" to 90.0)))
        assertEquals("L1, L2 og L3 har ingen verdi.",
            measurement("nb", p("no_value_phases" to listOf("L1", "L2", "L3"))))
        assertEquals("L1: arvo on yli 60 s vanha.",
            measurement("fi", p("stale_phases" to listOf("L1"), "max_age_s" to 60.0)))
    }

    @Test fun aMalformedMeasurementProblemFallsBackToTheGeneralSentence() {
        val general = "The site's measurement cannot be used right now."
        assertEquals(general, measurement("en", emptyMap()))
        assertEquals(general, measurement("en", mapOf("no_value_phases" to "L1", "stale_phases" to 5.0)))
        assertEquals(general, measurement("en", mapOf("no_value_phases" to listOf(1.0, null), "stale_phases" to emptyList<String>())))
        assertEquals("Anläggningens mätning kan inte användas just nu.", measurement("sv", emptyMap()))
        // A stale phase with no usable age says 0 s, as the card does.
        assertEquals("L1 is older than 0 s.", measurement("en", mapOf("stale_phases" to listOf("L1"), "max_age_s" to "x")))
    }

    @Test fun aWaitingProposalNamesWhenTheCurrentWindowEndsInEveryLanguage() {
        val line = StatusLine("proposal_pending", mapOf("installs_at" to "2026-09-22T14:45:00+00:00", "waits_for" to "window_end"))
        assertEquals(
            "A new plan is ready and is installed when the current charging window ends at 16:45.",
            HaStatusText.line(line, format("en"), now)
        )
        assertEquals(
            "En ny plan väntar och installeras när pågående laddfönster slutar kl. 16:45.",
            HaStatusText.line(line, format("sv"), now)
        )
        // Another day carries the day with the time, as the card does; without an instant it is the plain fact.
        val later = StatusLine("proposal_pending", mapOf("installs_at" to "2026-09-23T14:45:00+00:00"))
        assertTrue(HaStatusText.line(later, format("en"), now).contains("Wed 23 Sep 16:45"))
        assertEquals("A new charging proposal is ready.", HaStatusText.line(StatusLine("proposal_pending", emptyMap()), format("en"), now))
        for (language in HaStatusWording.LANGUAGES) {
            val text = HaStatusText.line(line, format(language), now)
            assertTrue("$language: $text", text.contains("16:45") && !text.contains('{'))
        }
    }

    @Test fun loadBalancingNamesWhoSharesTheFuseAndFallsBackToThePlainSentence() {
        fun text(language: String, cause: Any?) = HaStatusText.line(
            StatusLine("load_balancing_limited", mapOf("limit_a" to 12.0, "phase" to "L1", "cause" to cause)),
            format(language), now
        )
        assertEquals("The home battery charges from the grid and shares the main fuse: the car gets 12 A.", text("en", "battery_shares_fuse"))
        assertEquals("Hushållets förbrukning begränsar bilen till 12 A.", text("sv", "house_consumption"))
        assertEquals("Charging is limited to 12 A by the site's load balancing.", text("en", null))
        assertEquals("Charging is limited to 12 A by the site's load balancing.", text("en", "something_new"))
        for (language in HaStatusWording.LANGUAGES) {
            assertFalse(text(language, "battery_shares_fuse") == text(language, "house_consumption"))
        }
        assertEquals(
            "Charging is limited by the site's load balancing right now.",
            HaStatusText.line(StatusLine("load_balancing_limited", mapOf("cause" to "house_consumption")), format("en"), now)
        )
    }

    @Test fun wordsAnEstimatedRemainingNeedByWhereItWasCounted() {
        val kept = StatusLine("remaining_need_estimated", mapOf("kwh" to 7.25, "basis" to "kept"))
        val sessions = StatusLine("remaining_need_estimated", mapOf("kwh" to 7.25, "basis" to "sessions"))
        assertEquals(
            "The energy meter cannot be read: 7.3 kWh remains, from its last reading.",
            HaStatusText.line(kept, format("en"), now)
        )
        assertEquals(
            "Energimätaren kan inte läsas: 7,3 kWh återstår enligt dess senaste värde.",
            HaStatusText.line(kept, format("sv"), now)
        )
        assertEquals(
            "No energy meter: 7.3 kWh remains, counted from this charger's recorded charges.",
            HaStatusText.line(sessions, format("en"), now)
        )
        assertEquals(
            "Ingen energimätare: 7,3 kWh återstår, räknat från laddarens sparade laddningar.",
            HaStatusText.line(sessions, format("sv"), now)
        )
    }
}
