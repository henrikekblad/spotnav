package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun figures(name: String = "villa_now.json") = HouseNowModel.figures(live(name), house())

    private fun line(figures: NowFigures, role: NodeRole) = figures.lines.first { it.role == role }

    /** A live answer like villa_now with other figures, nothing estimated unless said. */
    private fun flows(grid: Double, sun: Double, battery: Double, car: Double, house: Double, heat: Double = 1.1) =
        live().copy(gridKw = grid, sunKw = sun, batteryKw = battery, carKw = car, houseKw = house, heatKw = heat, otherKw = house - heat, restKw = house - heat, estimated = emptySet())

    /** Into the house as written (tenths), out of it with the house, the house and its ring's parts. */
    private fun sides(figures: NowFigures): List<Long> {
        fun tenths(kw: Double?) = Math.round((kw ?: 0.0) * 10)
        val into = figures.lines.filter { it.way == Way.IN }.sumOf { tenths(it.kw) }
        val out = figures.lines.filter { it.way == Way.OUT }.sumOf { tenths(it.kw) } + tenths(figures.houseKw)
        return listOf(into, out, tenths(figures.houseKw), figures.parts.sumOf { tenths(it.kw) })
    }

    @Test fun eachLineHasItsWayAnExportAndAChargingBatteryAwayFromTheHouse() {
        val export = figures("villa_export.json")
        assertEquals(NowLine(NodeRole.GRID, 2.6, Way.OUT, Tone.EXPORT, false), line(export, NodeRole.GRID))
        assertEquals(Way.OUT, line(export, NodeRole.BATTERY).way)
        assertEquals(1.2, line(export, NodeRole.BATTERY).kw!!, 0.0)
        assertEquals(Way.IN, line(export, NodeRole.SUN).way)
        // No car plugged in: its node stays, with a still line.
        assertEquals(Way.STILL, line(export, NodeRole.CAR).way)
        val car = figures("villa_car.json")
        assertEquals(Way.OUT, line(car, NodeRole.CAR).way)
        assertEquals(7.4, line(car, NodeRole.CAR).kw!!, 0.0)
        assertEquals(Tone.IMPORT, line(car, NodeRole.GRID).tone)
        // The heat is no node: it is in the house's ring.
        assertEquals(listOf(NodeRole.SUN, NodeRole.GRID, NodeRole.BATTERY, NodeRole.CAR), car.lines.map { it.role })
    }

    @Test fun aBatteryReadAtTheBatteryIsDrawnAsReadWithTheInverterInTheHouseSoTheLinesAddUp() {
        val dc = live("villa_dc_battery.json")
        assertEquals(-10.0, dc.batteryKw!!, 0.0)
        assertEquals(-9.57, dc.batteryAcKw!!, 0.0)
        assertEquals("balance", dc.houseFrom)
        val drawn = HouseNowModel.figures(dc, house())
        assertEquals(Way.IN, line(drawn, NodeRole.BATTERY).way)
        assertEquals(10.0, line(drawn, NodeRole.BATTERY).kw!!, 0.0)
        assertEquals(dc.houseKw!!, line(drawn, NodeRole.GRID).kw!! + line(drawn, NodeRole.SUN).kw!! + line(drawn, NodeRole.BATTERY).kw!!, 1e-9)
        // From a Home Assistant before the AC side: the battery as read on both, and no word on the house.
        val old = (HouseNowRead.decode(HaFixtures.json("house_now/villa_dc_battery.json").apply { remove("battery_ac_kw"); remove("house_from") }) as SiteRead.Read).value
        assertEquals(-10.0, old.batteryAcKw!!, 0.0)
        assertNull(old.houseFrom)
    }

    @Test fun withoutTheLiveFlowsTheDrawingHasTheDaysFiguresAndTheBatterysOwnPower() {
        val answer = house().battery { it.copy(now = House.Battery.Now(null, -0.6)) }
        val drawn = HouseNowModel.figures(null, answer)
        assertEquals(NowLine(NodeRole.BATTERY, 0.6, Way.IN, Tone.BATTERY, false), line(drawn, NodeRole.BATTERY))
        assertEquals(NowLine(NodeRole.GRID, null, Way.STILL, Tone.IMPORT, false), line(drawn, NodeRole.GRID))
        assertNull(drawn.houseKw)
        assertTrue(drawn.parts.isEmpty())
        // What the site has comes from the house.
        assertEquals(Present(sun = true, battery = true, car = true, heat = true), HouseNowModel.present(null, answer))
        val picture = HouseNowModel.picture(text("en"), answer, null, now)
        assertEquals("7.5 kWh today", picture.captions.getValue(NodeRole.SUN).today)
        // No live figures: no sentence and no own share, the price still.
        assertNull(picture.headline.why)
        assertNull(picture.headline.own)
        assertNotNull(picture.headline.price)
        assertEquals("–", picture.houseFigure)
        assertEquals("Läge Balanserad · inga livevärden än", HouseNowModel.notesLine(text(), answer, null))
        assertEquals("Mode Balanced · no live figures yet", HouseNowModel.notesLine(text("en"), answer, null))
        assertEquals("Läge Balanserad", HouseNowModel.notesLine(text(), answer, live()))
        // A house alone has no mode to tell.
        assertNull(HouseNowModel.notesLine(text(), house("house_learning.json"), live("house_alone_now.json")))
    }

    @Test fun eachNodeHasItsStateNowAndTodaysKwh() {
        val captions = HouseNowModel.picture(text(), house(), live(), now).captions
        assertEquals(Captions("Sol", "7,5 kWh i dag"), captions[NodeRole.SUN])
        assertEquals(Captions("Köper", "köpt 76,9 · sålt 0,1 kWh"), captions[NodeRole.GRID])
        assertEquals(Captions("54 % · täcker huset", "in 38,0 · ut 8,6 kWh"), captions[NodeRole.BATTERY])
        assertEquals(Captions("Ansluten · 73 %", "laddat 25,5 kWh i dag"), captions[NodeRole.CAR])
        assertEquals("29,4 kWh i dag", HouseNowModel.picture(text(), house(), live(), now).houseToday)
        val unplugged = HouseNowModel.picture(text(), house(), live().copy(carConnected = false, carSocPct = null), now)
        assertEquals("Ingen bil", unplugged.captions.getValue(NodeRole.CAR).state)
        assertEquals("Laddar · 73 %", HouseNowModel.picture(text(), house(), live("villa_car.json"), now).captions.getValue(NodeRole.CAR).state)
        assertEquals("Sol, ingen nu", HouseNowModel.picture(text(), house(), live("villa_car.json"), now).captions.getValue(NodeRole.SUN).state)
        assertEquals("Säljer", HouseNowModel.picture(text(), house(), live("villa_export.json"), now).captions.getValue(NodeRole.GRID).state)
    }

    @Test fun theWholeHouseIsInTheMiddleAndItsPartsInTheRingMarkedWhereEstimated() {
        val stale = figures("villa_stale_parts.json")
        val (into, out, middle, parts) = sides(stale)
        assertEquals(into, out)
        assertEquals(middle, parts)
        // 0,6 + 1,44 = 2,04: tenths that still add up.
        assertEquals(listOf(NowPart(PartRole.HEAT, 1.8, true), NowPart(PartRole.OTHER, 0.2, true)), stale.parts)
        val picture = HouseNowModel.picture(text(), house(), live("villa_stale_parts.json"), now)
        assertEquals("≈ 0,6 kW", picture.labels[NodeRole.SUN])
        assertEquals("1,4 kW", picture.labels[NodeRole.BATTERY])
        assertEquals("≈ 2,0 kW", picture.houseFigure)
        assertEquals(listOf(PartRole.HEAT to "Värme ≈ 1,8 kW", PartRole.OTHER to "Övrigt ≈ 0,2 kW"), picture.legend)
        assertTrue(picture.description, "Huset nu 2,0 kW (uppskattat)" in picture.description)
        assertEquals("Värme ≈ 1,1 kW", HouseNowModel.picture(text(), house(), live(), now).legend.first().second)
    }

    @Test fun thePoolTheSpaAndTheSaunaAreInTheRingBesideTheHeat() {
        val pool = figures("villa_pool.json")
        val (into, out, middle, parts) = sides(pool)
        assertEquals(into, out)
        assertEquals(middle, parts)
        assertEquals(
            listOf(NowPart(PartRole.HEAT, 0.9, false), NowPart(PartRole.POOL, 0.9, true), NowPart(PartRole.OTHER, 0.5, true)),
            pool.parts
        )
        val picture = HouseNowModel.picture(text("en"), house(), live("villa_pool.json"), now)
        assertEquals(PartRole.POOL to "Pool, spa, sauna ≈ 0.9 kW", picture.legend[1])
        assertEquals("The sun covers the house and charges the battery. The surplus is sold.", picture.headline.why)
        assertTrue(picture.present.pool)
    }

    @Test fun aHeatCutToTheHouseAndALaggingCarBalance() {
        for (name in listOf("villa_heat_over.json", "villa_car_lags.json", "villa_now.json", "villa_export.json", "villa_car.json", "villa_dc_battery.json", "villa_pool.json", "villa_stale_parts.json")) {
            val (into, out, middle, parts) = sides(figures(name))
            assertEquals(name, into, out)
            assertEquals(name, middle, parts)
        }
        assertEquals(NowPart(PartRole.HEAT, 1.3, true), figures("villa_heat_over.json").parts.first())
        val lags = HouseNowModel.figures(live("villa_car_lags.json").copy(carConnected = null), house())
        assertEquals(NowLine(NodeRole.CAR, 0.5, Way.OUT, Tone.CAR, true), line(lags, NodeRole.CAR))
    }

    @Test fun eachSideAndTheRingAreRoundedToTheSameTenthsByTheLargestRemainder() {
        assertEquals(listOf(7, 6, 7), HouseNowModel.largestRemainder(listOf(0.66, 0.66, 0.68), 20))
        assertEquals(listOf(11, 10, 10), HouseNowModel.largestRemainder(listOf(1.04, 1.04, 1.04), 31))
        assertEquals(listOf(2, 2), HouseNowModel.largestRemainder(listOf(0.26, 0.26), 4))
        val thirds = HouseNowModel.figures(live().copy(gridKw = 0.33, sunKw = 0.33, batteryKw = -0.34, carKw = 0.0, heatKw = 0.55, houseKw = 1.0, restKw = 0.45, otherKw = 0.45, estimated = emptySet()), house())
        assertEquals(listOf(10L, 10L, 10L, 10L), sides(thirds))
        assertEquals(listOf(0.3, 0.3, 0.4), thirds.lines.filter { it.way == Way.IN }.map { it.kw!! }.sorted())
        assertEquals(listOf(0.6, 0.4), thirds.parts.map { it.kw })
        // A large figure keeps its decimal, so it adds up with the small ones.
        val big = HouseNowModel.picture(text("en"), house(), live().copy(gridKw = 12.34, sunKw = 0.0, batteryKw = 0.0, carKw = 0.0, heatKw = 1.0, houseKw = 12.34, restKw = 11.34, otherKw = 11.34, estimated = emptySet()), now)
        assertEquals("12.3 kW", big.labels[NodeRole.GRID])
        assertEquals("12.3 kW", big.houseFigure)
        // A heat over a house worked out from the lines is cut to it.
        val unread = HouseNowModel.figures(live().copy(houseKw = null, heatKw = 9.0), house())
        assertEquals(listOf(3.1, 0.0), unread.parts.map { it.kw })
    }

    @Test fun theHeadlineSaysWhatIsHappeningNowFromTheFlows() {
        fun why(grid: Double, sun: Double, battery: Double, car: Double, house: Double, band: PriceBand) =
            HouseNowModel.whyKey(HouseNowModel.figures(flows(grid, sun, battery, car, house, heat = 0.2), house()), band)?.removePrefix("house.nowTab.why.")
        assertEquals("batterySunDear", why(0.0, 0.6, -1.5, 0.0, 2.1, PriceBand.DEAR))
        assertEquals("batteryDear", why(0.0, 0.0, -1.5, 0.0, 1.5, PriceBand.DEAR))
        assertEquals("cheapBatteryCar", why(16.4, 0.0, 7.0, 7.4, 2.0, PriceBand.CHEAP))
        assertEquals("cheapBattery", why(9.0, 0.0, 7.0, 0.0, 2.0, PriceBand.CHEAP))
        assertEquals("cheapCar", why(9.4, 0.0, 0.0, 7.4, 2.0, PriceBand.CHEAP))
        assertEquals("sunBatterySold", why(-3.1, 7.8, 2.4, 0.0, 2.3, PriceBand.CHEAP))
        assertEquals("sunSold", why(-3.1, 5.4, 0.0, 0.0, 2.3, PriceBand.MID))
        assertEquals("sunPart", why(0.5, 1.0, 0.0, 0.0, 1.5, PriceBand.MID))
        assertEquals("sunBatteryAll", why(0.0, 1.0, -0.6, 0.0, 1.6, PriceBand.MID))
        assertEquals("sunBatteryPart", why(0.4, 1.0, -0.6, 0.0, 2.0, PriceBand.MID))
        assertEquals("sunBattery", why(0.0, 3.0, 1.0, 0.0, 2.0, PriceBand.MID))
        assertEquals("sunAll", why(0.0, 1.5, 0.0, 0.0, 1.5, PriceBand.MID))
        assertEquals("battery", why(0.0, 0.0, -1.5, 0.0, 1.5, PriceBand.MID))
        assertEquals("batteryPart", why(0.5, 0.0, -1.0, 0.0, 1.5, PriceBand.MID))
        assertEquals("car", why(9.4, 0.0, 0.0, 7.4, 2.0, PriceBand.MID))
        assertEquals("gridAll", why(2.0, 0.0, 0.0, 0.0, 2.0, PriceBand.MID))
        // The grid idle and nothing else flowing: nothing worth a sentence; no live figures: nothing to say.
        assertNull(why(0.0, 0.0, 0.0, 0.0, 0.0, PriceBand.MID))
        assertNull(HouseNowModel.whyKey(HouseNowModel.figures(null, house()), PriceBand.MID))
        assertEquals("Solen och batteriet täcker huset.", text().words.say("house.nowTab.why.sunBatteryAll"))
    }

    @Test fun theOwnShareIsHiddenWithoutSunAndBatteryAndThePriceIsInItsBand() {
        // 0,4 kW bought of 3,1 kW used: 87 % own.
        assertEquals(87, HouseNowModel.ownShare(figures()))
        assertEquals(0, HouseNowModel.ownShare(HouseNowModel.figures(flows(16.4, 0.0, 7.0, 7.4, 2.0, heat = 0.2), house())))
        val head = HouseNowModel.picture(text(), house(), live(), now).headline
        assertEquals("87 % egen el", head.own)
        assertEquals(OwnTone.HIGH, head.ownTone)
        assertTrue(head.price!!, Regex("^(Billig|Normal|Dyr) timme · \\d+,\\d\\d kr$").matches(head.price!!))
        assertEquals(HouseNowModel.priceNow(house(), now)!!.band, head.band)
        // A house with the grid alone: no own share.
        assertNull(HouseNowModel.picture(text(), house("house_learning.json"), live("house_alone_now.json"), now).headline.own)
        // The bands are the plan's priced hours' thirds.
        val priced = house().let { answer -> answer.copy(hours = answer.hours.mapIndexed { index, hour -> hour.copy(buy = index.toDouble()) }) }
        fun band(index: Int) = HouseNowModel.priceNow(priced, priced.hours[index].start.plusSeconds(60))?.band
        val size = priced.hours.size
        assertEquals(PriceBand.CHEAP, band(size / 3 - 1))
        assertEquals(PriceBand.MID, band(size / 3))
        assertEquals(PriceBand.DEAR, band(size * 2 / 3))
        assertNull(HouseNowModel.priceNow(priced.copy(hours = priced.hours.map { it.copy(buy = null) }), priced.hours[0].start.plusSeconds(60)))
        assertNull(HouseNowModel.priceNow(priced, priced.hours.last().start.plusSeconds(7200)))
    }

    @Test fun theDrawingSaysItsFiguresAndEachLinesWayInWords() {
        val picture = HouseNowModel.picture(text(), house(), live(), now)
        assertEquals("≈ 3,1 kW", picture.houseFigure)
        assertEquals(mapOf(NodeRole.SUN to "1,2 kW", NodeRole.GRID to "0,4 kW", NodeRole.BATTERY to "1,5 kW"), picture.labels)
        assertTrue(picture.description, picture.description.startsWith("Huset nu 3,1 kW (uppskattat). "))
        assertTrue(picture.description, "Köper 0,4 kW från nätet" in picture.description)
        assertTrue(picture.description, "Batteriet ger 1,5 kW" in picture.description)
        assertTrue(picture.description, picture.description.endsWith("29,4 kWh i dag"))
        val export = HouseNowModel.picture(text("en"), house(), live("villa_export.json"), now).description
        assertTrue(export, "Selling 2.6 kW to the grid" in export)
        assertTrue(export, "The battery charges 1.2 kW" in export)
        assertTrue(export, "The sun gives 4.8 kW (estimated)" in export)
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
        assertEquals(listOf("51 kr", "4,00 kr", "0,80 kr", "0 kr", "-12 kr"), listOf(5100.0, 400.0, 80.0, 0.0, -1200.0).map { text().amount(it, money) })
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
                    HouseNowModel.picture(text, answer, flows, now).description,
                    HouseNowModel.picture(text, answer, flows, now).headline.why,
                    HouseNowModel.picture(text, answer, flows, now).headline.own,
                    HouseNowModel.picture(text, answer, flows, now).headline.price,
                    HouseNowModel.planSentence(text, answer, now),
                    HouseNowModel.moneyLine(text, answer),
                    HouseNowModel.notesLine(text, answer, flows),
                    (HouseNowModel.status(text.words, answer) as? HouseStatus.Said)?.text
                ) + HouseNowModel.learningLines(text, answer) + HouseNowModel.picture(text, answer, flows, now).legend.map { it.second } +
                    HouseNowModel.picture(text, answer, flows, now).captions.values.flatMap { listOf(it.state, it.today) }
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
