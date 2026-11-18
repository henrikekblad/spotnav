package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistoryRead
import se.sensnology.spotnav.ha.house.HouseLoads
import se.sensnology.spotnav.ha.house.HouseLoadsRead
import se.sensnology.spotnav.ha.house.HouseRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/**
 * The Energy flow tab apart from the views: the card's own cases for the Sankey layout, the tiles and the pie, and
 * which boxes a house page has.
 */
class FlowBoxTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun house(name: String = "villa_battery_sun.json") = (HouseRead.decode(HaFixtures.json("house/$name")) as SiteRead.Read).value

    private fun flow() = house().energyFlow!!

    private fun node(id: String, source: Boolean, role: String, kwh: Double) = House.FlowNode(id, source, role, kwh, null)

    private fun loads(name: String) = (HouseLoadsRead.decode(HaFixtures.json("house_loads/$name")) as SiteRead.Read).value

    private fun history(name: String) = (HouseHistoryRead.decode(HaFixtures.json("house_history/$name")) as SiteRead.Read).value

    private fun layout(flow: House.EnergyFlow, measure: (String) -> Double? = { null }) =
        SankeyLayout.layout(flow, 328.0, null, { FlowModel.pillWords(text(), it, 100.0) }, measure)

    // --- the Sankey layout (house-card.test.ts, "today's energy flow") ---

    @Test fun theDiagramGrowsWithTheNumberOfBlocks() {
        assertEquals(250, SankeyLayout.flowHeight(328.0, 3))
        assertEquals(320, SankeyLayout.flowHeight(328.0, 4))
        assertEquals(440, SankeyLayout.flowHeight(328.0, 9))
    }

    @Test fun everyBlockIsTallEnoughForItsWords() {
        val heights = SankeyLayout.blockHeights(listOf(7.5, 8.6, 76.9), 300.0)
        assertTrue(heights.min() >= SankeyLayout.MIN_NODE)
        assertEquals(300.0, heights.sum() + 12, 1e-6)
        assertEquals(listOf(44.0, 44.0), SankeyLayout.blockHeights(listOf(0.0, 0.0), 100.0))
    }

    @Test fun aColumnIsAsWideAsItsWidestPillNeedsFromTheBands() {
        val (usualLeft, usualRight) = SankeyLayout.columnWidths(328.0, 40.0, 40.0)
        assertEquals(usualLeft, usualRight, 0.0)
        val (left, right) = SankeyLayout.columnWidths(328.0, 40.0, 96.0)
        assertEquals(usualLeft, left, 0.0)
        assertEquals(96 + 2 * SankeyLayout.PILL_PAD + 12, right, 0.0)
        // Two very wide columns give way in proportion, never below the usual one.
        val (tightLeft, tightRight) = SankeyLayout.columnWidths(328.0, 160.0, 160.0)
        assertTrue(tightLeft + tightRight <= 328 * 0.78)
        assertTrue(tightLeft >= usualLeft)
    }

    @Test fun theColumnsAreAsWideAsTheWidestPillAsMeasured() {
        val measure = { words: String -> words.length * 7.0 }
        val laid = layout(flow(), measure)!!
        for (block in laid.blocks) {
            assertTrue(laid.pillWidths.getValue(block.node.id) + 2 * SankeyLayout.PILL_PAD <= block.w + 0.01)
        }
        // Each pill says its name and its share, in the share's own form.
        val grid = FlowModel.pillWords(text(), flow().nodes.first { it.id == "source.grid" }, flow().nodes.filter { it.source }.sumOf { it.kwh })
        assertEquals("Nät · 83 %", grid)
        val widest = laid.uses.maxOf { laid.pillWidths.getValue(it.node.id) }
        assertEquals(widest + 2 * SankeyLayout.PILL_PAD + 12, laid.uses[0].w, 0.0)
        assertEquals(328 - laid.uses[0].w, laid.uses[0].x, 0.0)
        // Without measuring, the words are estimated and the diagram is still drawn.
        assertNotNull(layout(flow()))
    }

    @Test fun sourcesAreLeftUsesRightAndAllBlocksAreDrawnInTheAnswersOrder() {
        val laid = layout(flow())!!
        assertEquals(listOf("solar", "battery", "grid"), laid.sources.map { it.node.role })
        assertEquals(listOf("battery", "house", "heat", "car", "grid"), laid.uses.map { it.node.role })
        assertEquals(flow().links.size, laid.bands.size)
    }

    @Test fun aUseWithNoEnergyIsLeftOutAndNothingIsDrawnWithoutASource() {
        val f = flow()
        val noCar = f.copy(nodes = f.nodes.map { if (it.role == "car") it.copy(kwh = 0.0) else it }, links = f.links.filter { it.to != "use.car" })
        assertFalse("car" in layout(noCar)!!.uses.map { it.node.role })
        val none = f.copy(nodes = f.nodes.filter { !it.source })
        assertNull(layout(none))
        assertFalse(SankeyLayout.drawable(none))
    }

    @Test fun noBlockWithoutABandAndNoBandWithoutWidth() {
        val f = house().energyFlow!!.copy(
            nodes = listOf(
                node("source.battery", true, "battery", 6.0), node("source.grid", true, "grid", 0.06),
                node("use.house", false, "house", 5.99), node("use.heat", false, "heat", 0.04), node("use.grid", false, "grid", 0.03)
            ),
            links = listOf(
                House.FlowLink("source.battery", "use.house", 5.98), House.FlowLink("source.battery", "use.grid", 0.01),
                House.FlowLink("source.battery", "use.heat", 0.01), House.FlowLink("source.grid", "use.heat", 0.03),
                House.FlowLink("source.grid", "use.house", 0.01)
            )
        )
        val laid = layout(f)!!
        assertEquals(listOf("battery", "grid", "house"), laid.blocks.map { it.node.role })
        assertEquals(listOf("battery>house", "grid>house"), laid.bands.map { "${it.from.node.role}>${it.to.node.role}" })
        for (band in laid.bands) assertTrue(band.a1 - band.a0 >= SankeyLayout.MIN_BAND - 0.1)
        val ends = laid.bands.flatMap { listOf("s:" + it.from.node.id, "u:" + it.to.node.id) }.toSet()
        assertTrue(laid.sources.all { "s:" + it.node.id in ends } && laid.uses.all { "u:" + it.node.id in ends })
    }

    @Test fun aBandIsAsThickAtBothEndsAndTheBlocksHoldTheirBandsCentred() {
        val laid = layout(flow())!!
        val scale = laid.blocks.filter { it.node.kwh > 0 }.minOf { it.h / it.node.kwh }
        for (band in laid.bands) {
            assertEquals(band.a1 - band.a0, band.b1 - band.b0, 1e-9)
            assertEquals(maxOf(SankeyLayout.MIN_BAND, band.link.kwh * scale), band.a1 - band.a0, 1e-9)
            assertTrue(band.a0 >= band.from.y - 1e-9 && band.a1 <= band.from.y + band.from.h + 1e-9)
        }
    }

    @Test fun aMonthsFiguresStayNarrow() {
        assertEquals("99,9 kWh", FlowModel.kwh(text(), 99.94))
        assertEquals("183 kWh", FlowModel.kwh(text(), 183.4))
        val en = text("en")
        assertEquals("2037 kWh", FlowModel.kwh(en, 2037.0))
        assertEquals("Heat", FlowModel.role(en, "heat"))
        // A role the card does not know is named as it comes.
        assertEquals("pool", FlowModel.role(en, "pool"))
    }

    // --- tiles: only without a diagram, never 0 kWh ---

    @Test fun tilesCarryTheFiguresOnlyWhereThereIsNoDiagramAndNeverOneOfZero() {
        val f = flow()
        val early = f.copy(
            nodes = f.nodes.map { it.copy(kwh = if (it.role == "house") 0.3 else if (it.role == "grid" && it.source) 0.3 else if (it.role == "heat") 0.04 else 0.0) },
            links = emptyList()
        )
        val content = FlowModel.content(PeriodChoice(HistoryPeriod.DAY), house().let { it.copy(energyFlow = early) }, null, null)
        assertEquals(FlowContent.Tiles(waiting = true, tiles = FlowModel.tiles(early)), content)
        assertEquals(listOf("house", "bought"), FlowModel.tiles(early).map { it.key })
        val nothing = f.copy(nodes = f.nodes.map { it.copy(kwh = 0.0) }, links = emptyList())
        assertTrue(FlowModel.tiles(nothing).isEmpty())
        assertTrue(FlowModel.tiles(f).all { it.kwh >= FlowModel.MIN_TILE_KWH })
        // With a diagram there are no tiles.
        assertTrue(FlowModel.content(PeriodChoice(HistoryPeriod.DAY), house(), null, null) is FlowContent.Diagram)
    }

    @Test fun whatTheTabShowsForEachPeriodAndState() {
        val day = PeriodChoice(HistoryPeriod.DAY)
        val month = PeriodChoice(HistoryPeriod.MONTH)
        assertEquals(FlowContent.Loading, FlowModel.content(day, null, null, null))
        assertEquals(FlowContent.Loading, FlowModel.content(month, house(), null, null))
        assertEquals(FlowContent.Failed(false), FlowModel.content(month, house(), null, FlowModel.FlowFailure("spotnav_read_failed")))
        assertEquals(FlowContent.Failed(true), FlowModel.content(month, house(), null, FlowModel.FlowFailure("unknown_command")))
        assertEquals(FlowContent.Failed(true), FlowModel.content(month, house(), null, FlowModel.FlowFailure(null, unsupportedVersion = true)))
        // Today's own flow is the house's answer; a month is its history, a past day's battery level is the one at its end.
        val shown = FlowModel.content(month, house(), history("villa_month.json"), null) as FlowContent.Diagram
        assertTrue(shown.past)
        assertFalse((FlowModel.content(day, house(), null, null) as FlowContent.Diagram).past)
        assertTrue((FlowModel.content(PeriodChoice(HistoryPeriod.DAY, java.time.LocalDate.parse("2026-11-15")), house(), history("villa_day.json"), null) as FlowContent.Diagram).past)
        assertEquals(FlowContent.NoMeters, FlowModel.content(day, house().copy(energyFlow = null), null, null))
        // A past day with nothing flowing says none was measured, not that it waits.
        val empty = history("villa_day.json").let { it.copy(energyFlow = it.energyFlow!!.copy(links = emptyList())) }
        assertEquals(FlowContent.Tiles(false, FlowModel.tiles(empty.energyFlow!!)), FlowModel.content(PeriodChoice(HistoryPeriod.DAY, java.time.LocalDate.parse("2026-11-15")), house(), empty, null))
    }

    @Test fun theHouseBlockOpensItsPieAndTheHeatBlockOnlyWithMoreThanOneSource() {
        val f = flow()
        fun opens(role: String, flow: House.EnergyFlow = f) = FlowModel.opens(flow, flow.nodes.first { !it.source && it.role == role })
        assertEquals("house", opens("house"))
        assertNull(opens("heat"))
        assertEquals("heat", opens("heat", f.copy(heatSources = 3)))
        assertNull(opens("car"))
        assertNull(FlowModel.opens(f, f.nodes.first { it.source && it.role == "grid" }))
    }

    // --- the pie (house-loads.test.ts) ---

    @Test fun thePieDrawsTheMetersOver1_5PercentAndPutsTheRestInOtherAndUnknown() {
        val words = CardStringsXml.words("en")
        val pie = LoadsModel.pie(words, loads("villa_day.json"))
        assertEquals(14.62, pie.whole, 0.0)
        assertEquals(listOf("Torktumlare", "Diskmaskin", "Tvättmaskin", "Other metered", "Unknown"), pie.slices.map { it.name })
        assertEquals(listOf("Frys", "Router"), pie.merged.map { it.name })
        assertEquals(0.21 + 0.12, pie.slices[3].kwh, 1e-9)
        assertEquals(9.6, pie.slices[4].kwh, 1e-9)
        assertEquals(14.62, pie.slices.sumOf { it.kwh }, 1e-9)
        // A meter's colour follows its statistic id, not its size.
        assertEquals(1, pie.slices[0].colour)
        assertEquals(LoadsModel.OTHER_COLOUR, pie.slices[3].colour)
        assertEquals(LoadsModel.UNKNOWN_COLOUR, pie.slices[4].colour)
        val sv = CardStringsXml.words("sv")
        assertEquals(listOf("Övrigt uppmätt", "Okänt"), LoadsModel.pie(sv, loads("villa_day.json")).slices.takeLast(2).map { it.name })
    }

    private fun meters(kwhs: List<Double>, house: Double): HouseLoads {
        val base = loads("villa_day.json")
        val list = kwhs.mapIndexed { i, kwh -> HouseLoads.Load("sensor.m$i", "M$i", kwh, "meter", false) }
        return base.copy(houseKwh = house, metersKwh = kwhs.sum(), otherKwh = maxOf(0.0, house - kwhs.sum()), loads = list)
    }

    @Test fun theLineIsAt1_5PercentOfTheWhole() {
        val pie = LoadsModel.pie(CardStringsXml.words("en"), meters(listOf(1.5, 1.49, 0.0), 100.0))
        assertEquals(listOf("M0", "Other metered", "Unknown"), pie.slices.map { it.name })
        assertEquals(listOf("M1", "M2"), pie.merged.map { it.name })
    }

    @Test fun atMostEightMetersAreNamed() {
        val pie = LoadsModel.pie(CardStringsXml.words("en"), meters(listOf(10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.5, 2.0), 100.0))
        assertEquals(8, pie.slices.count { it.key.startsWith("sensor.") })
        assertEquals(listOf("M8", "M9"), pie.merged.map { it.name })
        assertEquals(4.5, pie.slices.first { it.key == "other-metered" }.kwh, 1e-9)
        assertEquals(pie.slices.size, pie.slices.map { it.colour }.toSet().size)
    }

    @Test fun unknownIsClampedAtZeroWhenTheMetersExceedTheHouse() {
        val answer = loads("meters_over_house.json")
        val pie = LoadsModel.pie(CardStringsXml.words("en"), answer)
        assertEquals(43.5, pie.whole, 0.0)
        assertFalse("unknown" in pie.slices.map { it.key })
        val t = text("en")
        val notes = LoadsModel.notes(t, answer, pie, carApart = true, heatApart = false)
        assertEquals(listOf(t.words.say("house.loads.over", "kwh" to "3.5 kWh"), t.words.say("house.loads.car")), notes)
    }

    @Test fun unknownAloneAndWhereToAddMetersWhenThereAreNone() {
        val answer = loads("no_devices.json")
        val t = text("en")
        val pie = LoadsModel.pie(t.words, answer)
        assertEquals(listOf("unknown"), pie.slices.map { it.key })
        assertEquals(listOf(t.words.say("house.loads.none")), LoadsModel.notes(t, answer, pie, carApart = false, heatApart = false))
    }

    @Test fun theHeatPieShowsEverySourceWithEnergyHoweverSmall() {
        val answer = loads("heat_villa_day.json")
        val pie = LoadsModel.pie(CardStringsXml.words("en"), answer)
        assertEquals(4, pie.slices.size)
        assertTrue(pie.merged.isEmpty())
        // The heat pie says nothing of the car or the heat being apart, nor that no devices are metered.
        assertTrue(LoadsModel.notes(text("en"), answer, pie, carApart = true, heatApart = true).isEmpty())
    }

    @Test fun figuresAreWorded() {
        val t = text("en")
        assertEquals("0.21 kWh", LoadsModel.kwh(t, 0.21))
        assertEquals("14.6 kWh", LoadsModel.kwh(t, 14.62))
        assertEquals("1030 kWh", LoadsModel.kwh(t, 1030.0))
        assertEquals("<1 %", LoadsModel.share(t, 0.003))
        assertEquals("16 %", LoadsModel.share(t, 0.158))
        assertEquals("0 %", LoadsModel.share(t, 0.0))
        val moved = loads("moved_and_implausible_month.json")
        val notes = LoadsModel.notes(t, moved, LoadsModel.pie(t.words, moved), false, false)
        assertTrue(notes.first().contains("NAS") && notes.first().contains("12"))
    }

    @Test fun theTabsAreWordedInEveryLanguage() {
        val keys = listOf(
            "house.flow.solar", "house.flow.battery", "house.flow.grid", "house.flow.house", "house.flow.car", "house.flow.heat", "house.flow.socNow",
            "house.flow.socEnd", "house.flow.waiting", "house.loads.title", "house.loads.titleHeat", "house.loads.open", "house.loads.openHeat", "house.loads.tap",
            "house.loads.tapHeat", "house.loads.otherMetered", "house.loads.unknown", "house.loads.today", "house.loads.reading", "house.loads.failed",
            "house.loads.unsupported", "house.loads.noFlow", "house.loads.otherHolds", "house.loads.over", "house.loads.none", "house.loads.carHeat",
            "house.loads.car", "house.loads.heat", "house.loads.chart", "house.loads.implausible", "house.loads.implausibleOut", "hist.loading", "hist.failed",
            "hist.unsupported", "hist.house.noMeters", "hist.house.noEnergy", "house.tile.sun", "house.tile.house", "house.tile.discharged",
            "house.tile.charged", "house.tile.bought", "house.tile.sold", "house.tile.heat", "house.tile.car"
        )
        for (language in CardStringsXml.LANGUAGES) {
            val words = CardStringsXml.words(language)
            for (key in keys) assertTrue("$language $key", words.say(key, "pct" to "1 %", "kwh" to "1", "list" to "x", "figures" to "x", "name" to "x", "hours" to "1").isNotEmpty())
        }
    }

    // --- which boxes the house page has ---

    @Test fun aBoxWithNothingToShowIsLeftOut() {
        // No energy-flow data: no Energiflöde box.
        val alone = house("house_learning.json")
        assertNull(alone.energyFlow)
        assertFalse(HouseBoxes.flow(alone))
        assertTrue(HouseBoxes.flow(house()))
        // Before the first answer there is Now alone.
        assertFalse(HouseBoxes.flow(null))
        assertFalse(HouseBoxes.flow(alone.copy(energyFlow = null)))
        assertTrue(HouseBoxes.flow(alone.copy(energyFlow = house().energyFlow)))
    }

    @Test fun thePickerIsDrawnOnlyWhileABoxNeedsIt() {
        // A house with no energy flow still has the energy chart (hours), so the picker has a box to drive.
        assertTrue(HouseBoxes.picker(house("house_learning.json")))
        assertFalse(HouseBoxes.flow(house("house_learning.json")))
        assertFalse(HouseBoxes.picker(null))
        assertTrue(HouseBoxes.picker(house()))
    }
}
