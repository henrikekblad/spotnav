package se.sensnology.spotnav.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.PowerMonthRead
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DemandComponent
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.ui.settings.DemandText
import java.time.LocalDate
import java.util.Locale

/** The history's Power view in words and columns, as the card's `power-view.ts` has them. */
class PowerModelTest {
    private fun model(language: String = "sv"): PowerModel {
        val locale = Locale.forLanguageTag(language)
        val words = CardStringsXml.words(language)
        return PowerModel(words, DemandText(words, locale, DemandText.dateLocale(locale), null), locale)
    }

    private val month: PowerMonthRead.Month get() {
        val json = HaFixtures.json("power_month/pilot_month.json").put("ok", true)
        return (PowerMonthRead.answer(200, json.toString()) as PowerMonthRead.Outcome.Read).month
    }

    private fun part(from: Int, to: Int, days: String = "all", fromMonth: Int = 1, toMonth: Int = 12) =
        DemandComponent(HighLoadWindow(days, fromMonth, toMonth, from, to), 1, null, null)

    @Test fun partsAreNamedByTheirHours() {
        val names = model().partNames(listOf(part(6, 23), part(23, 6)))
        assertEquals(listOf("Dag 06–23", "Natt 23–06"), names.names)
        assertEquals(listOf("dag 06–23", "natt 23–06"), names.inline)
        assertEquals("Alla timmar", model().partNames(listOf(DemandComponent(null, 1, null, null))).names.single())
        assertEquals("Dag 07–19, vardagar, nov–mar", model().partNames(listOf(part(7, 19, "weekdays", 11, 3))).names.single())
        // Two that read the same are numbered.
        assertEquals(listOf("Effektavgift 1", "Effektavgift 2"), model().partNames(listOf(part(6, 23), part(6, 23))).names)
    }

    @Test fun theMonthsFigurePerPart() {
        assertEquals("Dag 06–23: 5,9 kW · Natt 23–06: 5,6 kW".substring(0, 15), model().summary(month)!!.substring(0, 15))
        val one = month.copy(components = month.components.take(1), bars = listOf(null))
        assertEquals("Effektavgift: ingen topp än", model().summary(one))
    }

    @Test fun everyDayOfTheMonthIsAColumnAndAMeasuredOneSaysItsFigures() {
        val columns = model().monthColumns(month)
        assertEquals(30, columns.size)
        assertEquals("1", columns[0].label)
        assertEquals("5", columns[4].label)
        assertEquals("", columns[1].label)
        val first = columns[0]
        assertTrue(first.figures, first.figures.contains("högst 5,8 kW kl. 19:00"))
        assertTrue(first.figures, first.figures.contains("huset 2,3 kW, bilen 3,5 kW"))
        assertTrue(first.figures, first.figures.contains("effektmålet höll nere laddningen"))
        val unmeasured = columns.last { it.kw == null }
        assertTrue(unmeasured.figures, unmeasured.figures.endsWith("inget uppmätt"))
        val counted = columns.first { it.counted.isNotEmpty() }
        assertTrue(counted.figures, counted.figures.contains("räknas för "))
    }

    @Test fun aTappedDaysHoursAreItsColumns() {
        val hours = model().hourColumns(month, LocalDate.of(2026, 9, 1))
        assertEquals(24, hours.size)
        assertEquals("00", hours[0].label)
        assertEquals("06", hours[6].label)
        assertTrue(hours[0].figures, hours[0].figures.startsWith("kl. 00:00: 0,7 kW"))
    }

    @Test fun theScaleLeavesHeadroomOverTheBarsAndTheTarget() {
        assertEquals(8.0, PowerModel.scaleTop(listOf(5.9), 6.0), 0.0)
        assertEquals(1.0, PowerModel.scaleTop(emptyList(), null), 0.0)
        assertEquals(12.0, PowerModel.scaleTop(listOf(9.5), null), 0.0)
    }

    @Test fun theLegendNamesWhatTheChartShows() {
        val legend = model().legend(month, model().monthColumns(month)).map { it.second }
        assertTrue(legend.toString(), legend.containsAll(listOf("Huset", "Bilen", "Effektmål 6,0 kW", "Topp som räknas, dag 06–23")))
    }

    @Test fun offeredWhereHomeAssistantAnswersItAndTheSiteMeasures() {
        val json = HaFixtures.json("webhook/dashboard.json")
        val dashboard = Dashboard.parse(json)
        assertEquals(dashboard.strategyOptions.any { it.wire == "solar" }, PowerModel.offered(dashboard))
        json.getJSONObject("charger").getJSONObject("capabilities").put("get_power_month", false)
        assertFalse(PowerModel.offered(Dashboard.parse(json)))
        assertFalse(PowerModel.offered(null))
    }

    private val imported: PowerMonthRead.Month get() {
        val json = HaFixtures.json("power_month/pilot_imported_month.json").put("ok", true)
        return (PowerMonthRead.answer(200, json.toString()) as PowerMonthRead.Outcome.Read).month
    }

    @Test fun importedDaysAreMarkedWithTheirNoteAndLegendRow() {
        val columns = model().monthColumns(imported)
        val first = columns.first()
        assertTrue(first.imported)
        assertTrue(first.figures, first.figures.endsWith("från Home Assistants statistik"))
        val legend = model().legend(imported, columns)
        assertTrue(legend.any { it.first == PowerModel.LegendKey.Imported && it.second == "Från Home Assistants statistik" })
        assertTrue(model().importedNote(columns)!!.startsWith("Timmarna från Home Assistants statistik är ungefärliga"))
        // A month without imported hours has no note.
        assertEquals(null, model().importedNote(model().monthColumns(month)))
    }

    @Test fun theMonthsPeakIsALineWithItsLegendRow() {
        val columns = model().monthColumns(imported)
        val lifted = columns.first { it.peak != null }
        assertTrue(lifted.figures, lifted.figures.contains("månadens topp "))
        val peak = model().legend(imported, columns).first { it.first == PowerModel.LegendKey.Peak }.second
        assertTrue(peak, peak.startsWith("Månadens topp "))
    }

    @Test fun anOlderAnswerWithoutTheFieldsReadsAsMeasuredAndUnlifted() {
        val day = month.days.first()
        assertEquals(false, day.imported)
        assertEquals(null, day.monthPeakKw)
    }
}
