package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.HouseHistoryRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The Intäkt box's figures and words on the integration's fixtures, as the card's `house-earnings` tests have them. */
class HouseEarnTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun history(name: String): HouseHistory =
        (HouseHistoryRead.decode(HaFixtures.json("house_history/$name")) as SiteRead.Read).value

    /** The same period as if SpotNav had controlled the battery: its own part becomes SpotNav's. */
    private fun controlled(answer: HouseHistory): HouseHistory {
        val own = answer.ownControl!!
        return answer.copy(
            ownControl = null,
            saving = answer.saving!!.copy(battery = own.earned, batteryDays = own.days, total = answer.saving!!.total + own.earned),
            buckets = answer.buckets.map { it.copy(savingBattery = it.ownBattery, ownBattery = null, wouldBattery = null) }
        )
    }

    private fun lanes(bar: Bar): List<Pair<List<String>, Int>> =
        bar.lanes.mapIndexed { i, lane -> lane.map { it.series } to (bar.slots?.get(i) ?: i) }

    private fun bars(h: HouseHistory, hidden: Set<String> = emptySet(), language: String = "sv") =
        HouseEarn.bars(text(language), Locale.forLanguageTag(language), h, hidden, h.money)

    @Test fun aPeriodHasOnlyItsPartsEachWithItsSumAndAHeadlineOfThePartsDrawn() {
        val month = history("villa_month.json")
        // The battery under its own control: its own part and the plan's would-be figure, no arbitrage of SpotNav's.
        assertEquals(listOf(EarnPart.OWN, EarnPart.HEAT, EarnPart.CAR, EarnPart.WOULD), HouseEarn.parts(month))
        assertEquals(2625.0, HouseEarn.partTotal(month, EarnPart.OWN)!!, 0.0)
        assertEquals(3225.0, HouseEarn.partTotal(month, EarnPart.WOULD)!!, 0.0)
        assertEquals(1960.0, HouseEarn.partTotal(month, EarnPart.CAR)!!, 0.0)
        assertEquals(120.0, HouseEarn.partTotal(month, EarnPart.HEAT)!!, 0.0)
        assertNull(HouseEarn.partTotal(month, EarnPart.BATTERY))
        // The headline never counts what the plan would have earned: nothing was done.
        assertEquals(2625.0 + 120 + 1960, HouseEarn.total(month, emptySet())!!, 0.0)
        assertEquals(2080.0, HouseEarn.total(month, setOf("own"))!!, 0.0)
        assertNull(HouseEarn.total(month, setOf("own", "heat", "car")))
        val own = controlled(month)
        assertEquals(listOf(EarnPart.BATTERY, EarnPart.HEAT, EarnPart.CAR), HouseEarn.parts(own))
        assertEquals(4705.0, HouseEarn.total(own, emptySet())!!, 0.0)
    }

    @Test fun anAnswerFromAHomeAssistantBeforeThePartsHasNothingToShowAndANonsenseOneIsRefused() {
        val raw = HaFixtures.json("house_history/villa_month.json")
        raw.remove("own_control")
        raw.remove("saving")
        val buckets = raw.getJSONArray("buckets")
        for (i in 0 until buckets.length()) {
            for (key in listOf("own_battery", "would_battery", "saving", "saving_battery", "saving_car", "saving_heat")) buckets.getJSONObject(i).remove(key)
        }
        val old = (HouseHistoryRead.decode(raw) as SiteRead.Read).value
        assertNull(old.ownControl)
        assertTrue(old.buckets.all { it.ownBattery == null && it.wouldBattery == null })
        assertEquals(emptyList<EarnPart>(), HouseEarn.parts(old))
        val bad = HaFixtures.json("house_history/villa_month.json").put("own_control", org.json.JSONObject().put("earned", "much"))
        assertTrue(HouseHistoryRead.decode(bad) is SiteRead.Failed)
    }

    @Test fun aMonthStacksThePartsPerDayWithThePlansFigureBesideTheBatterysOwn() {
        val month = history("villa_month.json")
        val all = bars(month)!!
        assertEquals(30, all.size)
        // The 12th: the battery's own, the heat and the car in one stack (bottom up), the plan's figure beside it.
        val day = all.first { it.key == "2026-11-12" }
        assertEquals(listOf(listOf("own", "heat", "car") to 0, listOf("would") to 1), lanes(day))
        assertEquals(2, day.slotCount)
        assertTrue(day.words, day.words.startsWith("tors 12 nov"))
        assertTrue(day.words, day.words.contains("urladdat 5,0 kWh, värt 15 kr, minus 11 kr"))
        assertTrue(day.words, day.words.endsWith(" · Värme 0,60 kr · Bil 2,70 kr · SpotNav skulle ha sparat 4,75 kr"))
        // Today (the 16th) is not reviewed yet: no would-be lane; a day before any figure says so.
        assertEquals(listOf(listOf("own", "car")), lanes(all.first { it.key == "2026-11-16" }).map { it.first })
        assertEquals("sön 1 nov: ingen siffra", all[0].words)
        assertTrue(all[0].lanes.isEmpty())
        // A part hidden leaves the stack; with the plan's figure hidden the stack takes the bar's whole width.
        val fewer = bars(month, setOf("would", "heat"))!!.first { it.key == "2026-11-12" }
        assertEquals(listOf(listOf("own", "car") to 0), lanes(fewer))
        assertEquals(1, fewer.slotCount)
        // Under SpotNav's control: its arbitrage at the bottom of the stack, no lane beside it.
        val own = bars(controlled(month))!!.first { it.key == "2026-11-12" }
        assertEquals(listOf(listOf("battery", "heat", "car") to 0), lanes(own))
        // Nothing drawn: no bars.
        assertNull(bars(month, setOf("own", "heat", "car", "would")))
    }

    @Test fun aDaysPartsAreInTheirHoursAndALossIsBelowTheLine() {
        val day = history("villa_day.json")
        assertEquals(300.0, HouseEarn.bucketPart(day.buckets[22], EarnPart.CAR)!!, 0.0)
        val all = bars(day)!!
        assertEquals(24, all.size)
        assertEquals(listOf(listOf("car") to 0), lanes(all[22]))
        assertEquals("22:00: Bil 3,00 kr", all[22].words)
        assertEquals(listOf(listOf("own") to 0), lanes(all[17]))
        // 07:00: the battery gave back below what it was stored at: a negative part, below the line.
        assertTrue(all[7].lanes.single().single().value < 0)
        val extent = BarsLayout.extent(all, emptySet())
        assertTrue(extent.bottom > 0 && extent.top > 0)
        // One hour with a gain and a loss: a stack each side of the line, in the same place.
        val mixed = day.copy(buckets = day.buckets.mapIndexed { i, b -> if (i == 22) b.copy(ownBattery = -40.0) else b })
        val both = bars(mixed)!![22]
        assertEquals(listOf(listOf("car") to 0, listOf("own") to 0), lanes(both))
        assertEquals(-40.0, both.lanes[1].single().value, 0.0)
        // A hatched series' rectangles are laid out like any other.
        val rects = BarsLayout.rects(listOf(both), emptySet(), 100f, 100f, 0f)
        assertEquals(2, rects.size)
        assertTrue(rects.first { it.series == "own" }.top > rects.first { it.series == "car" }.top)
    }

    @Test fun theLegendHasEachPartWithItsSumAndThePlansFigureHatched() {
        val month = history("villa_month.json")
        val legend = HouseEarn.legend(month, text(), month.money)
        assertEquals(
            listOf("Batteri, egen styrning 26 kr", "Värme 1,20 kr", "Bil 20 kr", "SpotNav skulle ha sparat 32 kr"),
            legend.map { it.second }
        )
        assertEquals(EarnPart.WOULD, legend.last().first)
        // A day's hours have the plan's figure in words alone: no toggle for it.
        val day = history("villa_day.json")
        assertFalse(HouseEarn.legend(day, text(), day.money).any { it.first == EarnPart.WOULD })
        assertTrue(HouseEarn.notes(text(), day, emptySet(), day.money).any { it.startsWith("På batteriet skulle SpotNav ha sparat 4,75 kr") })
    }

    @Test fun theNotesSayWhatEachFigureCounts() {
        val month = history("villa_month.json")
        assertEquals(
            "Vad SpotNav skulle ha sparat är känt för 6 av 7 dagar; övriga räknas som de gick. " +
                "Bilens besparing räknas på dagen då laddningen startade. Värmens besparing är en uppskattning.",
            HouseEarn.notes(text(), month, emptySet(), month.money).joinToString(" ")
        )
        assertFalse(HouseEarn.notes(text(), month, setOf("car"), month.money).joinToString(" ").contains("Bilens"))
        val day = history("villa_day.json").let { it.copy(saving = it.saving!!.copy(heat = 60.0, heatDays = 1, total = 360.0)) }
        assertEquals(
            "På batteriet skulle SpotNav ha sparat 4,75 kr den här dagen. Bilens besparing ligger på timmen då laddningen startade. " +
                "Värmens besparing är en uppskattning för hela dagen och ritas inte per timme.",
            HouseEarn.notes(text(), day, emptySet(), day.money).joinToString(" ")
        )
        // The running day is reviewed tonight; a period with no day reviewed says it is not known.
        val running = day.copy(today = day.date, ownControl = day.ownControl!!.copy(would = null, wouldDays = 0))
        assertEquals("Vad SpotNav skulle ha sparat på batteriet räknas ut i natt.", HouseEarn.notes(text(), running, emptySet(), day.money)[0])
        val past = running.copy(today = day.date.plusDays(5))
        assertEquals("Vad SpotNav skulle ha sparat på batteriet är inte känt för perioden.", HouseEarn.notes(text(), past, emptySet(), day.money)[0])
        // An older day: its hours are no longer kept, its sums are.
        val old = day.copy(buckets = day.buckets.map { it.copy(saving = null, savingCar = null, ownBattery = null) }, today = day.date.plusDays(5))
        assertNull(bars(old))
        assertEquals(735.0, HouseEarn.total(old, emptySet())!!, 0.0)
        assertTrue(HouseEarn.notes(text(), old, emptySet(), day.money)[0].startsWith("Den här dagen saknar timvärden (de sparas i 40 dagar)."))
    }

    @Test fun everyWordOfTheBoxIsInAllNineLanguages() {
        val month = history("villa_month.json")
        val day = history("villa_day.json")
        for (language in CardStringsXml.LANGUAGES) {
            val t = text(language)
            val said = buildList {
                add(t.words.say("house.tab.earn"))
                add(t.words.say("house.earn.total", "amount" to "1 kr"))
                add(t.words.say("house.earn.noneShown"))
                add(t.words.say("house.earn.label"))
                HouseEarn.legend(month, t, month.money).forEach { add(it.second) }
                bars(month, language = language)!!.forEach { add(it.words) }
                bars(day, language = language)!!.forEach { add(it.words) }
                add(HouseEarn.notes(t, month, emptySet(), month.money).joinToString(" "))
                add(HouseEarn.notes(t, day, emptySet(), day.money).joinToString(" "))
            }
            // A missing key throws in CardStringsXml; an unfilled placeholder is a word left in braces.
            said.forEach { assertTrue("$language: $it", it.isNotBlank() && !it.contains("{")) }
        }
    }
}
