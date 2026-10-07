package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Settings leaves rows to speak for themselves: no "changed in Home Assistant" notes, no lines a row does not need. */
class SettingsWordingTest {
    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    @Test fun noLanguageCarriesTheReadOnlyNotes() {
        for (directory in listOf("values", "values-sv", "values-da", "values-nb", "values-fi")) {
            val strings = xml(directory)
            // Gone too: the planned car's line, what the sources are read for (the Home Assistant card's
            // alone) and the AI task (chosen in that card).
            for (gone in listOf(
                "changed_in_home_assistant", "site_active_in_ha", "identify_sources_title", "settings_vehicle_planned_here",
                "identify_sources_help"
            )) {
                assertFalse("$directory still has $gone", strings.contains("name=\"$gone\""))
            }
        }
    }
}
