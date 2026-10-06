package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Settings leaves read-only rows to speak for themselves: no "changed in Home Assistant" notes. */
class SettingsWordingTest {
    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    @Test fun noLanguageCarriesTheReadOnlyNotes() {
        for (directory in listOf("values", "values-sv", "values-da", "values-nb", "values-fi")) {
            val strings = xml(directory)
            for (gone in listOf("changed_in_home_assistant", "site_active_in_ha", "identify_sources_title")) {
                assertFalse("$directory still has $gone", strings.contains("name=\"$gone\""))
            }
            val help = Regex("<string name=\"identify_sources_help\">(.*?)</string>").find(strings)!!.groupValues[1]
            assertFalse("$directory: $help", help.contains("Home Assistant"))
        }
    }
}
