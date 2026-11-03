package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The import target's help points at the app's departure dialog, where the plan's choice lives, in every language. */
class ImportTargetHelpTest {
    private val folders = listOf("values", "values-sv", "values-da", "values-nb", "values-fi", "values-de", "values-nl", "values-es", "values-fr")

    private fun string(folder: String, name: String): String {
        val file = listOf("src/main/res", "app/src/main/res").map { File(it, "$folder/strings.xml") }.first { it.exists() }
        return Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(file.readText())?.groupValues?.get(1)
            ?: throw AssertionError("$folder has no $name")
    }

    @Test fun theHelpNamesTheDepartureDialogAndNotThePlan() {
        for (folder in folders) {
            val help = string(folder, "import_target_help")
            val departure = string(folder, "plan_card_departure_label")
            assertTrue("$folder: $help", help.contains("; $departure "))
            assertFalse("$folder: $help", help.contains("Plan "))
        }
    }
}
