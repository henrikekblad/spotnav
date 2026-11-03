package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.dashboard.DemandComponent
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.ui.common.CardStrings
import java.time.ZoneId
import java.util.Locale

/** The demand charge's rows and the import target's hour, worded as the card's `demand-charge.ts` words them. */
class DemandTextTest {
    private val sv = Locale.forLanguageTag("sv")
    private fun text(language: String = "sv") = DemandText(
        CardStringsXml.words(language), Locale.forLanguageTag(language),
        DemandText.dateLocale(Locale.forLanguageTag(language)), ZoneId.of("Europe/Stockholm")
    )
    private val pilot get() = DashboardGridTariff.parse(HaFixtures.json("grid_tariff/pilot_demand.json"))!!.demand!!

    @Test fun everyCardKeyIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val table = CardStringsXml.strings(language)
            for (key in CardStrings.IDS.keys) {
                assertTrue("$language has no $key", !table[CardStrings.resourceName(key)].isNullOrBlank())
            }
        }
    }

    @Test fun thePilotsTwoPartsAreRowsWithTheirPricesAndMonthAndARowToAddAThird() {
        val rows = text().rows(pilot)
        assertEquals(3, rows.size)
        assertEquals("Effektavgift 1", rows[0].label)
        assertEquals("Högsta timmen, Alla dagar 06–23, hela året", rows[0].value)
        assertEquals("45 / 23 kr/kW (vinter / sommar)", rows[0].lines[0])
        assertTrue(rows[0].lines[1], rows[0].lines[1].startsWith("Den här månaden: 7,0 kW – "))
        assertTrue(rows[0].lines[1], rows[0].lines[1].endsWith("09:00 · 7,0 kW"))
        assertEquals("Ingen topp än den här månaden", rows[1].lines[1])
        assertEquals(null, rows[2].index)
        assertEquals("Lägg till", rows[2].value)
    }

    @Test fun noPartIsOneRowAndThreeHaveNoAddRow() {
        val none = text().rows(pilot.copy(components = emptyList(), perComponent = emptyList()))
        assertEquals(listOf(DemandText.Row(null, "Effektavgift", "Inget", emptyList())), none)
        val one = DemandComponent(null, 3, null, null)
        val three = text().rows(pilot.copy(components = listOf(one, one, one), perComponent = emptyList()))
        assertEquals(3, three.size)
        assertEquals("Snitt av de 3 högsta dagarna, Alla timmar", three[0].value)
        assertEquals(emptyList<String>(), three[0].lines)
        assertNull(text().rows(null).firstOrNull())
    }

    @Test fun aSiteThatCannotMeasureSaysWhyUnderTheLastPart() {
        val rows = text().rows(pilot.copy(available = false, reason = "needs_total_grid_power", perComponent = emptyList()))
        assertEquals("Mäts när anläggningen har nätets totala effekt.", rows[1].lines.last())
    }

    @Test fun theHourAgainstTheImportTarget() {
        assertEquals("Den här timmen: 3,1 av 6,0 kW", text().hourLine(pilot))
        assertEquals("This hour: 3.1 of 6.0 kW", text("en").hourLine(pilot))
        assertEquals("Den här timmen: inte uppmätt än", text().hourLine(pilot.copy(hour = null)))
        assertNull(text().hourLine(pilot.copy(importTargetKw = null)))
        assertEquals("6,0 kW", text().targetText(pilot))
        assertEquals("Av", text().targetText(pilot.copy(importTargetKw = null)))
    }

    @Test fun windowsAndMonthsAreWordedAsTheCardDoes() {
        assertEquals("Vardagar 07–19, nov–mar", text().windowText(HighLoadWindow.SUGGESTED))
        assertEquals("Mon–Sat 22–06, Jan", text("en").windowText(HighLoadWindow("mon_sat", 1, 1, 22, 6)))
    }

    @Test fun aPriceAsTyped() {
        assertNull(DemandText.parsePrice(" "))
        assertEquals(45.5, DemandText.parsePrice("45,5")!!, 0.0)
        assertTrue(DemandText.parsePrice("-1")!!.isNaN())
        assertTrue(DemandText.parsePrice("x")!!.isNaN())
    }
}
