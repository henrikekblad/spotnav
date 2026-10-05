package se.sensnology.spotnav.ui.charging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kWh slider's Fill words in all five locales, put together as the screen puts them, read against
 * the card's own sentences (`frontend/src/i18n`, `settings.energy.*`), so the app and the card say the
 * same thing in the same words.
 */
class KwhFillStringsTest {
    private val dirs = mapOf("en" to "values", "sv" to "values-sv", "nb" to "values-nb", "da" to "values-da", "fi" to "values-fi")

    private fun xml(dir: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$dir/strings.xml") }.first { it.exists() }.readText()

    private fun string(language: String, name: String): String {
        val match = Regex("<string name=\"$name\">(.*?)</string>").find(xml(dirs.getValue(language)))
            ?: throw AssertionError("$language has no $name")
        return match.groupValues[1].replace("\\'", "'").replace("\\\"", "\"")
    }

    /** What the screen writes: the room's figure, and the car's limit when it is below 100 %. */
    private fun help(language: String, fill: Boolean, kwh: String, percent: String?): String {
        val limit = percent?.let { " " + string(language, "energy_limit_suffix").format("$it %") }.orEmpty()
        return string(language, if (fill) "energy_fill_help" else "energy_room_help").format(kwh, limit)
    }

    @Test fun everyLocaleStatesEveryWord() {
        for (language in dirs.keys) {
            for (name in listOf("energy_full_mark", "energy_fill", "energy_room_help", "energy_fill_help", "energy_limit_suffix")) {
                assertTrue("$language $name", string(language, name).isNotBlank())
            }
        }
    }

    @Test fun englishSaysWhatTheCardSays() {
        assertEquals("full", string("en", "energy_full_mark"))
        assertEquals("Fill", string("en", "energy_fill"))
        assertEquals("9.5 kWh fills the battery.", help("en", false, "9.5", null))
        assertEquals("Charges until the battery is full, 9.5 kWh now.", help("en", true, "9.5", null))
        assertEquals("7.5 kWh fills the battery (to the car's charge limit of 90 %).", help("en", false, "7.5", "90"))
        assertEquals(
            "Charges until the battery is full (to the car's charge limit of 90 %), 7.5 kWh now.",
            help("en", true, "7.5", "90")
        )
    }

    @Test fun swedishSaysWhatTheCardSays() {
        assertEquals("fullt", string("sv", "energy_full_mark"))
        assertEquals("Fyll", string("sv", "energy_fill"))
        assertEquals("Det behövs 9,5 kWh för att fylla batteriet.", help("sv", false, "9,5", null))
        assertEquals("Laddar tills batteriet är fullt, nu 9,5 kWh.", help("sv", true, "9,5", null))
        assertEquals("Det behövs 7,5 kWh för att fylla batteriet (till bilens laddgräns 90 %).", help("sv", false, "7,5", "90"))
        assertEquals("Laddar tills batteriet är fullt (till bilens laddgräns 90 %), nu 7,5 kWh.", help("sv", true, "7,5", "90"))
    }

    @Test fun norwegianDanishAndFinnishSayWhatTheCardSays() {
        assertEquals("fullt", string("nb", "energy_full_mark"))
        assertEquals("Fyll", string("nb", "energy_fill"))
        assertEquals("Det trengs 7,5 kWh for å fylle batteriet (til bilens ladegrense på 90 %).", help("nb", false, "7,5", "90"))
        assertEquals("Lader til batteriet er fullt (til bilens ladegrense på 90 %), nå 7,5 kWh.", help("nb", true, "7,5", "90"))
        assertEquals("fuldt", string("da", "energy_full_mark"))
        assertEquals("Fyld", string("da", "energy_fill"))
        assertEquals("Der skal bruges 7,5 kWh for at fylde batteriet (til bilens ladegrænse på 90 %).", help("da", false, "7,5", "90"))
        assertEquals("Lader, til batteriet er fuldt (til bilens ladegrænse på 90 %), nu 7,5 kWh.", help("da", true, "7,5", "90"))
        assertEquals("täynnä", string("fi", "energy_full_mark"))
        assertEquals("Täytä", string("fi", "energy_fill"))
        assertEquals("Akun täyttämiseen tarvitaan 7,5 kWh (auton latausrajaan 90 %).", help("fi", false, "7,5", "90"))
        assertEquals("Lataa, kunnes akku on täynnä (auton latausrajaan 90 %), nyt 7,5 kWh.", help("fi", true, "7,5", "90"))
    }
}
