package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.site.BatteryControlRead
import se.sensnology.spotnav.ha.site.BatteryStatus
import se.sensnology.spotnav.ha.site.HeatControlStatus
import se.sensnology.spotnav.ha.site.HeatNight
import se.sensnology.spotnav.ha.site.HeatSettings
import se.sensnology.spotnav.ha.site.HeatSettingsRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.heat.HeatWrite
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The battery and heat settings in the card's own words, on the integration's fixtures. */
class SiteSettingsTextTest {
    private fun text(language: String = "en") = SiteSettingsText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun status(path: String) = (BatteryControlRead.answer(200, HaFixtures.json(path).toString()) as BatteryControlRead.Outcome.Status).status

    @Test fun theModeDialogSaysTheMarginAndTheWearInTheSitesMoney() {
        val live = status("battery_control/live.json")
        assertEquals("Balanced sells at 1 kr/kWh net or more", text().planMargin(live))
        assertEquals("Balanserad säljer vid minst 1 kr/kWh netto", text("sv").planMargin(live))
        assertEquals("Slitage (öre/kWh åt varje håll)", text("sv").wearLabel(live.planCosts!!))
        // No wear of the site's own: the currency's default.
        assertEquals("Standard", text("sv").wearValue(live))
        assertEquals("Vad det kostar i slitage att ladda och ladda ur en kWh. Standard 15 öre.", text("sv").wearHelp(live))
        assertEquals("20,5 öre/kWh", text("sv").wearValue(live.copy(wearOwnMinorPerKwh = 20.5)))
        val euro = BatteryStatus.PlanCosts(1.4, 10.0, "EUR", "cent", wearDefaultMinorPerKwh = 1.4)
        assertEquals("1.4 cent", text().planMinor(1.4, euro))
        assertEquals("1.25 cent", text().planMinor(1.25, euro))
        assertEquals("540 cent", text().planMinor(540.0, euro))
        assertEquals("1.4 cent", text().planMoney(1.4, euro))
        assertEquals("1.40 EUR", text().planMoney(140.0, euro))
        assertEquals("0 EUR", text().planMoney(0.0, euro))
        assertEquals("25 EUR", text().planMoney(2500.0, euro))
        // Without the block (an older Home Assistant, or no price area): no margin and no wear row.
        val older = status("battery_control/checking.json")
        assertNull(text().planMargin(older))
        assertNull(text().wearValue(older))
        // A Home Assistant that cannot take a wear of the site's own: no wear row.
        assertNull(text().wearValue(live.copy(planCosts = live.planCosts!!.copy(wearDefaultMinorPerKwh = null))))
        for (language in listOf("en", "sv", "da", "de", "nb", "fi", "fr", "nl", "es")) {
            val line = text(language).planMargin(live)
            assertTrue(language, line != null && "1" in line && "{" !in line)
            assertTrue(language, text(language).wearHelp(live)!!.let { "15" in it && "{" !in it })
        }
    }

    @Test fun aColdBatterysPreheatingAndTheDisabledFactsAreOneLineEachOnlyWhenTheySaySomething() {
        val live = status("battery_control/live.json")
        assertNull(text().preheatLine(live))
        assertNull(text().factsLine(live))
        val cold = live.copy(preheat = BatteryStatus.Preheat("off", 2.4, "switch.ess_preheating_enable"))
        assertEquals(
            "The battery's cells are cold (2.4 °C) and its preheating is off, so it takes less charge in the cheap hours. Turn on “ESS Preheating Enable” on the battery's device in Home Assistant.",
            text().preheatLine(cold)
        )
        assertTrue(text("sv").preheatLine(cold.copy(preheat = BatteryStatus.Preheat("disabled", null, null)))!!.contains("(– °C)"))
        assertNull(text().preheatLine(cold.copy(preheat = BatteryStatus.Preheat("brand_new", 1.0, null))))
        val facts = live.copy(enableFacts = BatteryStatus.EnableFacts("Sigenergy", 2, listOf("size", "floor")))
        assertEquals("Batteriets storlek och lägsta nivå finns i avstängda entiteter i Sigenergy.", text("sv").factsLine(facts))
        assertTrue(text().factsLine(facts.copy(enableFacts = BatteryStatus.EnableFacts("Sigenergy", 1, listOf("rating"))))!!.contains("charge and discharge"))
        assertNull(text().factsLine(facts.copy(enableFacts = BatteryStatus.EnableFacts("Sigenergy", 0, listOf("size")))))
    }

    @Test fun aBrandStillBeingTestedSaysWhereTheTesterStandsAndAMirroredBatteryThatItIsReadOnly() {
        val live = status("battery_control/live.json")
        // The fixtures send both as null: no row, no line.
        assertNull(live.tester)
        assertNull(text().testerRow(live))
        assertNull(text().readOnlyLine(live))
        val json = HaFixtures.json("battery_control/live.json").apply {
            getJSONObject("status").put("battery_read_only", "remote_homeassistant").put(
                "tester",
                org.json.JSONObject().put("stage", "none").put("brand", "solis").put("sent_at", org.json.JSONObject.NULL)
                    .put("result_sent_at", org.json.JSONObject.NULL).put("test_passed", false).put("code_entered_at", org.json.JSONObject.NULL)
            )
        }
        val shown = (BatteryControlRead.decode(json) as BatteryControlRead.Outcome.Status).status
        assertEquals(BatteryStatus.Tester("none", "solis", false), shown.tester)
        assertEquals(
            Triple("Testare", "Bli testare", "SpotNav kan läsa det här batteriet. Styrningen av Solis testas tillsammans med användare: bli testare så får du instruktioner via e-post."),
            text("sv").testerRow(shown)
        )
        assertTrue(text().testerSignup(shown))
        val coded = shown.copy(tester = BatteryStatus.Tester("control_code", "foxess", true))
        assertEquals("Kod inskriven", text("sv").testerRow(coded)!!.second)
        assertEquals(false, text().testerSignup(coded))
        // A stage the app has no words for reads as none; a brand it does not know is capitalised.
        assertEquals("Bli testare", text("sv").testerRow(shown.copy(tester = BatteryStatus.Tester("later", "acme", false)))!!.second)
        assertEquals("Acme", SiteSettingsText.brandName("acme"))
        assertEquals("Läses via Remote Home-Assistant: endast läsning. SpotNav planerar med batteriet men styr det aldrig.", text("sv").readOnlyLine(shown))
        // A tester block without its stage is no document.
        val broken = HaFixtures.json("battery_control/live.json").apply { getJSONObject("status").put("tester", org.json.JSONObject().put("brand", "solis")) }
        assertTrue(BatteryControlRead.decode(broken) is BatteryControlRead.Outcome.Failed)
        for (language in CardStringsXml.LANGUAGES) {
            for (stage in SiteSettingsText.TESTER_STAGES) {
                val row = text(language).testerRow(shown.copy(tester = BatteryStatus.Tester(stage, "marstek", false)))!!
                assertTrue("$language $stage", listOf(row.first, row.second, row.third).none { it.contains('{') })
            }
        }
    }

    @Test fun degreesAndTheNightAreWordedAsTheCardWordsThem() {
        val sv = text("sv")
        assertEquals("+0,5 °C", sv.signedDegrees(0.5))
        assertEquals("−1,5 °C", sv.signedDegrees(-1.5))
        assertEquals("0 °C", sv.signedDegrees(0.0))
        assertEquals("−1,5 °C 22:30–05:30", sv.nightValue(HeatNight(true, 1.5, "22:30", "05:30")))
        assertEquals("Av", sv.nightValue(HeatNight(false, 1.5, "22:30", "05:30")))
        assertEquals("Ovanvåning", sv.roomName("Ovanvåning temperatur"))
        assertEquals("Temperatur", sv.roomName("Temperatur"))
    }

    @Test fun aRefusedWriteIsWordedByItsKind() {
        val en = text()
        assertEquals(SiteSettingsText.Issue.CONFLICT, en.heatIssue(HeatWrite.Outcome.Conflict))
        assertEquals(SiteSettingsText.Issue.NOT_PERMITTED, en.heatIssue(HeatWrite.Outcome.NotPermitted))
        assertEquals(SiteSettingsText.Issue.RANGE, en.heatIssue(HeatWrite.Outcome.Refused(listOf("out_of_range"))))
        assertEquals(SiteSettingsText.Issue.STEP, en.heatIssue(HeatWrite.Outcome.Refused(listOf("not_a_step"))))
        assertEquals(SiteSettingsText.Issue.GENERIC, en.heatIssue(HeatWrite.Outcome.Refused(listOf("unknown_field"))))
        assertNull(en.heatIssue(HeatWrite.Outcome.Written))
        assertEquals(SiteSettingsText.Issue.NOT_PERMITTED, en.batteryIssue(BatteryControlRead.Outcome.NotPermitted))
        assertEquals(SiteSettingsText.Issue.GENERIC, en.batteryIssue(BatteryControlRead.Outcome.Invalid))
        assertEquals("Someone else changed it meanwhile; showing the latest.", en.cardSentence(SiteSettingsText.Issue.CONFLICT, battery = false))
        assertEquals("That did not work. Try again.", en.cardSentence(SiteSettingsText.Issue.GENERIC, battery = true))
        // Not permitted has no card sentence: the screen says it in its own words.
        assertNull(en.cardSentence(SiteSettingsText.Issue.NOT_PERMITTED, battery = false))
    }

    private fun settings(name: String): HeatSettings = (HeatSettingsRead.decode(HaFixtures.json("heat_card/$name")) as SiteRead.Read).value

    private fun HeatSettings.with(change: HeatControlStatus.() -> HeatControlStatus) = copy(control = control!!.change())

    @Test fun theZonesAndTheWaterHeaterHaveTheirLinesUnderTheirRows() {
        val settings = settings("controlled.json")
        val hold = text("sv").hold(settings)
        assertEquals("SpotNav håller 21,0 °C till 19:00.", hold.zoneLine(settings.zoneStatus["climate.golvvarme_toalett_uppe"]!!))
        assertEquals(null, hold.zoneLine(settings.zoneStatus["climate.golvvarme_toalett_nere"]!!))
        val line = hold.waterHeaterLine(settings.waterHeaterStatus!!, settings.waterHeater!!.control)!!
        assertEquals(true, line.startsWith("Av till ") && line.endsWith("01:00."))
    }

    @Test fun everyPathTheCardNamesHasItsName() {
        val sv = text("sv")
        assertEquals("SG Ready via integrationen (NIBE S)", sv.pathName("sg_ready_api"))
        assertEquals("SG Ready through the integration (NIBE S)", text().pathName("sg_ready_api"))
        for (path in listOf("none", "relay", "sg_ready", "sg_ready_api", "offset", "setpoint")) {
            for (language in CardStringsXml.LANGUAGES) assertTrue("$language $path", !text(language).pathName(path).startsWith("heat."))
        }
        // A path the app does not know yet is shown by its own word.
        assertEquals("heat_pump_api", sv.pathName("heat_pump_api"))
        assertEquals("Aktivering", sv.secondEntityLabel("sg_ready_api"))
        assertEquals("Andra kontakten", sv.secondEntityLabel("sg_ready"))
        assertNull(sv.secondEntityLabel("offset"))
    }

    @Test fun controlTheHeatHasItsRowOnEverySteeredPath() {
        val sv = text("sv")
        val api = settings("sg_api_pump.json")
        assertEquals("sg_ready_api", api.controlPath)
        assertTrue(sv.steers(api))
        assertTrue(sv.steers(api.copy(controlPath = "offset")))
        assertTrue(!sv.steers(api.copy(controlPath = "setpoint")))
        assertTrue(sv.steers(api.copy(controlPath = "setpoint", controlEnabled = true)))
        assertTrue(api.control!!.untested)
    }

    @Test fun theLineUnderControlTheHeatAsTheCardSaysIt() {
        val sv = text("sv")
        val controlled = settings("controlled.json")
        assertEquals("Styr: sänker värmen till 19:00.", sv.controlLine(controlled))
        assertEquals("Styr: sänker värmen till 19:00, för varmt inne.", sv.controlLine(controlled.copy(wouldReason = "room_warm")))
        assertEquals("Styr: sänker värmen för taket till 19:00.", sv.controlLine(controlled.with { copy(reason = "ceiling") }))
        assertEquals("Av: SpotNav visar bara vad den skulle göra.", sv.controlLine(controlled.with { copy(enabled = false, state = "off") }))
        // The safety reasons, the hot water's among them.
        assertEquals("Styr: extra varmvatten är på, ingen sänkning.", sv.controlLine(controlled.with { copy(lowered = false, reason = "hot_water") }))
        assertEquals("Styr: varmvattnet är svalt, ingen sänkning.", sv.controlLine(controlled.with { copy(lowered = false, reason = "hot_water_low") }))
        assertEquals("Controlling: extra hot water is on, no lowering.", text().controlLine(controlled.with { copy(lowered = false, reason = "hot_water") }))
        assertEquals("Styr: värmepumpen går på sin vanliga kurva just nu.", sv.controlLine(controlled.with { copy(lowered = false, reason = "normal") }))
        assertEquals(
            "Styr: höjer värmen till 18:00.",
            sv.controlLine(controlled.with { copy(lowered = false, raised = true, raiseUntil = java.time.Instant.parse("2026-11-16T17:00:00Z")) })
        )
        assertEquals("Controlling: raising the heat now.", text().controlLine(controlled.with { copy(lowered = false, raised = true, raiseUntil = null) }))
        // The relay's waits name the relay; the pump's own settings have their own.
        assertEquals("Väntar: reläet går inte att läsa.", sv.controlLine(controlled.with { copy(state = "waiting", reason = "relay_unavailable") }))
        val api = settings("sg_api_pump.json")
        fun waiting(reason: String, state: String = "waiting") = api.with { copy(enabled = true, state = state, reason = reason) }
        assertEquals("Väntar: värmepumpens inställning går inte att läsa.", sv.controlLine(waiting("relay_unavailable")))
        assertEquals("Provar att värmepumpen svarar: väntar på en stund när den värmer.", sv.controlLine(waiting("wait", "verifying")))
        assertEquals("Väntar: värmepumpens egen prisstyrning är på.", sv.controlLine(waiting("pump_price_control")))
        assertEquals(
            "Väntar: stäng av automationen 'Panna' så att SpotNav kan styra värmen.",
            sv.controlLine(api.with { copy(enabled = true, state = "waiting", reason = "automation", automationName = "Panna") })
        )
        assertEquals("Väntar.", sv.controlLine(waiting("something_new")))
        assertNull(sv.controlLine(controlled.copy(control = null)))
        // No wait is left as its key in any language.
        for (language in CardStringsXml.LANGUAGES) {
            for (reason in listOf("automation", "other_controller", "pump_price_control", "relay_unavailable", "relay_closed", "no_relay", "no_signal", "check_failed", "foreign_change", "no_read_back", "hot_water", "hot_water_low")) {
                assertTrue("$language $reason", !text(language).controlLine(waiting(reason))!!.startsWith("heat."))
            }
        }
    }

    @Test fun theStoredOffsetThatWasNotPutBack() {
        val sv = text("sv")
        val home = HeatControlStatus.OffsetHome("number.s1155_heating_offset_climate_system_1_40031", -2.0, 0.0)
        assertEquals("−2 (var 0)", sv.offsetHomeValue(home))
        assertEquals(
            "Kurvjusteringen står på −2; den var 0 innan SpotNav. Sätta tillbaka den, eller behålla −2 som ditt eget värde?",
            sv.offsetHomeHelp(home)
        )
        assertEquals("+1,5", sv.offsetValue(1.5))
        assertEquals("0", sv.offsetValue(0.0))
    }

    @Test fun everyWordIsInAllNineLanguages() {
        val keys = listOf(
            "battery.title", "battery.modeLabel", "battery.modeShadow", "battery.intro", "battery.planMargin", "battery.wear.label",
            "battery.wear.help", "battery.wear.default", "battery.preheat.off", "battery.enableFacts.both", "battery.failed", "battery.error.generic", "settings.value.on",
            "heat.settings.title", "heat.settings.night", "heat.settings.nightValue", "heat.settings.zones", "heat.error.conflict",
            "heat.error.step", "heat.path.sg_ready", "heat.settings.sensorOffsetHelp", "battery.modeHelp.max_profit"
        )
        for (language in CardStringsXml.LANGUAGES) {
            val words = CardStringsXml.words(language)
            for (key in keys) assertTrue("$language $key", words.say(key).isNotBlank())
            assertNotNull(text(language).modeName("max_profit"))
        }
    }
}
