package se.sensnology.spotnav.ui.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.ui.common.CardStrings
import java.util.Locale

/** The grid tariff's rows beside the grid fee, worded as the card's `grid-tariff.ts` words them. */
class GridTariffTextTest {
    private fun text(language: String = "sv") = GridTariffText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun block(percent: String, high: String, window: String?) = DashboardGridTariff.parse(JSONObject("""
        {"entry_id": "price_a", "write_revision": 3, "transfer_percent": $percent, "transfer_high": $high,
         "high_load_window": ${window ?: "null"}, "writable": true}
    """))!!

    private val off = """{"policy": "off", "explicit_value": null, "effective_value": null, "unit": "%"}"""
    private val percent = """{"policy": "manual", "explicit_value": 5.5, "effective_value": 5.5, "unit": "%"}"""
    private val high = """{"policy": "manual", "explicit_value": 53.0, "effective_value": 53.0, "unit": "öre/kWh"}"""
    private val highOff = """{"policy": "off", "explicit_value": 53.0, "effective_value": null, "unit": "öre/kWh"}"""
    private val included = """{"policy": "included", "explicit_value": null, "effective_value": null, "unit": "%"}"""
    private val window = """{"days": "weekdays", "from_month": 11, "to_month": 3, "from_hour": 7, "to_hour": 19}"""

    @Test fun everyCardKeyIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val table = CardStringsXml.strings(language)
            for (key in CardStrings.IDS.keys) assertTrue("$language has no $key", !table[CardStrings.resourceName(key)].isNullOrBlank())
        }
    }

    @Test fun bothFeesAndTheirHours() {
        val rows = text().rows(block(percent, high, window))
        assertEquals(listOf("Nätavgift, % av spotpriset" to "5,5 %", "Nätavgift under höglasttid" to "53 öre/kWh", "Höglasttid" to "Vardagar 07–19, nov–mar"),
            rows.map { it.label to it.value })
        assertTrue(rows.none { it.included })
    }

    @Test fun theHoursAreLeftOutWhileTheFeeIsOffAndNoneAreStored() {
        val rows = text().rows(block(off, highOff, null))
        assertEquals(listOf("Av", "Av"), rows.map { it.value })
        assertEquals("Inte inställt", text().rows(block(off, high.replace("53.0, \"effective", "53.0, \"effective"), null)).last().value)
        // Stored hours are shown although the fee is off.
        assertEquals(3, text().rows(block(off, highOff, window)).size)
    }

    @Test fun aPriceThatIncludesTheGridFeeLocksTheRows() {
        val rows = text().rows(block(included, included.replace("%", "öre/kWh"), window))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.included && it.value == "Ingår i priset" })
    }

    @Test fun windowsAreWordedAsTheCardDoes() {
        assertEquals("Mon–Sat 22–06, all year", text("en").windowText(HighLoadWindow("mon_sat", 1, 12, 22, 6)))
        assertEquals("Alla dagar 06–22, jan", text().windowText(HighLoadWindow("all", 1, 1, 6, 22)))
    }
}
