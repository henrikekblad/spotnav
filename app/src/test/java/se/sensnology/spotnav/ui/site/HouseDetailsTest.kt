package se.sensnology.spotnav.ui.site

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.HouseHistoryRead
import se.sensnology.spotnav.ha.house.HouseRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The house's details sheet on the integration's fixtures: the card's `period-history` and `history-bars` cases, ported. */
class HouseDetailsTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun history(name: String, change: (HouseHistory) -> HouseHistory = { it }): HouseHistory =
        change((HouseHistoryRead.decode(HaFixtures.json("house_history/$name")) as SiteRead.Read).value)

    private fun house(): House = (HouseRead.decode(HaFixtures.json("house/villa_battery_sun.json")) as SiteRead.Read).value

    private fun sections(h: HouseHistory, language: String = "sv", withHouse: Boolean = false) =
        HouseDetails.sections(text(language), Locale.forLanguageTag(language), h, if (withHouse) house() else null)

    private fun List<HouseDetailSection>.section(key: String) = firstOrNull { it.key == key }

    private fun HouseDetailSection.figures() = blocks.filterIsInstance<DetailBlock.Figure>()

    private fun HouseDetailSection.notes() = blocks.filterIsInstance<DetailBlock.Note>().map { it.text }

    private fun HouseDetailSection.bars() = blocks.filterIsInstance<DetailBlock.Bars>().single()

    /** Every word a sheet says, for a sweep over the languages. */
    private fun said(sections: List<HouseDetailSection>): List<String> = sections.flatMap { s ->
        listOf(s.title) + s.blocks.flatMap { b ->
            when (b) {
                is DetailBlock.Bars -> listOf(b.label, b.hint) + b.series.map { it.label } + b.bars.map { it.words }
                is DetailBlock.Figure -> listOf(b.name, b.value)
                is DetailBlock.Note -> listOf(b.text)
                is DetailBlock.Headline -> listOf(b.text)
                is DetailBlock.Share -> listOfNotNull(b.name, b.inForce, b.figure)
                is DetailBlock.Table -> b.head + b.rows.flatten()
                is DetailBlock.Ways -> b.head + b.groups.flatMap { listOf(it.first) + it.second }
                is DetailBlock.Sources -> listOf(b.heading) + b.facts + b.notes
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- decoding

    @Test fun everyFixtureDecodesAndANewerVersionOrAMistypedFieldIsRefused() {
        for (name in listOf("villa_month.json", "villa_day.json", "villa_year.json", "house_alone_year.json")) {
            assertTrue(name, HouseHistoryRead.decode(HaFixtures.json("house_history/$name")) is SiteRead.Read)
        }
        fun month() = HaFixtures.json("house_history/villa_month.json")
        assertEquals(SiteRead.Unsupported, HouseHistoryRead.decode(month().put("api_version", 2)))
        assertTrue(HouseHistoryRead.decode(month().put("period", "week")) is SiteRead.Failed)
        // An added key is fine, a key the sheet needs missing is not.
        assertTrue(HouseHistoryRead.decode(month().put("later", 1)) is SiteRead.Read)
        assertTrue(HouseHistoryRead.decode(month().apply { remove("forecast") }) is SiteRead.Failed)
        assertTrue(HouseHistoryRead.decode(month().apply { getJSONArray("buckets").getJSONObject(0).remove("grid_in") }) is SiteRead.Failed)
        assertTrue(HouseHistoryRead.decode(month().apply { getJSONObject("cost").put("net", "much") }) is SiteRead.Failed)
        // A perfect plan without one way's key is broken; a share note a newer Home Assistant names is shown as none.
        assertTrue(HouseHistoryRead.decode(month().apply { getJSONObject("review").getJSONObject("perfect").getJSONObject("shares").remove("own") }) is SiteRead.Failed)
        val newer = month().apply { getJSONObject("review").getJSONArray("by_day").getJSONObject(0).put("share_note", "later") }
        assertNull((HouseHistoryRead.decode(newer) as SiteRead.Read).value.review!!.byDay[0].shareNote)
        // The saving and the own control came later: without them the answer still reads.
        val old = month().apply { remove("saving"); remove("own_control") }
        assertNull((HouseHistoryRead.decode(old) as SiteRead.Read).value.saving)
    }

    @Test fun theDecodedFiguresAreTheFixturesOwn() {
        val month = history("villa_month.json")
        assertEquals(52122.0, month.cost!!.net, 0.0)
        assertEquals(334.6, month.cost!!.kwhIn, 1e-9)
        assertEquals(13, month.review!!.batteryDays)
        assertEquals(74.0, month.review!!.perfect!!.shares["balanced"]!!, 1.0)
        assertEquals(0.5, month.forecast.house!!.mae, 0.0)
        assertNull(month.forecast.soc)
        assertEquals(7, month.ownControl!!.days)
        assertEquals(1960.0, month.saving!!.car!!, 0.0)
        assertEquals(375.0, month.buckets.first { it.key == "2026-11-12" }.ownBattery!!, 0.0)
        assertNull(history("house_alone_year.json").review)
    }

    // ---------------------------------------------------------------------------------------------- the cost bars

    @Test fun aMoneyBarIsTheCostFilledAndTheSavingHatchedOnTop() {
        fun parts(cost: Double?, saving: Double?) = HouseDetails.moneyParts(cost, saving).let { listOf(it.solid, it.hatch, it.warn, it.whole) }
        assertEquals(listOf(3000.0, 400.0, 0.0, 3400.0), parts(3000.0, 400.0))
        assertEquals(listOf(3000.0, 0.0, 0.0, 3000.0), parts(3000.0, null))
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), parts(null, null))
        // A saving below zero is the top of the cost itself, so the bar is never taller than what was paid.
        assertEquals(listOf(2750.0, 0.0, 250.0, 3000.0), parts(3000.0, -250.0))
        assertEquals(listOf(0.0, 0.0, 100.0, 100.0), parts(100.0, -300.0))
        assertEquals(listOf(0.0, 300.0, 0.0, 300.0), parts(-200.0, 500.0))
    }

    @Test fun theMonthsBarsSayTheCostAndWhatTheBatterySaved() {
        val bars = sections(history("villa_month.json")).section("bars")!!.bars()
        assertEquals(30, bars.bars.size)
        val reviewed = bars.bars.first { it.key == "2026-11-05" }
        assertEquals(listOf(HouseDetails.COST to 3342.0, HouseDetails.SAVING to 400.0), reviewed.lanes.single().map { it.series to it.value })
        assertEquals("tors 5 nov: kostnad 33 kr, utan batteri 37 kr, sparat 4,00 kr", reviewed.words)
        assertEquals(listOf("Kostnad", "Sparat av batteriet"), bars.series.map { it.label })
        assertTrue(bars.series.last().hatched)
        assertEquals("37 kr", bars.scale(BarsLayout.extent(bars.bars, emptySet()).top))
        // A day not reviewed is the filled cost alone and says so.
        val plain = bars.bars.first { it.key == "2026-11-02" }
        assertEquals(listOf(HouseDetails.COST), plain.lanes.single().map { it.series })
        assertEquals("mån 2 nov: kostnad 33 kr, ej utvärderad", plain.words)
        // Nothing hatched: no hatched entry in the legend.
        val none = sections(history("villa_month.json") { h -> h.copy(buckets = h.buckets.map { it.copy(batterySaving = null) }) }).section("bars")!!.bars()
        assertEquals(listOf("Kostnad"), none.series.map { it.label })
    }

    @Test fun aSavingBelowZeroIsHatchedInTheWarningAtTheTopOfTheCost() {
        val worse = history("villa_month.json") { h -> h.copy(buckets = h.buckets.map { if (it.key == "2026-11-05") it.copy(batterySaving = -342.0) else it }) }
        val bars = sections(worse).section("bars")!!.bars()
        val bar = bars.bars.first { it.key == "2026-11-05" }
        assertEquals(listOf(HouseDetails.COST to 3000.0, HouseDetails.EXTRA to 342.0), bar.lanes.single().map { it.series to it.value })
        assertEquals("tors 5 nov: kostnad 33 kr, utan batteri 30 kr, batteriet gjorde det 3,42 kr dyrare", bar.words)
        assertTrue(bars.series.map { it.label }.contains("Dyrare med batteriet"))
    }

    @Test fun aDaysHoursDoNotClaimASavingAndWithoutPricesTheEnergyIsOneColour() {
        val day = sections(history("villa_day.json")).section("bars")!!.bars()
        assertEquals(24, day.bars.size)
        assertTrue(day.bars.none { bar -> bar.lanes.any { lane -> lane.any { it.series == HouseDetails.SAVING } } })
        assertTrue(day.bars.first().words, day.bars.first().words.endsWith("sparat visas per dygn"))
        val unpriced = sections(history("villa_month.json") { h -> h.copy(buckets = h.buckets.map { it.copy(cost = null) }) }).section("bars")!!.bars()
        assertEquals(listOf(HouseDetails.USE), unpriced.bars.first { it.lanes.isNotEmpty() }.lanes.single().map { it.series })
        assertTrue(unpriced.series.all { it.label.isEmpty() })
        assertTrue(unpriced.scale(12.0).endsWith("kWh"))
        // A day of 25 hours has 25 bars.
        val answer = history("villa_day.json")
        val keys = answer.buckets.map { it.key }.toMutableList().also { it.add(3, "2026-10-25T02:00:00+01:00") }
        val long = answer.copy(buckets = keys.mapIndexed { i, key -> answer.buckets[minOf(i, answer.buckets.size - 1)].copy(key = key) })
        assertEquals(25, sections(long, "en").section("bars")!!.bars().bars.size)
    }

    // ---------------------------------------------------------------------------------------------- the sections

    @Test fun aMonthHasEverySectionInTheCardsOrder() {
        val month = sections(history("villa_month.json"), withHouse = true)
        // The modes against self-consumption stand in only before the first perfect plan.
        assertEquals(listOf("bars", "perfect", "money", "spotnav-saving", "forecast", "days", "how"), month.map { it.key })
        val money = month.section("money")!!
        assertTrue(money.figures()[0].name == "Elkostnad" && money.figures()[0].value == "521 kr")
        assertEquals("Batteriet sparade" to "52 kr", money.figures().first { it.name == "Batteriet sparade" }.let { it.name to it.value })
        assertTrue(money.notes().contains("Sparat är mätt för 13 av periodens 15 dygn."))
        assertTrue(money.notes().first().startsWith("Köpte 335 kWh för 529 kr, sålde 12,8 kWh för 7,68 kr"))
    }

    @Test fun oneDayCountForTheSavingsAndARowsOwnOnlyWhereItHasFewer() {
        val answer = history("villa_month.json") { h -> h.copy(review = h.review!!.copy(batteryDays = 1)) }
        val money = sections(answer).section("money")!!
        assertTrue(money.figures().first { it.name == "Batteriet sparade" }.value.endsWith("52 kr på 1 dygn"))
        assertFalse(money.figures().first { it.name.startsWith("Solen") }.value.contains("dygn"))
        assertTrue(money.notes().contains("Sparat är mätt för 13 av periodens 15 dygn."))
    }

    @Test fun aMonthOpensWithTheSharesOfThePerfectPlanSummedAndOneTable() {
        val perfect = sections(history("villa_month.json")).section("perfect")!!
        assertEquals("Andel av perfekt", perfect.title)
        assertEquals("Batteriets egen styrning 77 % av perfekt · SpotNav Balanserad 74 %", perfect.blocks.filterIsInstance<DetailBlock.Headline>().single().text)
        val notes = perfect.notes()
        assertTrue(notes.contains("Perfekt: bästa möjliga i efterhand, får sälja (Max vinst-regler)."))
        assertTrue(notes.contains("1 ofullständigt dygn räknas inte med."))
        assertFalse(notes.any { it.startsWith("Räknat med det som fanns kvar") })
        val shares = perfect.blocks.filterIsInstance<DetailBlock.Share>()
        assertEquals(listOf("own", "balanced", "max_profit", "self_consumption").size, shares.size)
        assertEquals(listOf("77 %", "74 %", "69 %", "55 %"), shares.map { it.figure })
        assertEquals(77.0, shares[0].percent!!, 0.5)
        assertEquals("vald", shares[1].inForce)
        // One table of what each way saved and the battery's work: a block per way, the perfect plan last.
        val table = perfect.blocks.filterIsInstance<DetailBlock.Ways>().single()
        assertEquals(listOf("Sparat", "Cykler", "Växlingar", "Från nätet", "Till nätet"), table.head)
        assertEquals(
            listOf(
                "Batteriets egen styrning" to listOf("72 kr", "3,69", "36", "81,0", "3,6"),
                "SpotNav Balanserad" to listOf("69 kr", "3,80", "18", "112,5", "0,0"),
                "SpotNav Max vinst" to listOf("64 kr", "4,91", "27", "135,0", "49,5"),
                "SpotNav Egenförbrukning" to listOf("52 kr", "2,68", "9", "0,0", "0,0"),
                "Perfekt" to listOf("94 kr", "4,02", "18", "126,0", "54,0")
            ),
            table.groups
        )
        assertTrue(notes.any { it.contains("nästa dygns medelpris för köpt el") })
        assertTrue(notes.any { it.contains("minst 5 kr per dygn") })
        // The battery against no battery stands under Money too, with another sum: said side by side with why.
        assertTrue(
            notes.contains(
                "Batteriets egen styrning sparade 72 kr här, medan Batteriet sparade under Pengar visar 52 kr. Båda är mot inget batteri, men här räknas bara hela dygn (9) och det som fanns kvar i batteriet vid midnatt; under Pengar räknas alla mätta dygn (13) och inte det som fanns kvar."
            )
        )
        assertTrue(notes.contains("Battericeller: medel 21,8 °C, högst 27,5 °C, 15 min över 30 °C (åtta dygn)."))
    }

    @Test fun thePerfectPlanSaysItsRulesAClampedShareAndAWayTooCloseToCompare() {
        val month = history("villa_month.json")
        val perfect = month.review!!.perfect!!
        fun shown(change: (HouseHistory.Perfect) -> HouseHistory.Perfect) =
            sections(month.copy(review = month.review!!.copy(perfect = change(perfect)))).section("perfect")!!
        assertTrue(shown { it.copy(rules = "no_sell") }.notes().contains("Perfekt: bästa möjliga i efterhand, utan försäljning (inget gick ut på nätet)."))
        assertTrue(shown { it.copy(rules = "mode") }.notes().contains("Perfekt: bästa möjliga i efterhand, med samma regler."))
        assertTrue(shown { it }.notes().contains("Det som fanns kvar vid midnatt räknas med dygnets eget pris för en av dygnen, tills nästa dygns priser finns."))
        val clamped = shown { it.copy(clamped = listOf("own"), shares = it.shares + ("own" to 100.0) + ("max_profit" to null)) }
        assertTrue(clamped.notes().contains("Batteriets egen styrning: över 100 %, visas som 100 % (modellens batteri är idealt)."))
        assertEquals("för liten skillnad att jämföra", clamped.blocks.filterIsInstance<DetailBlock.Share>().first { it.name == "SpotNav Max vinst" }.figure)
        // A way that did not run on these days has no bar and no block; no figure of the battery's own, no note on it.
        val none = shown { it.copy(savings = it.savings + ("max_profit" to null) + ("own" to null), shares = it.shares + ("max_profit" to null) + ("own" to null)) }
        assertNull(none.blocks.filterIsInstance<DetailBlock.Ways>().single().groups.firstOrNull { it.first == "SpotNav Max vinst" })
        assertFalse(none.notes().any { it.contains("sparade") && it.contains("här, medan") })
    }

    @Test fun aWayThatLostMoneySaysItsLossNotZeroPercentAndIsNoClampedShare() {
        val month = history("villa_month.json")
        val perfect = month.review!!.perfect!!
        val lost = perfect.copy(savings = perfect.savings + ("own" to -4720.0), shares = perfect.shares + ("own" to 0.0), clamped = listOf("own"))
        val shown = sections(month.copy(review = month.review!!.copy(perfect = lost))).section("perfect")!!
        assertEquals("förlust 47 kr", shown.blocks.filterIsInstance<DetailBlock.Share>().first { it.name == "Batteriets egen styrning" }.figure)
        assertFalse(shown.notes().any { it.contains("över 100 %") })
        // Clamped at 100 % is still said.
        val over = perfect.copy(shares = perfect.shares + ("own" to 100.0), clamped = listOf("own"))
        assertTrue(sections(month.copy(review = month.review!!.copy(perfect = over))).section("perfect")!!.notes().any { it.contains("över 100 %") })
        // A day the battery lost money on says so in its share cell.
        val json = HaFixtures.json("house_history/villa_month.json").apply {
            getJSONObject("review").getJSONArray("by_day").getJSONObject(5).put("share_note", "loss").put("own_share", JSONObject.NULL)
        }
        val day = (HouseHistoryRead.decode(json) as SiteRead.Read).value.review!!.byDay[5]
        assertEquals("loss", day.shareNote)
        val table = sections((HouseHistoryRead.decode(json) as SiteRead.Read).value).section("days")!!.blocks.filterIsInstance<DetailBlock.Table>().single()
        assertEquals("förlust", table.rows[5].last())
    }

    @Test fun whileOnlyIncompleteDaysHaveAPerfectPlanTheModesStandInWithItsNote() {
        val month = history("villa_month.json")
        val none = mapOf("own" to null, "self_consumption" to null, "balanced" to null, "max_profit" to null)
        val partial = month.review!!.perfect!!.copy(days = 0, partial = 2, saving = null, shares = none, savings = none, provisional = 0, work = none + ("perfect" to null))
        val sections = sections(month.copy(review = month.review!!.copy(perfect = partial)))
        assertNull(sections.section("perfect"))
        val modes = sections.section("modes")!!
        assertTrue(modes.figures().first { it.name == "SpotNav Balanserad · vald" }.value.startsWith("20 kr"))
        // Self-consumption is the zero the others are measured against, not a row.
        assertNull(modes.figures().firstOrNull { it.name.contains("Egenförbrukning") })
        assertEquals("Batteriets egen styrning", modes.figures().last().name)
        assertTrue(modes.notes().contains("Bara 2 ofullständiga dygn: inget att jämföra än."))
        assertTrue(modes.blocks.none { it is DetailBlock.Ways })
        assertTrue(modes.notes().any { it.startsWith("Battericeller") })
        // On a day, before the first perfect plan, the same: and its figures.
        val day = history("villa_day.json") { h -> h.copy(review = h.review!!.copy(perfect = null)) }
        assertTrue(sections(day).section("modes")!!.figures().first { it.name.startsWith("SpotNav Max vinst") }.value.contains("2,00 kr"))
    }

    @Test fun aDayHasItsMidnightNoteItsForecastErrorsAndNoDayByDayList() {
        val day = sections(history("villa_day.json"))
        assertTrue(day.section("perfect")!!.notes().contains("Räknat med det som fanns kvar i batteriet vid midnatt."))
        val forecast = day.section("forecast")!!
        assertEquals("Husets last" to "±0,42 kW (1 d)", forecast.figures().first().let { it.name to it.value })
        assertEquals(listOf("Husets last", "Sol", "Sol, korrigerad", "Batterinivå"), forecast.figures().map { it.name })
        assertTrue(forecast.notes().single().startsWith("Medelvärdet av varje granskat dygns medelfel"))
        assertNull(day.section("days"))
        // Without any forecast reviewed, the sentence says so.
        val none = sections(history("villa_month.json") { it.copy(forecast = HouseHistory.Forecast(null, null, null, null)) }).section("forecast")!!
        assertTrue(none.figures().isEmpty() && none.notes().single().startsWith("Ingen prognos granskades"))
    }

    @Test fun aMonthHasADayByDayTableWithTheShareAndWhyThereIsNone() {
        val days = sections(history("villa_month.json")).section("days")!!
        val table = days.blocks.filterIsInstance<DetailBlock.Table>().single()
        assertEquals(listOf("Dag", "Batteri", "Plan", "Eget", "Av perfekt"), table.head)
        assertEquals(13, table.rows.size)
        assertEquals(5, table.rows.first().size)
        fun share(date: String) = table.rows.first { it[0].endsWith(date.drop(8).trimStart('0').let { d -> " $d nov" }) || it[0].contains(" ${date.takeLast(2).trimStart('0')} nov") }.last()
        assertEquals("–", share("2026-11-03"))
        assertEquals("78 %", share("2026-11-06"))
        // A day under 90 % measured, and one whose perfect plan saved under 5 kr, say why; muted.
        assertEquals("ofullständigt dygn", share("2026-11-07"))
        assertEquals("för liten skillnad", share("2026-11-09"))
        assertTrue(table.mutedCells.isNotEmpty())
        assertTrue(days.notes().single().startsWith("Batteri: mot inget batteri."))
    }

    @Test fun theSavingsSectionHasTheTotalAndARowPerPartOverTheDaysThatHaveIt() {
        val saving = sections(history("villa_month.json")).section("spotnav-saving")!!
        assertEquals(listOf("Totalt", "Bil", "Värme (uppskattat)"), saving.figures().map { it.name })
        assertEquals("21 kr", saving.figures()[0].value)
        assertEquals("20 kr", saving.figures()[1].value)
        // The heat on two of the seven days, said to be an estimate; no battery row (its own control).
        assertTrue(saving.figures()[2].value.endsWith("2 dygn"))
        assertNull(sections(history("villa_month.json") { it.copy(saving = null) }).section("spotnav-saving"))
    }

    @Test fun lastItSaysHowItIsCountedWhatSpotNavsSavingIsComparedWithAndHowTheFlowIsAllocated() {
        val answer = history("villa_month.json")
        fun how(h: HouseHistory, withHouse: Boolean = false) = sections(h, withHouse = withHouse).section("how")!!.notes()
        val lines = how(answer)
        assertEquals("Siffrorna visar vad som mättes och vad planerna skulle ha gjort.", lines[0])
        assertTrue(lines.contains("Det SpotNav sparade jämförs med:"))
        // The battery ran under its own control: said, and no line for SpotNav's own battery part.
        assertTrue(lines.any { it.startsWith("Batteri: det styrdes av sig självt") })
        assertFalse(lines.any { it.startsWith("Batteri: det batteriet tjänade medan SpotNav styrde det.") })
        assertTrue(lines.any { it.startsWith("Bil: att ladda direkt vid inkoppling") })
        assertTrue(lines.any { it.startsWith("Värme (uppskattat)") })
        assertTrue(lines.any { Regex("varje (timme|kvart) går solen").containsMatchIn(it) })
        // The car counted from the day the house says.
        val since = house().saving!!.carSince
        if (since != null) assertTrue(how(answer, withHouse = true).any { it.startsWith("Bil:") && it.contains("Bilen räknas från") })
        // Under SpotNav's control the battery's line is its own; without the car and the heat, no line for them.
        val controlled = answer.copy(ownControl = null, saving = answer.saving!!.copy(battery = 500.0, car = null, heat = null))
        val own = how(controlled)
        assertTrue(own.contains("Batteri: det batteriet tjänade medan SpotNav styrde det."))
        assertFalse(own.any { it.startsWith("Bil:") || it.startsWith("Värme (uppskattat)") })
        // No figure of the period stands here. Without anything saved and without a flow: the one sentence.
        assertFalse(lines.joinToString(" ").contains(Regex("\\d kr")))
        assertEquals(1, how(answer.copy(saving = null, ownControl = null, energyFlow = null)).size)
    }

    @Test fun theSourcesListWhereTheFiguresComeFromAndWhatWasAssumed() {
        val how = sections(history("villa_month.json"), withHouse = true).section("how")!!
        val sources = how.blocks.filterIsInstance<DetailBlock.Sources>().single()
        assertEquals("Källor", sources.heading)
        assertTrue(sources.facts.contains("Väder: weather.forecast_home"))
        assertTrue(sources.facts.contains("Utetemperatur: sensor.nibe_bt1_outdoor_temperature"))
        assertTrue(sources.facts.contains("Helgdagar: calendar.sverige"))
        assertTrue(sources.facts.contains("Temperatur i prognosen: av (för få timmar med temperatur än)"))
        assertTrue(sources.facts.any { it.startsWith("Solprognos: Forecast.Solar") && it.contains("korrigeringen lär sig") })
        assertTrue(sources.facts.any { it.startsWith("Värmepumpens mätare: Värme Effekt") && it.contains("vald i Inställningar") })
        assertTrue(sources.facts.any { it.startsWith("Husprognos som värme plus resten: ±0,04 kW mot ±0,09 kW för helheten (168 h") })
        assertTrue(sources.facts.any { it.startsWith("Batteri: Sigenergy SigenStor") })
        assertTrue(sources.notes.contains("Batteriet styrs av sin egen optimerare. SpotNav planerar bredvid bara för att jämföra och skriver inget till det.") || sources.notes.any { it.startsWith("Batteriet styrs av sin egen optimerare") })
        assertTrue(sources.notes.any { it.startsWith("Antaget tills vidare: batteriets lägsta nivå") })
        // Without the house's answer there are no sources.
        assertTrue(sections(history("villa_month.json")).section("how")!!.blocks.none { it is DetailBlock.Sources })
    }

    @Test fun whatIsMissingIsSaidNoMetersNoPricesNoReview() {
        val alone = history("house_alone_year.json")
        val sections = sections(alone, "en")
        assertEquals(12, sections.section("bars")!!.bars().bars.size)
        assertTrue(sections.section("money")!!.notes().contains(CardStringsXml.words("en").say("hist.house.noReview")))
        val bare = alone.copy(energyFlow = null, buckets = emptyList(), cost = null, money = null)
        val empty = sections(bare, "en")
        assertEquals("no-meters", empty.first().key)
        assertEquals(CardStringsXml.words("en").say("hist.house.noMeters"), empty.first().notes().single())
        assertEquals(CardStringsXml.words("en").say("hist.house.noPriceSource"), empty.section("money")!!.notes().first())
    }

    @Test fun everyWordOfTheSheetIsInAllNineLanguages() {
        for (language in CardStringsXml.LANGUAGES) {
            for (name in listOf("villa_month.json", "villa_day.json", "villa_year.json", "house_alone_year.json")) {
                val words = said(sections(history(name), language, withHouse = true))
                assertTrue("$language $name", words.isNotEmpty())
                words.forEach { assertFalse("$language $name: $it", it.contains(Regex("\\{\\w+\\}")) || it.contains(Regex("\\b(hist|perfect|house)\\.[a-z]"))) }
            }
            // The paths the fixtures do not take: a day before the first perfect plan, a worse bar, a partial perfect.
            val month = history("villa_month.json")
            val none = mapOf("own" to null, "self_consumption" to null, "balanced" to null, "max_profit" to null)
            val partial = month.review!!.perfect!!.copy(days = 0, partial = 2, saving = null, shares = none, savings = none, work = none + ("perfect" to null))
            val shown = said(sections(month.copy(review = month.review!!.copy(perfect = partial), buckets = month.buckets.map { it.copy(batterySaving = -5.0) }), language, withHouse = true))
            shown.forEach { assertFalse("$language: $it", it.contains(Regex("\\{\\w+\\}"))) }
        }
        // The count words: one and many, in every language.
        for (language in CardStringsXml.LANGUAGES) {
            assertEquals("one", CountWords.form(language, 1))
            assertEquals("other", CountWords.form(language, 2))
            assertTrue(CountWords.word(language, 8).isNotEmpty() && CountWords.word(language, 8) != "8")
            assertEquals("13", CountWords.word(language, 13))
        }
        assertEquals("one", CountWords.form("fr", 0))
    }

    @Test fun theLegendOfTheCostBarsIsNamedWhereTwoPartsStand() {
        val bars = sections(history("villa_month.json")).section("bars")!!.bars()
        assertEquals(2, bars.series.size)
        assertNotNull(bars.hint)
    }
}
