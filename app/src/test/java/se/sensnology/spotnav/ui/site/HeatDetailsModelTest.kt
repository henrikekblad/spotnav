package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.heat.HeatHistoryRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The details sheet's sections, every one open, as the card words them (`heat-history.ts`). */
class HeatDetailsModelTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun history(name: String): HeatHistory = (HeatHistoryRead.decode(HaFixtures.json("heat_history/$name")) as SiteRead.Read).value

    private fun sections(name: String, language: String = "sv") = HeatDetailsModel.sections(text(language), history(name)).associateBy { it.key }

    private fun DetailSection.figures() = rows.filterIsInstance<DetailRow.Figure>().associate { it.key to it.value }

    @Test fun aDayHasEverySection() {
        assertEquals(listOf("use", "degree-days", "comfort", "shadow", "review", "model", "runs"), HeatDetailsModel.sections(text(), history("zones_day.json")).map { it.key })
    }

    @Test fun aMonthHasNoReviewAndNoRuns() {
        assertEquals(listOf("use", "degree-days", "comfort", "shadow", "model"), HeatDetailsModel.sections(text(), history("zones_month.json")).map { it.key })
    }

    @Test fun theUseSaysTheTotalTheZonesTheHotWaterAndTheCost() {
        val use = sections("zones_month.json").getValue("use")
        val figures = use.figures()
        assertEquals(listOf("total", "zones", "hot-water", "cost", "immersion"), figures.keys.toList())
        assertTrue(figures.getValue("total").endsWith(" kWh"))
        assertTrue(use.rows.any { it is DetailRow.Note && it.key == "split-days" })
        assertTrue(use.rows.any { it is DetailRow.Note && it.key == "zones-from" } || history("zones_month.json").energy!!.zonesFrom == null)
        // Where there is no meter, one line says so; with no energy, another.
        val noMeter = HeatDetailsModel.sections(text(), history("zones_month.json").copy(meter = null)).first { it.key == "use" }
        assertEquals(listOf("no-meter"), noMeter.rows.map { it.key })
        val none = HeatDetailsModel.sections(text(), history("zones_month.json").let { it.copy(energy = it.energy!!.copy(hours = 0)) }).first { it.key == "use" }
        assertEquals(listOf("no-energy"), none.rows.map { it.key })
    }

    @Test fun theDegreeDaysHaveTheirTrendAsBars() {
        val degree = sections("zones_year.json").getValue("degree-days")
        val trend = degree.rows.filterIsInstance<DetailRow.Trend>().single()
        assertEquals(12, trend.bars.size)
        assertTrue(trend.bars.any { it.lanes.isNotEmpty() })
        assertNotNull(degree.figures()["per-degree-day"])
    }

    @Test fun aDayHasItsReviewAndItsRunsOneByOne() {
        val day = sections("zones_day.json")
        assertEquals(listOf("review-relay", "review-relay-comfort", "review-plan", "review-plan-comfort"), day.getValue("review").figures().keys.toList())
        val runs = day.getValue("runs").rows.filterIsInstance<DetailRow.Figure>()
        assertEquals(3, runs.size)
        assertTrue(runs.first().name, Regex("^\\d\\d:\\d\\d–\\d\\d:\\d\\d$").matches(runs.first().name))
        assertTrue(runs.first().value.contains(" kWh · "))
    }

    @Test fun theShadowAndTheModelSayWhatTheyAre() {
        val month = sections("zones_month.json")
        assertTrue(month.getValue("shadow").figures()["value"]!!.endsWith(" kr"))
        assertTrue(month.getValue("model").figures()["model-error"]!!.startsWith("±"))
        val h = history("zones_month.json")
        val without = HeatDetailsModel.sections(text(), h.copy(shadow = null, model = null)).associateBy { it.key }
        assertEquals(listOf("no-shadow"), without.getValue("shadow").rows.map { it.key })
        assertEquals(listOf("no-model"), without.getValue("model").rows.map { it.key })
        // Without a meter the shadow and the model are not offered at all.
        assertNull(HeatDetailsModel.sections(text(), h.copy(meter = null)).firstOrNull { it.key == "shadow" })
    }

    @Test fun comfortNamesTheColdestRoomWithoutItsTemperatureWord() {
        val comfort = sections("zones_day.json").getValue("comfort").figures()
        assertEquals("Övervåning", comfort["coldest"])
        assertTrue(comfort.getValue("below").contains("/"))
    }

    @Test fun everyWordIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val t = text(language)
            for (name in listOf("zones_day.json", "zones_month.json", "zones_year.json", "meter_only_year.json", "villa_day.json")) {
                val h = history(name)
                for (variant in listOf(h, h.copy(shadow = null, model = null, comfort = null, degreeDays = null, immersion = null, runs = emptyList(), review = null), h.copy(meter = null))) {
                    val said = HeatDetailsModel.sections(t, variant).flatMap { s ->
                        listOf(s.title) + s.rows.flatMap { r ->
                            when (r) {
                                is DetailRow.Figure -> listOf(r.name, r.value)
                                is DetailRow.Note -> listOf(r.text)
                                is DetailRow.Trend -> listOf(r.title, r.unit) + r.bars.map { it.words }
                            }
                        }
                    } + HeatDetailsModel.footnote(t)
                    for (line in said) assertFalse("$language $name: $line", line.contains("hist.") || line.contains("heat.") || line.contains("house.") || line.contains("{"))
                }
            }
        }
        assertTrue(text("en").words.say("hist.title.heat").isNotEmpty())
    }
}
