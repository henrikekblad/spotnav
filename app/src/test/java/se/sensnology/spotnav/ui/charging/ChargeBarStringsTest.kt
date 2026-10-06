package se.sensnology.spotnav.ui.charging

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The charge bar's line in all five locales, put together as the card puts it. */
class ChargeBarStringsTest {
    private val dirs = mapOf("en" to "values", "sv" to "values-sv", "nb" to "values-nb", "da" to "values-da", "fi" to "values-fi")
    private val names = listOf(
        "charge_bar_done", "charge_bar_of_target", "charge_bar_ends", "charge_bar_charging",
        "charge_bar_charging_power", "charge_bar_description"
    )

    private fun xml(dir: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$dir/strings.xml") }.first { it.exists() }.readText()

    private fun string(dir: String, name: String): String {
        val match = Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(xml(dir))
            ?: throw AssertionError("$dir has no $name")
        return match.groupValues[1].replace("\\'", "'")
    }

    private fun words(language: String): ChargeBarText.Words {
        val dir = dirs.getValue(language)
        return ChargeBarText.Words(
            done = string(dir, "charge_bar_done"),
            ofTarget = string(dir, "charge_bar_of_target"),
            ends = string(dir, "charge_bar_ends"),
            charging = string(dir, "charge_bar_charging"),
            chargingPower = string(dir, "charge_bar_charging_power"),
            line = string("values", "charge_bar_line")
        )
    }

    private fun bar(basis: ChargeBarBasis, percent: Int?) = ChargeBar(basis, percent, null, null, true)

    private fun say(language: String, basis: ChargeBarBasis, percent: Int?, clock: String? = null, kw: String? = null) =
        ChargeBarText.line(bar(basis, percent), words(language), clock, kw, Locale(language))

    @Test fun everyLocaleStatesEveryWord() {
        for ((language, dir) in dirs) {
            for (name in names) assertTrue("$language $name", string(dir, name).isNotBlank())
        }
    }

    @Test fun swedishInTheOwnersWords() {
        assertEquals("62 % klart · klart ca 14:35", say("sv", ChargeBarBasis.KWH, 62, "14:35"))
        assertEquals("62 % klart", say("sv", ChargeBarBasis.FILL, 62))
        assertEquals("93 % av målet · klart ca 14:35", say("sv", ChargeBarBasis.TARGET, 93, "14:35"))
        assertEquals("Laddar · 11 kW", say("sv", ChargeBarBasis.OPEN, null, kw = "11"))
        assertEquals("Laddar", say("sv", ChargeBarBasis.OPEN, null))
    }

    @Test fun englishNorwegianDanishAndFinnish() {
        assertEquals("62 % done · done about 2:35 PM", say("en", ChargeBarBasis.CAR_LIMIT, 62, "2:35 PM"))
        assertEquals("93 % of target", say("en", ChargeBarBasis.TARGET, 93))
        assertEquals("Charging · 3.7 kW", say("en", ChargeBarBasis.OPEN, null, kw = "3.7"))
        assertEquals("62 % ferdig · ferdig ca. 14:35", say("nb", ChargeBarBasis.KWH, 62, "14:35"))
        assertEquals("62 % færdig · færdig ca. 14:35", say("da", ChargeBarBasis.KWH, 62, "14:35"))
        assertEquals("62 % valmis · valmis noin klo 14:35", say("fi", ChargeBarBasis.KWH, 62, "14:35"))
        assertEquals("Ladataan · 3,7 kW", say("fi", ChargeBarBasis.OPEN, null, kw = "3,7"))
    }
}
