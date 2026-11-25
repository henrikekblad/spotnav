package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.heat.HeatHistoryRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The Förbrukning box as the card words it (the card's `heat-tabs.test.ts` "Use" cases, on the same fixtures). */
class HeatUseModelTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun history(name: String): HeatHistory = (HeatHistoryRead.decode(HaFixtures.json("heat_history/$name")) as SiteRead.Read).value

    private val month get() = history("zones_month.json")

    @Test fun theHeadlineIsWhatSpotNavSavedWithTheTotalUnderIt() {
        val h = HeatUseModel.headline(text(), month, emptySet(), false)
        assertEquals("SpotNav sparade 4,57 kr på värmen", h.head)
        assertEquals("Värme 665 kWh", h.sub)
        assertTrue(h.saved)
        // Without a saving the total is the headline.
        val none = HeatUseModel.headline(text(), month.copy(saving = null), emptySet(), false)
        assertEquals("Värme 665 kWh", none.head)
        assertNull(none.sub)
        assertFalse(none.saved)
    }

    @Test fun anEstimatedPartIsNamedSoAndWhatTheHouseHadNoRoomForIsSaidOnce() {
        val json = HaFixtures.json("heat_history/zones_month.json").apply {
            val parts = getJSONArray("parts")
            parts.getJSONObject(parts.length() - 1).put("kind", "spa").put("estimated", true).put("clamped_kwh", 2.5)
        }
        val answer = (HeatHistoryRead.decode(json) as SiteRead.Read).value
        assertTrue(answer.parts.last().estimated)
        assertEquals("Spa (uppskattat) 18,7 kWh", HeatUseModel.series(text(), answer, false) { 0 }.last().label)
        assertEquals("Uppskattade delar begränsade till det som mättes: 2,5 kWh av uppskattningen ligger kvar i huset.", HeatUseModel.notes(text(), answer, false).last())
        // A Home Assistant before the marks: nothing estimated, nothing left out.
        assertFalse(month.parts.any { it.estimated })
        assertTrue(HeatUseModel.notes(text(), month, false).none { it.startsWith("Uppskattade") })
        assertTrue(HeatHistoryRead.decode(HaFixtures.json("heat_history/zones_month.json").apply { getJSONArray("parts").getJSONObject(0).put("estimated", "yes") }) is SiteRead.Failed)
    }

    @Test fun thePartsAreTogglesWithTheirSumsAndEachZoneHasItsOwnColour() {
        val series = HeatUseModel.series(text(), month, false) { it.hashCode() }
        assertEquals(listOf("Värmepump 581 kWh", "Varmvatten 16,8 kWh", "Ovanvåning 48,7 kWh", "Övrig värme 18,7 kWh"), series.map { it.label })
        assertEquals(listOf("pump", "hot_water", "zone-0", "other"), month.parts.map { HeatUseModel.slot(month.parts, it) })
        val zones = (0..4).map { HeatHistory.Part("zone:$it", "zone", "Zon $it", 1.0, null) }
        assertEquals(listOf("zone-0", "zone-1", "zone-2", "zone-3", "zone-0"), zones.map { HeatUseModel.slot(zones, it) })
    }

    @Test fun aBarPerDaySaysItsPartsAndTheDaysSaving() {
        val bars = HeatUseModel.bars(text(), Locale.forLanguageTag("sv"), month, emptySet(), false)
        assertEquals(30, bars.size)
        val day = bars.first { it.key == "2026-11-13" }
        assertEquals(listOf("pump", "hot_water", "zone:climate.ovanvaning", "other"), day.lanes.single().map { it.series })
        assertTrue(day.words, Regex("^fre 13 nov\\.?: 4\\d,\\d kWh · Värmepump 3\\d,\\d kWh · Varmvatten 2,4 kWh · Ovanvåning 3,\\d kWh · Övrig värme 1,2 kWh · SpotNav sparade 2,12 kr$").matches(day.words))
        // A day ahead has nothing to show.
        val ahead = bars.first { it.key == "2026-11-20" }
        assertTrue(ahead.lanes.isEmpty())
        assertTrue(ahead.words, ahead.words.endsWith(": ingen mätaravläsning"))
        // A day has an hour per bar, the hot water in the hours it ran.
        val hours = HeatUseModel.bars(text(), Locale.forLanguageTag("sv"), history("zones_day.json"), emptySet(), false)
        assertEquals(24, hours.size)
        assertEquals(3, hours.count { bar -> bar.lanes.any { lane -> lane.any { it.series == "hot_water" } } })
    }

    @Test fun aHiddenPartLeavesTheBarsAndTheTotalButStaysAToggle() {
        val hidden = setOf("pump", "gone")
        assertEquals("Värme 84,2 kWh", HeatUseModel.headline(text(), month, hidden, false).sub)
        val bars = HeatUseModel.bars(text(), Locale.forLanguageTag("sv"), month, hidden, false)
        assertEquals(4, HeatUseModel.series(text(), month, false) { 0 }.size)
        val all = month.parts.map { it.key }.toSet()
        assertEquals("Ingen del vald.", HeatUseModel.headline(text(), month.copy(saving = null), all, false).head)
        assertNull(HeatUseModel.total(month, all, false))
    }

    @Test fun theUnitSwitchesToMoneyOnlyWhereSomePartIsPriced() {
        assertTrue(HeatUseModel.hasMoney(month))
        val money = HeatUseModel.headline(text(), month.copy(saving = null), emptySet(), true)
        assertEquals("Värme 982 kr", money.head)
        assertEquals("Varmvatten 22 kr", HeatUseModel.series(text(), month, true) { 0 }[1].label)
        val bar = HeatUseModel.bars(text(), Locale.forLanguageTag("sv"), month, emptySet(), true).first { it.key == "2026-11-13" }
        assertTrue(bar.words, Regex(": \\d\\d kr · Värmepump \\d\\d kr · Varmvatten \\d,\\d\\d kr · ").containsMatchIn(bar.words))
        assertTrue(HeatUseModel.notes(text(), month, true).contains("Kostnader är till sparade priser med dagens avgifter."))
        val unpriced = month.copy(parts = month.parts.map { it.copy(cost = null) })
        assertFalse(HeatUseModel.hasMoney(unpriced))
        assertFalse(HeatUseModel.inMoney(unpriced, true))
        assertEquals("1,50 kr", HeatUseModel.money(text(), 150.0, month.money))
        assertEquals("12 kr", HeatUseModel.money(text(), 1200.0, month.money))
        assertEquals("150 öre", HeatUseModel.money(text(), 150.0, month.money!!.copy(majorUnit = null)))
    }

    @Test fun theNotesSayWhichDaysTheHotWaterIsSplitOutFor() {
        assertEquals(listOf("Varmvatten är utbrutet för 7 av 16 dygn: de dygn Home Assistants historik ännu har i 5-minutersdetalj."), HeatUseModel.notes(text(), month, false))
        assertEquals(emptyList<String>(), HeatUseModel.notes(text(), history("zones_day.json"), false))
    }

    @Test fun aPeriodWithoutAMeterOrEnergyIsLeftOut() {
        assertEquals(HeatUseContent.Ready, HeatUseModel.content(month))
        assertEquals(HeatUseContent.NoMeter, HeatUseModel.content(month.copy(meter = null)))
        assertEquals(HeatUseContent.NoEnergy, HeatUseModel.content(month.copy(parts = emptyList())))
        assertEquals(HeatUseContent.NoEnergy, HeatUseModel.content(month.copy(energy = null)))
    }

    @Test fun everyWordIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val t = text(language)
            val locale = Locale.forLanguageTag(language)
            for (name in listOf("zones_day.json", "zones_month.json", "zones_year.json", "meter_only_year.json")) {
                val h = history(name)
                for (money in listOf(false, true)) {
                    val head = HeatUseModel.headline(t, h, emptySet(), money)
                    val said = listOfNotNull(head.head, head.sub) + HeatUseModel.notes(t, h, money) +
                        HeatUseModel.series(t, h, money) { 0 }.map { it.label } + HeatUseModel.bars(t, locale, h, emptySet(), money).map { it.words } +
                        HeatUseModel.headline(t, h, h.parts.map { it.key }.toSet(), money).head
                    for (line in said) assertFalse("$language $name: $line", line.contains("heat.") || line.contains("hist.") || line.contains("house.") || line.contains("{"))
                }
            }
        }
    }
}
