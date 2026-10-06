package se.sensnology.spotnav.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The short wording this round asks for, in every language: max periods on one line, the slider's marks. */
class RoundThreeWordingTest {
    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    private fun text(directory: String, name: String) =
        Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(xml(directory))?.groupValues?.get(1)

    @Test fun maxPeriodsIsShortInEveryLanguage() {
        assertEquals("Max laddperioder", text("values-sv", "max_charging_periods"))
        assertEquals("Maks. ladeperioder", text("values-da", "max_charging_periods"))
        assertEquals("Maks. ladeperioder", text("values-nb", "max_charging_periods"))
        assertEquals("Latausjaksoja enint.", text("values-fi", "max_charging_periods"))
        assertTrue(text("values", "max_charging_periods")!!.length <= 20)
    }

    @Test fun theTargetSlidersMarksHaveShortWordsInEveryLanguage() {
        for (directory in listOf("values", "values-sv", "values-da", "values-nb", "values-fi")) {
            for (name in listOf("target_mark_now", "target_mark_limit", "price_suggestion")) {
                val value = text(directory, name)
                assertTrue("$directory $name", value != null && value.isNotBlank())
            }
            assertTrue(text(directory, "target_mark_now")!!.length <= 8)
            assertTrue(text(directory, "target_mark_limit")!!.length <= 8)
        }
        assertEquals("nu", text("values-sv", "target_mark_now"))
        assertEquals("gräns", text("values-sv", "target_mark_limit"))
    }
}
