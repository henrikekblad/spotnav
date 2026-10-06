package se.sensnology.spotnav.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** The car line's words for a car a person chose, in the five languages, as the Home Assistant card has them. */
class CarLineWordingTest {
    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    private fun text(directory: String, name: String): String? =
        Regex("<string name=\"$name\">(.*?)</string>").find(xml(directory))?.groupValues?.get(1)

    @Test fun aCarAPersonChoseReadsChosenManually() {
        val expected = mapOf(
            "values" to "chosen manually",
            "values-sv" to "vald manuellt",
            "values-da" to "valgt manuelt",
            "values-nb" to "valgt manuelt",
            "values-fi" to "valittu käsin"
        )
        for ((directory, words) in expected) {
            assertEquals(directory, words, text(directory, "identify_by_hand"))
            // "Your answer" and "your choice" are gone.
            assertFalse(directory, xml(directory).contains("name=\"identify_by_answer\""))
            assertFalse(directory, xml(directory).contains("name=\"identify_by_manual\""))
        }
    }

    @Test fun thePlanningCardsTargetIsLaddmalInEveryLanguage() {
        val expected = mapOf(
            "values" to "Charge target", "values-sv" to "Laddmål", "values-da" to "Lademål",
            "values-nb" to "Lademål", "values-fi" to "Lataustavoite"
        )
        for ((directory, words) in expected) {
            assertEquals(directory, words, text(directory, "vehicle_target"))
            for (old in listOf("Målladdningsnivå", "Målladningsniveau", "Målladningsnivå", "Tavoiteltu lataustaso", "Target state of charge")) {
                assertFalse("$directory still says $old", xml(directory).contains(old))
            }
        }
    }
}
