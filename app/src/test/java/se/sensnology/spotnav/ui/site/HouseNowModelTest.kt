package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseNow
import se.sensnology.spotnav.ha.house.HouseNowRead
import se.sensnology.spotnav.ha.house.HouseRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.util.Locale

/**
 * The house page's Now tab in words and figures, as the Home Assistant card's Now tab has them
 * (`frontend/test/house-tabs.test.ts`), on the same fixtures.
 */
class HouseNowModelTest {
    /** The house fixture's own moment: 13 November 2026, 17:20 in Stockholm. */
    private val now = Instant.parse("2026-11-13T16:20:00Z")

    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun house(name: String = "villa_battery_sun.json"): House =
        (HouseRead.decode(HaFixtures.json("house/$name")) as SiteRead.Read).value

    private fun live(name: String = "villa_now.json"): HouseNow =
        (HouseNowRead.decode(HaFixtures.json("house_now/$name")) as SiteRead.Read).value

    private fun House.battery(edit: (House.Battery) -> House.Battery) = copy(battery = edit(battery!!))

    @Test fun eachLineHasItsWayAnExportAndAChargingBatteryAwayFromTheHouse() {
        val lines = HouseNowModel.lines(live("villa_export.json"), house()).associateBy { it.role }
        assertEquals(SchematicLine(NodeRole.GRID, 2.6, Way.OUT, Tone.EXPORT), lines[NodeRole.GRID])
        assertEquals(SchematicLine(NodeRole.BATTERY, 1.2, Way.OUT, Tone.BATTERY), lines[NodeRole.BATTERY])
        assertEquals(Way.IN, lines.getValue(NodeRole.SUN).way)
        // No car plugged in: no line to it.
        assertNull(lines[NodeRole.CAR])
        val car = HouseNowModel.lines(live("villa_car.json"), house()).first { it.role == NodeRole.CAR }
        assertEquals(SchematicLine(NodeRole.CAR, 7.4, Way.OUT, Tone.CAR), car)
        // An import in the import's colour; a sun that gives nothing stands still.
        val charging = HouseNowModel.lines(live("villa_car.json"), house()).associateBy { it.role }
        assertEquals(SchematicLine(NodeRole.GRID, 7.6, Way.IN, Tone.IMPORT), charging[NodeRole.GRID])
        assertEquals(Way.STILL, charging.getValue(NodeRole.SUN).way)
        assertEquals(Way.IN, charging.getValue(NodeRole.BATTERY).way)
        assertEquals(Way.OUT, charging.getValue(NodeRole.HEAT).way)
    }

    @Test fun theDotsRunFasterWithMorePowerWithinTheLapsBounds() {
        assertEquals(3.3, HouseNowModel.dotLap(0.0), 1e-9)
        assertEquals(0.8, HouseNowModel.dotLap(12.0), 1e-9)
        assertEquals(0.8, HouseNowModel.dotLap(30.0), 1e-9)
        assertTrue(HouseNowModel.dotLap(6.0) < HouseNowModel.dotLap(1.0))
    }

    @Test fun withoutTheLiveFlowsTheSchematicHasTheDaysFiguresAndTheBatterysOwnPower() {
        val answer = house().battery { it.copy(now = House.Battery.Now(null, -0.6)) }
        val lines = HouseNowModel.lines(null, answer).associateBy { it.role }
        assertEquals(SchematicLine(NodeRole.BATTERY, 0.6, Way.IN, Tone.BATTERY), lines[NodeRole.BATTERY])
        assertEquals(SchematicLine(NodeRole.GRID, null, Way.STILL, Tone.GRID), lines[NodeRole.GRID])
        // What the site has comes from the house: the car is known by the day's energy flow, but has no line.
        assertEquals(Present(sun = true, battery = true, car = true, heat = true), HouseNowModel.present(null, answer))
        assertNull(lines[NodeRole.CAR])
        assertEquals("Läge Balanserad · inga livevärden än", HouseNowModel.notesLine(text(), answer, null))
        assertEquals("Mode Balanced · no live figures yet", HouseNowModel.notesLine(text("en"), answer, null))
        assertEquals("Läge Balanserad", HouseNowModel.notesLine(text(), answer, live()))
        // A house alone has no mode to tell.
        assertNull(HouseNowModel.notesLine(text(), house("house_learning.json"), live("house_alone_now.json")))
    }

    @Test fun theCornersHaveTheDaySoFarAndTheLiveLevels() {
        val corners = HouseNowModel.corners(text(), house(), live(), wide = false).associateBy { it.role }
        assertEquals(setOf(NodeRole.SUN, null, NodeRole.GRID, NodeRole.BATTERY, NodeRole.HEAT, NodeRole.CAR), corners.keys)
        assertEquals(CornerWords(NodeRole.SUN, "Sol i dag", "7,5 kWh", Tone.SUN), corners[NodeRole.SUN])
        // The house with its heat, without the car, and says so.
        assertEquals(CornerWords(null, "Hus i dag", "29,4 kWh", Tone.HOUSE, under = "utan bilen"), corners[null])
        // Bought, with the sold part under it where there is no room beside.
        val grid = corners.getValue(NodeRole.GRID)
        assertEquals(listOf("Köpt i dag", "76,9 kWh", null, "sålt 0,1 kWh", Tone.EXPORT), listOf(grid.name, grid.value, grid.beside, grid.under, grid.underTone))
        // The live level, and what it is of the usable size.
        assertEquals(CornerWords(NodeRole.BATTERY, "Batteri", "54 %", Tone.BATTERY, under = "21,8 kWh"), corners[NodeRole.BATTERY])
        assertEquals(CornerWords(NodeRole.HEAT, "Värme i dag", "12,6 kWh", Tone.HEAT), corners[NodeRole.HEAT])
        // A plugged-in car shows its level, with the day's energy under it.
        assertEquals(CornerWords(NodeRole.CAR, "Bil", "73 %", Tone.CAR, under = "25,5 kWh i dag"), corners[NodeRole.CAR])
        val wide = HouseNowModel.corners(text(), house(), live(), wide = true).first { it.role == NodeRole.GRID }
        assertEquals(listOf(" · sålt 0,1", Tone.EXPORT, null), listOf(wide.beside, wide.besideTone, wide.under))
    }

    @Test fun anUnpluggedCarShowsTheDaysEnergyAndIsDimmed() {
        val unplugged = live().copy(carConnected = false, carSocPct = null)
        val car = HouseNowModel.corners(text(), house(), unplugged, wide = false).first { it.role == NodeRole.CAR }
        assertEquals(CornerWords(NodeRole.CAR, "Bil", "25,5 kWh", Tone.CAR, under = "i dag"), car)
        val schematic = HouseNowModel.schematic(text(), house(), unplugged, wide = false)
        assertTrue(schematic.carDimmed)
        assertFalse(schematic.lines.any { it.role == NodeRole.CAR })
        assertFalse(HouseNowModel.schematic(text(), house(), live(), wide = false).carDimmed)
    }

    @Test fun aHouseAloneIsOneRowTheGridAndTheHouse() {
        val alone = HouseNowModel.schematic(text("en"), house("house_learning.json"), live("house_alone_now.json"), wide = false)
        assertTrue(alone.present.alone)
        assertEquals(listOf(NodeRole.GRID), alone.lines.map { it.role })
        assertEquals(listOf(null, NodeRole.GRID), alone.corners.map { it.role })
        // The row has no room for the house's own power; the grid's line says it.
        assertNull(alone.houseKw)
        assertEquals(mapOf(NodeRole.GRID to "1.8 kW"), alone.labels)
        // Nothing measured for the day yet: a dash, never 0.
        assertEquals(listOf("–", "–"), alone.corners.map { it.value })
    }

    @Test fun theSchematicSaysItsFiguresAndEachLinesWayInWords() {
        val schematic = HouseNowModel.schematic(text(), house(), live(), wide = false)
        assertEquals("3,1 kW", schematic.houseKw)
        assertEquals(
            mapOf(NodeRole.SUN to "1,2 kW", NodeRole.GRID to "0,4 kW", NodeRole.BATTERY to "1,5 kW", NodeRole.HEAT to "1,1 kW"),
            schematic.labels
        )
        assertTrue(schematic.description, schematic.description.startsWith("Huset nu 3,1 kW. Sol i dag 7,5 kWh. Hus i dag 29,4 kWh utan bilen. "))
        assertTrue(schematic.description, "Köper 0,4 kW från nätet" in schematic.description)
        assertTrue(schematic.description, "Batteriet ger 1,5 kW" in schematic.description)
        assertTrue(schematic.description, schematic.description.endsWith("Värmen använder 1,1 kW"))
        val export = HouseNowModel.schematic(text("en"), house(), live("villa_export.json"), wide = false).description
        assertTrue(export, "Selling 2.6 kW to the grid" in export)
        assertTrue(export, "The battery charges 1.2 kW" in export)
        // From 10 kW a figure has no decimal.
        assertEquals("12 kW", text().kw(12.4))
    }

    @Test fun theSentenceSaysWhatTheBatteryDoesAndWhatItsPlanDoesNext() {
        val answer = house()
        assertTrue(HouseNowModel.planRuns(answer, now).size > 1)
        val controlled = answer.battery { it.copy(control = "on") }
        assertEquals(
            "Batteriet täcker huset och lagrar solen till 18:00, sedan säljer det till nätet.",
            HouseNowModel.planSentence(text(), controlled, now)
        )
        assertEquals(
            "Batteriet styr sig självt (Sigenergy AI) · SpotNav skulle täcka huset och lagra solen till 18:00, sedan sälja till nätet.",
            HouseNowModel.planSentence(text(), answer, now)
        )
        val free = answer.battery { it.copy(management = "free") }
        assertTrue(HouseNowModel.planSentence(text("en"), free, now)!!.startsWith("Only testing so far · SpotNav would cover the house and store the sun until 18:00"))
        assertEquals("Batteriplanen har inte körts än.", HouseNowModel.planSentence(text(), answer.battery { it.copy(status = "waiting") }, now))
        assertEquals(
            "Ingen batteriplan just nu; detaljerna visar vad som saknas.",
            HouseNowModel.planSentence(text(), answer.battery { it.copy(status = "no_plan") }, now)
        )
        // Another brand's own control has no name of its own.
        assertTrue(
            HouseNowModel.planSentence(text(), answer.battery { it.copy(brand = "other") }, now)!!
                .startsWith("Batteriet styr sig självt (Batteriets egen styrning) · ")
        )
        // No battery, no sentence.
        assertNull(HouseNowModel.planSentence(text(), house("house_learning.json"), now))
    }

    @Test fun aChangeOnAnotherDayIsToldWithItsDayAndAPlanOfOneRunIsToldWhole() {
        val answer = house().battery { it.copy(control = "on") }
        // From the running hour to the first change on the next day.
        val late = answer.copy(hours = answer.hours.map { hour ->
            if (hour.start < Instant.parse("2026-11-13T23:00:00Z")) hour.copy(batteryAction = null) else hour.copy(batteryAction = "grid_charge")
        })
        assertEquals(
            "Batteriet täcker huset och lagrar solen till lör 14 00:00, sedan laddar det från nätet.",
            HouseNowModel.planSentence(text(), late, now)
        )
        val whole = answer.copy(hours = answer.hours.map { it.copy(batteryAction = "hold") })
        assertEquals("Batteriet håller laddningen så långt planen räcker.", HouseNowModel.planSentence(text(), whole, now))
        val would = whole.battery { it.copy(control = "off", management = "free") }
        assertEquals("Bara test än · SpotNav skulle hålla laddningen så långt planen räcker.", HouseNowModel.planSentence(text(), would, now))
        // A plan that has run out: nothing to say while controlling, who runs the battery otherwise.
        val over = answer.copy(hours = answer.hours.map { it.copy(batteryAction = null, socPlanPct = null) })
        assertNull(HouseNowModel.planSentence(text(), over, now))
        assertEquals("Bara test än", HouseNowModel.planSentence(text(), over.battery { it.copy(control = "off", management = "free") }, now))
    }

    @Test fun todaysMoneyIsOneLine() {
        // The fixture's battery ran under its own control (battery_share 0): its figure is its own.
        assertEquals("I dag: batteriet tjänade 11 kr (egen styrning) · SpotNav sparade 17 kr", HouseNowModel.moneyLine(text(), house()))
        assertEquals("Today: the battery earned 11 kr (own control) · SpotNav saved 17 kr", HouseNowModel.moneyLine(text("en"), house()))
        val steered = house().let { it.copy(saving = it.saving!!.copy(batteryShare = 1.0)) }
        assertEquals("I dag: batteriet tjänade 11 kr · SpotNav sparade 17 kr", HouseNowModel.moneyLine(text(), steered))
        assertNull(HouseNowModel.moneyLine(text(), house("house_learning.json")))
        assertEquals("I dag: batteriet tjänade 11 kr", HouseNowModel.moneyLine(text(), house().copy(saving = null)))
        // Whole units from 10 up and for nothing, one decimal below; the minor unit where there is no major one.
        val money = House.Money("SEK", "kr", "öre")
        assertEquals(listOf("51 kr", "4,00 kr", "0,80 kr", "0,00 kr", "-12 kr"), listOf(5100.0, 400.0, 80.0, 0.0, -1200.0).map { text().amount(it, money) })
        assertEquals("80 öre", text().amount(80.0, House.Money("SEK", null, "öre")))
        assertEquals("80", text().amount(80.0, null))
    }

    @Test fun aLearningPeriodThatHoldsSomethingBackIsToldInOneLine() {
        assertEquals(
            listOf("Lär sig husets toppar, 13 dygn kvar. Laddar inte från nätet förrän dess."),
            HouseNowModel.learningLines(text(), house())
        )
        val capped = house().copy(learning = listOf(House.LearningHold("phase_peaks", 1, 1.4), House.LearningHold("something_new", 3, 0.0)))
        assertEquals(listOf("Nätladdning högst 1,4 kW medan husets toppar lärs in (1 dygn kvar)."), HouseNowModel.learningLines(text(), capped))
        assertEquals(
            listOf("Grid charging at most 2 kW while the house's peaks are being learned (1 day left)."),
            HouseNowModel.learningLines(text("en"), house().copy(learning = listOf(House.LearningHold("phase_peaks", 1, 2.0))))
        )
        assertEquals(emptyList<String>(), HouseNowModel.learningLines(text(), house("house_learning.json")))
    }

    @Test fun theHeaderSaysWhatTheBatteryDoesNow() {
        val words = CardStringsXml.words("sv")
        val answer = house()
        // The battery's own optimiser runs it.
        assertEquals(HouseStatus.Said("own", "Egen styrning"), HouseNowModel.status(words, answer))
        // SpotNav controls nothing yet.
        assertEquals(HouseStatus.Test, HouseNowModel.status(words, answer.battery { it.copy(management = "free") }))
        fun on(action: String?, power: Double?) =
            HouseNowModel.status(words, answer.battery { it.copy(control = "on", now = House.Battery.Now(action, power)) })
        assertEquals(HouseStatus.Said("grid_charge", "Laddar från nätet"), on("grid_charge", 3.0))
        assertEquals(HouseStatus.Said("hold", "Håller"), on("hold", 0.0))
        assertEquals(HouseStatus.Said("export_discharge", "Säljer till nätet"), on("export_discharge", -4.0))
        assertEquals(HouseStatus.Said("discharge_house", "Laddar ur"), on("discharge_house", -1.0))
        // Its own self-consumption: what the measured power says.
        assertEquals(HouseStatus.Said("sun", "Laddar med sol"), on(null, 0.6))
        assertEquals(HouseStatus.Said("discharge", "Laddar ur"), on(null, -0.62))
        assertEquals(HouseStatus.Said("idle", "Vilar"), on(null, 0.05))
        assertEquals(HouseStatus.Said("idle", "Vilar"), on("something_new", 0.0))
        assertNull(on(null, null))
        assertNull(HouseNowModel.status(words, answer.battery { it.copy(control = "on", now = null) }))
        assertNull(HouseNowModel.status(words, house("house_learning.json")))
    }

    @Test fun everyLanguageHasEveryWordOfTheNowTab() {
        val states = listOf(
            house() to live(),
            house().battery { it.copy(control = "on") } to live("villa_export.json"),
            house().battery { it.copy(management = "free") } to live("villa_car.json"),
            house() to null,
            house("house_learning.json") to live("house_alone_now.json")
        )
        for (language in CardStringsXml.LANGUAGES) {
            val text = text(language)
            for ((answer, flows) in states) {
                val said = listOfNotNull(
                    HouseNowModel.schematic(text, answer, flows, wide = true).description,
                    HouseNowModel.schematic(text, answer, flows, wide = false).description,
                    HouseNowModel.planSentence(text, answer, now),
                    HouseNowModel.moneyLine(text, answer),
                    HouseNowModel.notesLine(text, answer, flows),
                    (HouseNowModel.status(text.words, answer) as? HouseStatus.Said)?.text
                ) + HouseNowModel.learningLines(text, answer)
                for (line in said) {
                    assertFalse("$language: $line", "{" in line || "}" in line)
                    assertFalse("$language: $line", "house." in line || "learning." in line)
                }
            }
            for (key in listOf("house.title", "heat.title", "header.test", "header.testExplain", "house.test.explain", "house.loading", "house.failed", "house.tabs")) {
                assertTrue("$language $key", text.words.say(key).isNotBlank())
            }
        }
    }
}
