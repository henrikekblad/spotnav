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
            "basis" to "estimate", "reason" to "ready", "missing" to listOf("area"), "choice" to "next_period",
            "stopped_at" to "2026-09-22T05:31:00+00:00"
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
            "Planerat från 09:15 · 34,5 kWh · 62,55 kr · 17,3 mil",
            HaStatusText.render(s, format("sv"), now)
        )
        assertEquals(
            "Planned from 09:15 · 34.5 kWh · 62.55 kr · 173 km",
            HaStatusText.render(s, format("en"), now)
        )
        assertEquals(
            "Suunniteltu klo 09:15 alkaen · 34,5 kWh · 62,55 kr · 173 km",
            HaStatusText.render(s, format("fi"), now)
        )
    }

    @Test fun aTargetStoppedOnAnEstimateSaysSoAndWhenItStopped() {
        val text = HaStatusText.render(status("target_soc_stopped_on_estimate"), format("sv"), now)!!
        assertTrue(text, text.endsWith("Stoppad vid 83 % kl. 08:40 (uppskattat)"))
    }

    @Test fun aTargetStopIsToldWithItsTimeNeverAsJustNowHoursLater() {
        // The field case: stopped at 05:31Z on a fresh reading (age 0 then), read three hours later.
        val later = Instant.parse("2026-10-10T08:33:00Z")
        fun worded(language: String, basis: String, at: String?, atNow: Instant = later) = HaStatusText.line(
            StatusLine(
                "target_reached",
                mapOf("soc_percent" to 53.0, "basis" to basis, "reading_age_s" to 0.0, "stopped_at" to at)
            ),
            format(language),
            atNow
        )
        val stop = "2026-10-10T05:31:00+00:00"
        assertEquals("Stoppad vid 53 % kl. 07:31", worded("sv", "reading", stop))
        assertEquals("Stoppad vid 53 % kl. 07:31 (uppskattat)", worded("sv", "estimate", stop))
        assertEquals("Stopped at 53 % at 07:31", worded("en", "reading", stop))
        // A stop on an earlier day names the day.
        val nextDay = worded("en", "reading", stop, Instant.parse("2026-10-11T08:33:00Z"))
        assertTrue(nextDay, nextDay.startsWith("Stopped at 53 % at ") && nextDay.endsWith(" 07:31") && nextDay.length > 24)
        // A Home Assistant without the time: the reading's age, as before.
        assertEquals("Stoppad vid 53 % (nyss)", worded("sv", "reading", null))
        for (language in HaStatusWording.LANGUAGES) {
            val text = worded(language, "reading", stop)
            assertTrue("$language: $text", text.contains("07:31") && !text.contains('{'))
        }
    }

    @Test fun theWaitForTheCarsLevelAfterItEndedTheChargeSaysTheLevelAndHowItIsKnown() {
        fun worded(language: String, params: Map<String, Any?>) =
            HaStatusText.line(StatusLine("car_ended_waiting", params), format(language), now)
        assertEquals(
            "Bilen avslutade laddningen vid ~86 % (uppskattat), väntar på bilens nivå",
            worded("sv", mapOf("soc_percent" to 86.4, "estimated" to true))
        )
        assertEquals(
            "Bilen avslutade laddningen vid 86 %, väntar på bilens nivå",
            worded("sv", mapOf("soc_percent" to 86.0, "estimated" to false))
        )
        assertEquals(
            "The car ended the charge; waiting for the car's level",
            worded("en", mapOf("soc_percent" to null, "estimated" to false))
        )
    }

    @Test fun anInstantIsNotWrittenWithoutAZone() {
        val noZone = StatusFormat("en", null, null, null)
        assertEquals("Charging is scheduled · 34.5 kWh · 62.55 SEK · 173 km",
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

    @Test fun aOnePhaseSolarChargeIsWordedWithItsPowerAsTheCardWordsIt() {
        fun say(language: String, params: Map<String, Any?>) =
            HaStatusText.line(StatusLine("solar_charging_one_phase", params), format(language), now)
        assertEquals("Solar · 1 phase · 2.9 kW", say("en", mapOf("requested_a" to 12.5)))
        assertEquals("Sol · 1 fas · 2,9 kW", say("sv", mapOf("requested_a" to 12.5)))
        assertEquals("Solar · 1 Phase · 1,4 kW", say("de", mapOf("requested_a" to 6.0)))
        // No current, or none above zero: only that it charges on one phase.
        assertEquals("Solar · charging on 1 phase", say("en", emptyMap()))
        assertEquals("Sol · laddar på 1 fas", say("sv", mapOf("requested_a" to 0.0)))
    }

    @Test fun waitingForHistoryIsWaitingForTheWeekdaysPricesInEachLanguage() {
        fun say(language: String, params: Map<String, Any?>) =
            HaStatusText.line(StatusLine("waiting_for_history", params), format(language), now)
        val sunday = mapOf<String, Any?>("weekday" to 7.0, "percent" to 30.0, "weeks" to 4.0)
        assertEquals("Waiting for Sunday's prices, which have been 30 % cheaper over the last 4 weeks.", say("en", sunday))
        assertEquals("Väntar på priserna för söndag, som de senaste 4 veckorna varit 30 % billigare.", say("sv", sunday))
        assertEquals("Venter på prisene for søndag, som de siste 4 ukene har vært 30 % billigere.", say("nb", sunday))
        assertEquals("Venter på priserne for søndag, som de seneste 4 uger har været 30 % billigere.", say("da", sunday))
        assertEquals("Odotetaan sunnuntain hintoja, jotka ovat olleet viimeiset 4 viikkoa 30 % halvempia.", say("fi", sunday))
        assertEquals("Waiting for Monday's prices, which have been 30 % cheaper over the last 4 weeks.", say("en", sunday + ("weekday" to 1.0)))
        // Without all three facts, or with a weekday that is none, only the plain fact:
        val plain = "Waiting for hours that usually cost less, will plan then."
        assertEquals(plain, say("en", emptyMap()))
        assertEquals(plain, say("en", sunday + ("weekday" to 9.0)))
        assertEquals(plain, say("en", sunday - "weeks"))
        assertEquals("Väntar på timmar som brukar vara billigare, planerar då.", say("sv", emptyMap()))
    }

    @Test fun waitingForHistorySaysWhenThePricesComeWhileThatIsAhead() {
        val thursday = StatusLine("waiting_for_history", mapOf("weekday" to 4.0, "percent" to 41.0, "weeks" to 4.0))
        fun say(language: String, publicationAt: String?) =
            HaStatusText.line(thursday, format(language), now, publicationAt)
        val ahead = "2026-09-22T11:00:00+00:00"
        assertEquals("Väntar på priserna för torsdag (kl. 13:00), som de senaste 4 veckorna varit 41 % billigare.", say("sv", ahead))
        assertEquals("Waiting for Thursday's prices (at 13:00), which have been 41 % cheaper over the last 4 weeks.", say("en", ahead))
        for (without in listOf(null, "2026-09-22T05:59:00+00:00", "not a time")) {
            assertEquals("Waiting for Thursday's prices, which have been 41 % cheaper over the last 4 weeks.", say("en", without))
        }
        // No zone to read the time in: the time is left out rather than shown in another zone.
        assertEquals(
            "Waiting for Thursday's prices, which have been 41 % cheaper over the last 4 weeks.",
            HaStatusText.line(thursday, StatusFormat("en", null, "SEK", "kr"), now, ahead)
        )
        // The dashboard's planning record carries the time to the status line.
        val status = DashboardStatus(listOf(thursday), StatusTone.NORMAL, publicationAt = ahead)
        assertEquals(
            "Waiting for Thursday's prices (at 13:00), which have been 41 % cheaper over the last 4 weeks.",
            HaStatusText.render(status, format("en"), now)
        )
        assertEquals(
            "Waiting for Thursday's prices (at 13:00), which have been 41 % cheaper over the last 4 weeks",
            HaStatusText.priceWait(status, format("en"), now)
        )
    }

    @Test fun theIdentificationLinesAreWordedAsTheCardWordsThemAheadOfThePlan() {
        fun headline(code: String, language: String) = HaStatusText.render(
            DashboardStatus(
                listOf(StatusLine(code, emptyMap()), StatusLine("waiting_for_tomorrow", emptyMap())),
                StatusTone.NORMAL
            ),
            format(language), now
        )
        assertEquals("Identifierar bilen… · Väntar på morgondagens priser.", headline("identifying_vehicle", "sv"))
        assertTrue(headline("asking_vehicle", "sv")!!.startsWith("Väntar på svar: vilken bil är inkopplad? · "))
        assertTrue(headline("identifying_vehicle", "en")!!.startsWith("Identifying the car… · "))
        assertTrue(headline("asking_vehicle", "en")!!.startsWith("Waiting for an answer: which car is plugged in? · "))
        for (language in listOf("da", "nb", "fi")) {
            assertFalse(headline("identifying_vehicle", language)!!.contains("Home Assistant"))
            assertFalse(headline("asking_vehicle", language)!!.contains("Home Assistant"))
        }
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

    @Test fun theWaitForThePlannedStartIsFollowedByTheInstalledPlansFacts() {
        val status = DashboardStatus(
            listOf(
                StatusLine("held_until_window", mapOf("time" to "2026-09-22T20:30:00+00:00")),
                StatusLine("plan_energy", mapOf("kwh" to 16.6)),
                StatusLine("plan_cost", mapOf("amount_minor" to 1710.0, "currency" to "SEK")),
                StatusLine("plan_distance", mapOf("mil" to 8.7)),
            ),
            StatusTone.NORMAL
        )
        assertEquals("Laddningen väntar till planerad start kl. 22:30 · 16,6 kWh · 17,1 kr · 8,7 mil",
            HaStatusText.render(status, format("sv"), now))
        assertEquals("Charging waits for the planned start at 22:30 · 16.6 kWh · 17.1 kr · 87 km",
            HaStatusText.render(status, format("en"), now))
    }

    @Test fun aCarThatEndedTheChargeIsFullOrTriedAgainNeverCharging() {
        fun say(code: String, params: Map<String, Any?> = emptyMap(), language: String = "en") =
            HaStatusText.line(StatusLine(code, params), format(language), now)
        assertEquals("Solar · the car is full", say("solar_vehicle_full"))
        assertEquals("Solar · the car stopped charging; trying again at 10:15",
            say("solar_car_stopped", mapOf("time" to "2026-09-22T08:15:00+00:00")))
        assertEquals("Solar · the car stopped charging", say("solar_car_stopped", mapOf("time" to null)))
        assertEquals("Sol · bilen slutade ladda; försöker igen kl. 10:15",
            say("solar_car_stopped", mapOf("time" to "2026-09-22T08:15:00+00:00"), "sv"))
    }

    @Test fun anEmptyChargerOnSolarSaysNoCarOrTheSurplusThereIsForOne() {
        fun say(code: String, params: Map<String, Any?> = emptyMap(), language: String = "en") =
            HaStatusText.line(StatusLine(code, params), format(language), now)
        assertEquals("Sol · ingen bil inkopplad", say("solar_no_car", language = "sv"))
        assertEquals("Sol · överskott finns (4,2 kW)", say("solar_no_car_surplus", mapOf("surplus_kw" to 4.23), "sv"))
        assertEquals("Solar · no car plugged in", say("solar_no_car"))
        assertEquals("Solar · surplus available (4.2 kW)", say("solar_no_car_surplus", mapOf("surplus_kw" to 4.23)))
        assertEquals("Sol · överskott finns", say("solar_no_car_surplus", mapOf("surplus_kw" to null), "sv"))
        for (language in HaStatusWording.LANGUAGES) {
            for (line in listOf(StatusLine("solar_no_car", emptyMap()), StatusLine("solar_no_car_surplus", mapOf("surplus_kw" to null)))) {
                val text = HaStatusText.line(line, format(language), now)
                assertTrue("$language: $text", !text.isNullOrBlank() && !text!!.contains('{') && !text.contains("Home Assistant"))
            }
            val shown = HaStatusText.line(StatusLine("solar_no_car_surplus", mapOf("surplus_kw" to 4.23)), format(language), now)
            assertTrue("$language: $shown", Regex("""\(4[.,]2 kW\)$""").containsMatchIn(shown ?: ""))
        }
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

    @Test fun aFriendlyNameIsShownInPlaceOfTheEntityId() {
        val unreadable = StatusLine(
            "solar_charger_current_missing",
            mapOf("entity" to "sensor.halo_current", "entity_name" to "HALO current")
        )
        val meter = StatusLine(
            "site_meter_unavailable",
            mapOf(
                "entities" to listOf("sensor.a", "sensor.b"),
                "cause" to "meter_unavailable",
                "entity_names" to listOf("Meter A", "Meter B")
            )
        )
        assertEquals(
            "Laddarens egen ström (HALO current) har ingen mätning; till dess startar sol bara på minsta ström.",
            HaStatusText.line(unreadable, format("sv"), now)
        )
        assertTrue(HaStatusText.line(meter, format("en"), now).contains("Meter A, Meter B"))
    }

    @Test fun aSignedMeterReadAsUnsignedNamesThePhasesAndTheSetting() {
        val line = StatusLine("site_current_negative", mapOf("phases" to listOf("L2", "L3")))
        assertEquals(
            "The meter reports a negative current (export) on L2 and L3 — turn on “Grid current is signed”.",
            HaStatusText.line(line, format("en"), now)
        )
        assertEquals(
            "Mätaren rapporterar negativ ström (export) på L2 och L3 — slå på ”Nätströmmen är teckenmärkt”.",
            HaStatusText.line(line, format("sv"), now)
        )
        for (language in listOf("da", "nb", "fi")) {
            val worded = HaStatusText.line(line, format(language), now)
            assertTrue(worded.contains("L2") && worded.contains("L3"))
            assertFalse(worded.contains("{"))
        }
    }

    @Test fun whySolarHasNoFullBasisAndAMeterInStandbyAreWorded() {
        val notSet = StatusLine("solar_charger_current_missing", mapOf("entity" to null))
        val unreadable = StatusLine("solar_charger_current_missing", mapOf("entity" to "sensor.easee_current"))
        val grid = StatusLine("solar_no_grid_power", mapOf("entity" to null))
        val gridEntity = StatusLine("solar_no_grid_power", mapOf("entity" to "sensor.net"))
        val battery = StatusLine("solar_battery_unreadable", mapOf("entity" to "sensor.bat"))
        val site = StatusLine("solar_site_incomplete", mapOf("phases" to listOf("L1")))
        val meter = StatusLine(
            "site_meter_unavailable",
            mapOf("entities" to listOf("sensor.solax_grid_current_l1"), "cause" to "inverter_standby")
        )
        assertEquals(
            "The charger's own current is not set — choose it under Site wiring; " +
                "until then solar starts only at the minimum current.",
            HaStatusText.line(notSet, format("en"), now)
        )
        val own = StatusLine(
            "solar_charger_current_missing",
            mapOf("entity" to null, "integration" to "Peblar", "sensors" to "Current phase 1–3")
        )
        assertEquals(
            "Laddarens egen ström är inte vald — välj Current phase 1–3 från Peblar under Anläggningens koppling, " +
                "eller tryck på Använd där; till dess startar sol bara på minsta ström.",
            HaStatusText.line(own, format("sv"), now)
        )
        assertEquals(
            "Laddarens egen ström (sensor.easee_current) har ingen mätning; till dess startar sol bara på minsta ström.",
            HaStatusText.line(unreadable, format("sv"), now)
        )
        assertEquals("Solar · no grid power reading — set Total grid power under Site entities", HaStatusText.line(grid, format("en"), now))
        assertEquals("Sol · nätets totala effekt (sensor.net) har ingen färsk mätning", HaStatusText.line(gridEntity, format("sv"), now))
        assertEquals("Solar · the battery power (sensor.bat) has no fresh reading", HaStatusText.line(battery, format("en"), now))
        assertEquals(
            "Anläggningens mätning är ofullständig (L1) — sol går bara på nätets totala effekt.",
            HaStatusText.line(site, format("sv"), now)
        )
        assertEquals(
            "The meter's sensors are unavailable (the inverter may be in standby): sensor.solax_grid_current_l1.",
            HaStatusText.line(meter, format("en"), now)
        )
        assertEquals(
            "Mätarens sensorer är otillgängliga: sensor.m.",
            HaStatusText.line(
                StatusLine("site_meter_unavailable", mapOf("entities" to listOf("sensor.m"), "cause" to "meter_unavailable")),
                format("sv"),
                now
            )
        )
        for (language in listOf("da", "nb", "fi")) {
            for (line in listOf(notSet, unreadable, grid, gridEntity, battery, site, meter)) {
                assertFalse(HaStatusText.line(line, format(language), now).contains("{"))
            }
        }
    }

    @Test fun aNeedCappedAtTheBatteryRoomAndAChargeToTheCarsLimitAreWorded() {
        val capped = StatusLine("need_limited_by_room", mapOf("kwh" to 3.44))
        val toLimit = StatusLine("charging_to_vehicle_limit", mapOf("percent" to 100.0))
        assertEquals("Limited to 3.4 kWh: the car is almost full.", HaStatusText.line(capped, format("en"), now))
        assertEquals("Begränsat till 3,4 kWh: bilen är nästan full.", HaStatusText.line(capped, format("sv"), now))
        assertEquals(
            "Charging until the car stops at its own limit (100 %).",
            HaStatusText.line(toLimit, format("en"), now)
        )
        assertEquals(
            "Laddar tills bilen stoppar vid sin egen laddgräns (100 %).",
            HaStatusText.line(toLimit, format("sv"), now)
        )
        for (language in listOf("da", "nb", "fi")) {
            assertFalse(HaStatusText.line(capped, format(language), now).contains("{"))
            assertFalse(HaStatusText.line(toLimit, format(language), now).contains("{"))
        }
    }

    @Test fun aFillChargeAndAFillWithoutAKnownRoomAreWordedAsTheCardWordsThem() {
        val filling = StatusLine("filling_to_limit", mapOf("kwh" to 9.47))
        val unknown = StatusLine("fill_room_unknown", mapOf("kwh" to 12.0))
        assertEquals("Charging until the car is full, 9.5 kWh now.", HaStatusText.line(filling, format("en"), now))
        assertEquals("Laddar tills bilen är full, nu 9,5 kWh.", HaStatusText.line(filling, format("sv"), now))
        assertEquals(
            "The car's level or battery size is unknown, so 12 kWh is charged instead of filling.",
            HaStatusText.line(unknown, format("en"), now)
        )
        assertEquals(
            "Bilens nivå eller batteristorlek är okänd, så 12 kWh laddas i stället för att fylla.",
            HaStatusText.line(unknown, format("sv"), now)
        )
        for (language in listOf("da", "nb", "fi")) {
            assertFalse(HaStatusText.line(filling, format(language), now).contains("{"))
            assertFalse(HaStatusText.line(unknown, format(language), now).contains("{"))
        }
    }

    @Test fun aCarFinishingPastTheLastWindowChargesUntilItIsFullAtMostUntilATime() {
        val topOff = StatusLine("topping_off", mapOf("until" to "2026-09-22T05:40:00+00:00"))
        assertEquals(
            "Charging until the car is full (at most until 07:40).",
            HaStatusText.line(topOff, format("en"), now)
        )
        assertEquals(
            "Laddar tills bilen är full (som längst till 07:40).",
            HaStatusText.line(topOff, format("sv"), now)
        )
        for (language in listOf("da", "nb", "fi")) {
            assertFalse(HaStatusText.line(topOff, format(language), now).contains("{"))
        }
    }

    @Test fun aPriceWaitAfterAStrategyHeadlineIsShownAsItsOwnLine() {
        val status = DashboardStatus(
            listOf(
                StatusLine("hybrid_grid", mapOf("grid_kwh" to 34.7, "credit_kwh" to 8.8, "window_start" to null, "window_end" to null)),
                StatusLine("waiting_for_publication", mapOf("publication_at" to "2026-09-22T11:00:00+00:00"))
            ),
            StatusTone.NORMAL
        )
        assertEquals(
            "Hybrid · 34,7 kWh från elnätet, 8,8 kWh väntas från solen · Väntar på morgondagens priser (~13:00), planerar då.",
            HaStatusText.render(status, format("sv"), now)
        )
        assertEquals("Väntar på morgondagens priser (~13:00), planerar då", HaStatusText.priceWait(status, format("sv"), now))
        assertEquals(null, HaStatusText.priceWait(DashboardStatus(emptyList(), StatusTone.NORMAL), format("sv"), now))
    }

    @Test fun aDepartureShortfallIsWordedWithThePercentRoundedDownAndTheDeparturesClock() {
        val s = status("target_soc_departure_shortfall")
        assertEquals(
            "Planerat från 10:00 · 7,4 kWh · 13,02 kr · 3,7 mil · Hinner inte bli klar till avresan: cirka 44 % (7,4 kWh) till 12:00.",
            HaStatusText.render(s, format("sv"), now)
        )
        assertEquals(
            "Planned from 10:00 · 7.4 kWh · 13.02 kr · 37 km · Will not be ready by the departure: about 44 % (7.4 kWh) by 12:00.",
            HaStatusText.render(s, format("en"), now)
        )
        val line = StatusLine("departure_shortfall", mapOf("departure" to "2026-09-22T10:00:00+00:00", "kwh" to 7.36, "requested_kwh" to 13.52, "soc_percent" to null))
        assertEquals("Hinner inte bli klar till avresan: cirka 7,4 av 13,5 kWh till 12:00.", HaStatusText.line(line, format("sv"), now))
        val noTime = StatusLine("departure_shortfall", mapOf("kwh" to 7.36, "requested_kwh" to 13.52, "soc_percent" to 44.9))
        assertEquals("Will not be ready by the departure: about 44 % (7.4 kWh).", HaStatusText.line(noTime, format("en"), now))
        assertEquals("Will not be ready by the departure.", HaStatusText.line(StatusLine("departure_shortfall", emptyMap()), format("en"), now))
    }

    @Test fun aPlanningUnavailableReasonHasItsOwnSentenceElseTheGenericOne() {
        fun say(language: String, reason: Any?) =
            HaStatusText.line(StatusLine("planning_unavailable", mapOf("reason" to reason)), format(language), now)
        assertEquals("Inga priser är publicerade för det här prisområdet i dag.", say("sv", "no_published_prices"))
        assertEquals("The prices cannot be converted to the area's currency: the day's exchange rate is missing.", say("en", "missing_fx_rate"))
        assertEquals("Not one whole quarter-hour is left before the departure, so nothing can be charged for it.", say("en", "deadline_too_short"))
        assertEquals(
            "The charging plan could not be calculated because of an unexpected error. The log has the details.",
            say("en", "unexpected_failure")
        )
        assertEquals(say("en", "price_data_invalid"), HaStatusText.line(StatusLine("price_data_invalid", emptyMap()), format("en"), now))
        assertEquals("No charging plan could be calculated with the data available right now.", say("en", "something_new"))
    }

    @Test fun aWeekdayIsNamedAsTheSentenceAboutItsPricesTakesIt() {
        assertEquals("Thursday", HaStatusText.weekdaySingular("en", 4.0))
        assertEquals("torsdag", HaStatusText.weekdaySingular("sv", 4.0))
        assertEquals("Sonntag", HaStatusText.weekdaySingular("de", 7.0))
        assertEquals("zondag", HaStatusText.weekdaySingular("nl", 7.0))
        assertEquals("domingo", HaStatusText.weekdaySingular("es", 7.0))
        assertEquals("dimanche", HaStatusText.weekdaySingular("fr", 7.0))
        assertEquals("torstain", HaStatusText.weekdaySingular("fi", 4.0))
        assertEquals("keskiviikon", HaStatusText.weekdaySingular("fi", 3.0))
        assertEquals(null, HaStatusText.weekdaySingular("fr", 8.0))
    }

    @Test fun aPlanThatSavesMoneySaysSoWithOneDecimal() {
        val saving = StatusLine("plan_saving", mapOf("amount_minor" to 260.0, "currency" to "SEK"))
        assertEquals("sparar ~2,6 kr mot att ladda nu", HaStatusText.line(saving, format("sv"), now))
        assertEquals("saves ~2.6 kr against charging now", HaStatusText.line(saving, format("en"), now))
    }

    @Test fun theSunAForecastPromisesIsToldWithItsHoursTodayOrTomorrow() {
        fun sun(start: String, end: String) =
            StatusLine("solar_forecast", mapOf("kwh" to 12.34, "start" to start, "end" to end))
        assertEquals(
            "12,3 kWh väntas från solen 10:00–15:00",
            HaStatusText.line(sun("2026-09-22T08:00:00+00:00", "2026-09-22T13:00:00+00:00"), format("sv"), now)
        )
        assertEquals(
            "12.3 kWh expected from the sun tomorrow 09:00–14:00",
            HaStatusText.line(sun("2026-09-23T07:00:00+00:00", "2026-09-23T12:00:00+00:00"), format("en"), now)
        )
        assertEquals("12,3 kWh väntas från solen", HaStatusText.line(StatusLine("solar_forecast", mapOf("kwh" to 12.34)), format("sv"), now))
    }

    @Test fun theSunAHybridPlanCountsOnIsToldWithItsHoursWhenTheyAreStated() {
        val params = mapOf("grid_kwh" to 8.0, "credit_kwh" to 4.0, "window_start" to "2026-09-22T01:00:00+00:00", "window_end" to "2026-09-22T03:00:00+00:00")
        assertEquals(
            "Hybrid · 8 kWh from grid 03:00–05:00, 4 kWh expected from sun 10:00–15:00",
            HaStatusText.line(
                StatusLine("hybrid_grid", params + mapOf("sun_start" to "2026-09-22T08:00:00+00:00", "sun_end" to "2026-09-22T13:00:00+00:00")),
                format("en"), now
            )
        )
        assertEquals(
            "Hybrid · 8 kWh from grid 03:00–05:00, 4 kWh expected from sun",
            HaStatusText.line(StatusLine("hybrid_grid", params + mapOf("sun_start" to null, "sun_end" to null)), format("en"), now)
        )
    }

    @Test fun aChargersOwnModeThatWorksAgainstSpotNavIsToldWithWhereToChangeIt() {
        val own = StatusLine("charger_own_mode", mapOf("entity" to "select.p", "label" to "smart charging", "state" to "Pure solar", "value" to "default"))
        assertEquals(
            "Laddboxens egen smart charging är på (Pure solar). Den motverkar SpotNav. Ändra den på laddboxens enhetssida i Home Assistant.",
            HaStatusText.line(own, format("sv"), now)
        )
    }

    @Test fun aChargersOwnModeUsesHomeAssistantsNamesWhenTheyAreSent() {
        val own = StatusLine(
            "charger_own_mode",
            mapOf(
                "entity" to "select.p", "label" to "smart charging", "state" to "pure_solar", "value" to "default",
                "entity_name" to "Smart charging", "state_name" to "Pure solar", "value_name" to "Default"
            )
        )
        assertEquals(
            "The charger's own smart charging is on (Pure solar). It works against SpotNav. Change it on the charger's device page in Home Assistant.",
            HaStatusText.line(own, format("en"), now)
        )
        // An abbreviation keeps its capitals, and German its noun.
        assertEquals("PV surplus", HaStatusText.inSentence("en", "PV surplus"))
        assertEquals("Smart charging", HaStatusText.inSentence("de", "Smart charging"))
    }

    @Test fun theWhereToChangeItHintIsLeftOutWhileTheButtonIsShown() {
        val own = StatusLine("charger_own_mode", mapOf("entity" to "select.p", "label" to "smart charging", "state" to "Pure solar", "value" to "default"))
        assertEquals(
            "Laddboxens egen smart charging är på (Pure solar). Den motverkar SpotNav.",
            HaStatusText.line(own, format("sv").copy(ownModeButton = true), now)
        )
    }

    @Test fun aStopNothingCanSeeEndNamesTheAppsOwnButtons() {
        val line = StatusLine("stop_unplug_unseen", emptyMap())
        assertEquals("Stopped by hand. Press Start or Resume to charge again.", HaStatusText.line(line, format("en"), now))
        assertEquals("Stoppad för hand. Tryck Starta eller Återuppta för att ladda igen.", HaStatusText.line(line, format("sv"), now))
        // The card's German says "Starten"; the app's button says "Start".
        assertEquals(
            "Von Hand gestoppt. Tippe auf Start oder Fortsetzen, um wieder zu laden.",
            HaStatusText.line(line, format("de"), now)
        )
        for (language in HaStatusWording.LANGUAGES) {
            val folder = if (language == "en") "values" else "values-$language"
            val xml = listOf("src/main/res", "app/src/main/res").map { java.io.File(it, "$folder/strings.xml") }.first { it.exists() }.readText()
            fun button(name: String) = Regex("<string name=\"$name\">(.*?)</string>").find(xml)!!.groupValues[1]
            val worded = HaStatusText.line(line, format(language), now)
            assertTrue("$language names Start", worded.contains(button("bar_start")))
            assertTrue("$language names Resume", worded.contains(button("bar_resume")))
        }
    }

    @Test fun theSunPastATargetNamesTheCarsLimit() {
        assertEquals(
            "Målet nått – solen fyller på till bilens gräns 80 %",
            HaStatusText.line(StatusLine("sun_past_target", mapOf("percent" to 80.0)), format("sv"), now)
        )
    }
}

class ImportTargetStatusTextTest {
    private val now = Instant.parse("2026-09-22T18:10:00Z")
    private fun format(language: String) = StatusFormat(language, ZoneId.of("Europe/Stockholm"), "SEK", "kr")
    private fun say(code: String, params: Map<String, Any?>, language: String = "sv") =
        HaStatusText.line(StatusLine(code, params), format(language), now)

    @Test fun aHeldChargeSaysTheLimitTheTargetAndTheHourSoFar() {
        val held = mapOf("limit_a" to 9.0, "target_kw" to 6.0, "hour_kw" to 4.24, "set_kw" to 6.0)
        assertEquals("Hålls på 9 A så att timmen stannar under 6,0 kW (nu 4,2 kW).", say("import_target_held", held))
        assertEquals(
            "Held back to keep this hour under 6.0 kW (now 4.2 kW).",
            say("import_target_held", held + ("limit_a" to null), "en")
        )
    }

    @Test fun aLevelAboveTheTargetAsSetIsTheMonthsPeak() {
        val held = mapOf("limit_a" to 9.0, "target_kw" to 6.975, "hour_kw" to 4.2, "set_kw" to 6.0)
        assertEquals(
            "Hålls på 9 A så att timmen stannar under 7,0 kW, månadens topp (effektmål 6,0 kW; nu 4,2 kW).",
            say("import_target_held", held)
        )
        // An older Home Assistant without `set_kw`: the plain sentence.
        assertEquals("Hålls på 9 A så att timmen stannar under 7,0 kW (nu 4,2 kW).", say("import_target_held", held - "set_kw"))
    }

    @Test fun aPauseSaysUntilTheNextHourAndWhetherTheHouseAloneIsOver() {
        val paused = mapOf("until" to "2026-09-22T19:00:00+00:00", "target_kw" to 6.0, "set_kw" to 6.0, "house_over" to false)
        assertEquals("Pausad till kl. 21:00 så att timmen stannar under 6,0 kW.", say("import_target_paused", paused))
        assertEquals(
            "Pausad till kl. 21:00 – huset ensamt ligger över effektmålet på 6,0 kW den här timmen.",
            say("import_target_paused", paused + ("house_over" to true))
        )
        assertEquals(
            "Pausad till nästa hela timme så att timmen stannar under 6,0 kW.",
            say("import_target_paused", paused + ("until" to null))
        )
    }

    @Test fun theHouseAloneOverTheTargetIsOneLine() {
        assertEquals(
            "Huset ensamt ligger över effektmålet på 6,0 kW den här timmen.",
            say("import_target_house_over", mapOf("target_kw" to 6.0, "set_kw" to 6.0))
        )
    }

    @Test fun theShortfallUnderTheTargetSaysTheDepartureAndWhatIsMissing() {
        val risk = mapOf("departure" to "2026-09-23T05:00:00+00:00", "missing_kwh" to 4.4, "target_kw" to 6.0, "set_kw" to 6.0, "releasable" to true)
        assertEquals("Bilen blir inte klar till 07:00 under effektmålet 6,0 kW – 4 kWh saknas.", say("import_target_at_risk", risk))
        assertEquals(
            "Bilen blir inte klar till avresan under effektmålet 6,0 kW – 4 kWh saknas.",
            say("import_target_at_risk", risk + ("departure" to null))
        )
    }

    @Test fun aReleasedTargetSaysWhoSetItAsideAndUntilWhen() {
        val released = mapOf("departure" to "2026-09-23T05:00:00+00:00", "target_kw" to 6.0, "automatic" to false)
        assertEquals("Effektmålet är släppt av dig till kl. ${day()} 07:00.", say("import_target_released", released))
        assertEquals(
            "Effektmålet släpps: bilen behöver effekten för att bli klar till avresan.",
            say("import_target_released", released + mapOf("automatic" to true, "departure" to null))
        )
    }

    @Test fun aGuestCarSaysItsAmountByTheDepartureAndThatItWaitsToBeUnplugged() {
        val planned = mapOf("kwh" to 10.0, "departure" to "2026-09-23T05:00:00+00:00")
        assertEquals("Gästbil · 10 kWh före 07:00", say("guest_planned", planned))
        assertEquals("Gästbil · 10 kWh", say("guest_planned", planned + ("departure" to null)))
        assertEquals(
            "Guest car · 12.5 kWh by 07:00",
            HaStatusText.line(StatusLine("guest_planned", planned + ("kwh" to 12.5)), format("en"), now)
        )
        assertEquals("Gästbil · klar, väntar på urkoppling", say("guest_done", emptyMap()))
        assertEquals("Guest car · done, waiting to be unplugged", HaStatusText.line(StatusLine("guest_done", emptyMap()), format("en"), now))
    }

    private fun day() = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.forLanguageTag("sv"))
        .format(java.time.ZonedDateTime.parse("2026-09-23T07:00:00+02:00[Europe/Stockholm]"))
}
