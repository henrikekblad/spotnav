package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings names no entity: a charger's, a car's and a site's setup reads as short status values
 * ("Aktiv", "Styrs", "Finns", "Saknas"), in every language.
 */
class SetupWordingTest {
    private fun xml(directory: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()

    private fun text(xml: String, name: String): String? = Regex("<string name=\"$name\"[^>]*>(.*?)</string>").find(xml)?.groupValues?.get(1)

    private val statusValues = listOf(
        "setup_active", "setup_missing", "setup_present", "charger_current_kept", "charger_current_number_unnamed",
        "charger_current_ocpp", "charger_current_easee", "charger_energy_automatic", "charger_energy_chosen",
        "site_measurement_direct", "site_measurement_derived"
    )

    @Test fun everySetupValueIsShortInEveryLanguage() {
        for (directory in listOf("values", "values-sv", "values-da", "values-nb", "values-fi")) {
            val strings = xml(directory)
            for (name in statusValues) {
                val value = text(strings, name)
                assertTrue("$directory has $name", value != null)
                assertTrue("$directory $name is short: $value", value!!.length <= 24)
            }
            // The entity's own name is never part of a value.
            assertFalse("$directory still names a number entity", strings.contains("name=\"charger_current_number\""))
            assertFalse("$directory still has none found", strings.contains("name=\"identify_source_not_found\""))
        }
    }

    @Test fun theSwedishWordsAreTheOwners() {
        val sv = xml("values-sv")
        assertEquals("Aktiv", text(sv, "setup_active"))
        assertEquals("Saknas", text(sv, "setup_missing"))
        assertEquals("Finns", text(sv, "setup_present"))
        assertEquals("Styrs inte", text(sv, "charger_current_kept"))
        assertEquals("Styrs", text(sv, "charger_current_number_unnamed"))
        assertEquals("Hittad automatiskt", text(sv, "charger_energy_automatic"))
        assertEquals("Vald", text(sv, "charger_energy_chosen"))
        assertEquals("Per fas, direkt", text(sv, "site_measurement_direct"))
    }
}
