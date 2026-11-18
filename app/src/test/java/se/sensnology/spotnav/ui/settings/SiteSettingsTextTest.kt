package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.site.BatteryControlRead
import se.sensnology.spotnav.ha.site.BatteryStatus
import se.sensnology.spotnav.ha.site.HeatNight
import se.sensnology.spotnav.ha.site.HeatSettingsUpdate
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
        assertEquals("The import limit protects the fuse when charging from the grid.", text().gridChargeCap(status("battery_control/needs_ack.json")))
        // Charging from the grid not allowed: no line.
        assertNull(text().gridChargeCap(status("battery_control/live.json")))
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
        assertEquals(SiteSettingsText.Issue.CONFLICT, en.heatIssue(HeatSettingsUpdate.Outcome.Conflict))
        assertEquals(SiteSettingsText.Issue.NOT_PERMITTED, en.heatIssue(HeatSettingsUpdate.Outcome.NotPermitted))
        assertEquals(SiteSettingsText.Issue.RANGE, en.heatIssue(HeatSettingsUpdate.Outcome.Refused(listOf("out_of_range"))))
        assertEquals(SiteSettingsText.Issue.STEP, en.heatIssue(HeatSettingsUpdate.Outcome.Refused(listOf("not_a_step"))))
        assertEquals(SiteSettingsText.Issue.GENERIC, en.heatIssue(HeatSettingsUpdate.Outcome.Refused(listOf("unknown_field"))))
        assertNull(en.heatIssue(HeatSettingsUpdate.Outcome.Updated))
        assertEquals(SiteSettingsText.Issue.NOT_PERMITTED, en.batteryIssue(BatteryControlRead.Outcome.NotPermitted))
        assertEquals(SiteSettingsText.Issue.GENERIC, en.batteryIssue(BatteryControlRead.Outcome.Invalid))
        assertEquals("Someone else changed it meanwhile; showing the latest.", en.cardSentence(SiteSettingsText.Issue.CONFLICT, battery = false))
        assertEquals("That did not work. Try again.", en.cardSentence(SiteSettingsText.Issue.GENERIC, battery = true))
        // Not permitted has no card sentence: the screen says it in its own words.
        assertNull(en.cardSentence(SiteSettingsText.Issue.NOT_PERMITTED, battery = false))
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
