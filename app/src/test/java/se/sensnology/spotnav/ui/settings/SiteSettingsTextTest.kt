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

    @Test fun theGridChargeCapSaysWhatItRestsOn() {
        val learned = status("battery_control/checking.json").let { it.copy(allowedActions = listOf("grid_charge", "hold")) }
        assertEquals(
            "At most 3.2 kW from the grid, so the fuse holds even if Home Assistant stops (learned per phase).",
            text().gridChargeCap(learned)
        )
        val cautious = status("battery_control/live.json").let { it.copy(allowedActions = listOf("grid_charge")) }
        assertEquals(
            "No charging from the grid: the house can reach the fuse by itself (cautious until the first days are learned, 7 days left).",
            text().gridChargeCap(cautious)
        )
        // Swedish, on the card's own wording.
        assertEquals(
            "Högst 3,2 kW från nätet, så säkringen klarar sig även om Home Assistant stannar (inlärt per fas).",
            text("sv").gridChargeCap(learned)
        )
        assertEquals("The plant's own import limit protects the fuse when charging from the grid.", text().gridChargeCap(status("battery_control/needs_ack.json")))
        // Charging from the grid not allowed: no line.
        assertNull(text().gridChargeCap(status("battery_control/live.json")))
    }

    @Test fun thePlansFixedCostsAreSaidInTheSitesMoney() {
        val live = status("battery_control/live.json")
        assertEquals("Wear counted as 15 öre/kWh each way · Balanced sells at 1 kr/kWh net or more", text().planCosts(live))
        assertEquals("Slitage räknas som 15 öre/kWh åt varje håll · Balanserad säljer vid minst 1 kr/kWh netto", text("sv").planCosts(live))
        val euro = BatteryStatus.PlanCosts(1.4, 10.0, "EUR", "cent")
        assertEquals("1.4 cent", text().planMoney(1.4, euro))
        assertEquals("1.40 EUR", text().planMoney(140.0, euro))
        assertEquals("0 EUR", text().planMoney(0.0, euro))
        assertEquals("25 EUR", text().planMoney(2500.0, euro))
        // Without the block (an older Home Assistant, or no price area): no line.
        assertNull(text().planCosts(status("battery_control/checking.json")))
        for (language in listOf("en", "sv", "da", "de", "nb", "fi", "fr", "nl", "es")) {
            val line = text(language).planCosts(live)
            assertTrue(language, line != null && "15" in line && "{" !in line)
        }
    }

    @Test fun learningThatLeavesNoChargeGivesOneReasonNotTwo() {
        val learning = status("battery_control/checking.json").let {
            it.copy(
                allowedActions = listOf("grid_charge"),
                gridChargeCap = it.gridChargeCap.copy(rule = "learning", capKw = 0.0, capLearnedKw = 3.0, daysLeft = 1)
            )
        }
        assertEquals("No charging from the grid while the house's peaks are being learned (1 day left).", text().gridChargeCap(learning))
        val none = learning.copy(gridChargeCap = learning.gridChargeCap.copy(capLearnedKw = 0.0))
        assertEquals(
            "No charging from the grid: the house can reach the fuse by itself (learned per phase).",
            text().gridChargeCap(none)
        )
    }

    @Test fun theOneLineUnderTheSwitchIsWordedFromItsCode() {
        val en = text()
        assertEquals("Off: SpotNav controls nothing yet and only shows what it would do.", en.batteryLine("off"))
        assertEquals("On: SpotNav runs the battery.", en.batteryLine("on"))
        assertEquals(
            "Waiting for a suitable moment for the check. It waits until the car stops charging.",
            en.batteryLine("waiting:car_charging")
        )
        assertEquals(
            "The check failed: “Charge from the grid” did not work. Turn the switch off and on to check again. It gave up after five tries.",
            en.batteryLine("failed:charge:attempts")
        )
        assertEquals("Stopped for today: the day's writes are used up.", en.batteryLine("blocked:daily_write_cap"))
        // A code from a newer Home Assistant is shown as it is rather than hidden.
        assertEquals("brand_new:thing", en.batteryLine("brand_new:thing"))
    }

    @Test fun theControlWordIsOnOffOrTest() {
        assertEquals(listOf("Off", "Test", "On"), BatteryStatus.Control.entries.map { text().controlWord(it) })
        assertEquals("Test", text("sv").controlWord(BatteryStatus.Control.TEST))
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
            "battery.title", "battery.modeLabel", "battery.modeShadow", "battery.switch", "battery.line.off", "battery.line.on",
            "battery.cap.line", "battery.cap.zero", "battery.failed", "battery.error.generic", "settings.value.on",
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
