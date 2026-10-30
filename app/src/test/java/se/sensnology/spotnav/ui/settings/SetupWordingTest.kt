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
        "site_measurement_direct", "site_measurement_derived", "entsoe_key_added", "entsoe_key_not_accepted_value"
    )

    private val languages = listOf("values", "values-sv", "values-da", "values-nb", "values-fi", "values-de", "values-nl", "values-es", "values-fr")

    /** Every word of the ENTSO-E key, its how-to and the one-time explanation (design 5.1-5.3). */
    private val keyWords = listOf(
        "entsoe_key_row", "entsoe_key_added", "entsoe_key_not_accepted_value", "entsoe_source_own_key",
        "area_needs_home_assistant", "entsoe_howto_title", "entsoe_howto_1", "entsoe_howto_2", "entsoe_howto_3",
        "entsoe_howto_4", "entsoe_key_saved", "entsoe_key_checking", "entsoe_key_not_accepted", "entsoe_key_empty",
        "entsoe_key_not_saved", "entsoe_key_remove", "entsoe_why_title", "entsoe_why_body", "entsoe_why_continue",
        "price_need_key", "price_need_rejected", "price_need_add_key", "price_need_replace_key", "price_need_pair",
        "widget_add_key", "price_need_update_ha"
    )

    @Test fun everySetupValueIsShortInEveryLanguage() {
        for (directory in listOf("values", "values-sv", "values-da", "values-nb", "values-fi", "values-de", "values-nl", "values-es", "values-fr")) {
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

    @Test fun theKeyIsWordedInEveryLanguageWithItsLinksIntact() {
        for (directory in languages) {
            val strings = xml(directory)
            for (name in keyWords) assertTrue("$directory has $name", !text(strings, name).isNullOrBlank())
            // The parts the how-to makes tappable are ENTSO-E's own names, the same in every language.
            assertTrue("$directory step 1 names the site", text(strings, "entsoe_howto_1")!!.contains(EntsoeKeyDialog.SITE_NAME))
            val mail = text(strings, "entsoe_howto_2")!!
            assertTrue("$directory step 2 names the address", mail.contains(EntsoeKeyDialog.MAIL_ADDRESS))
            assertTrue("$directory step 2 names the subject", mail.contains(EntsoeKeyDialog.MAIL_SUBJECT))
            assertTrue("$directory step 3 names ENTSO-E's page", text(strings, "entsoe_howto_3")!!.contains("My Account Settings"))
            assertTrue("$directory masks the saved key", text(strings, "entsoe_key_saved")!!.contains("%1\$s"))
        }
    }

    @Test fun theApprovedSwedishTextsAreKeptWordForWord() {
        val sv = xml("values-sv")
        assertEquals("Priserna hämtas nu med din egen nyckel", text(sv, "entsoe_why_title"))
        assertEquals(
            "Elpriserna för nästa dygn får inte längre distribueras från vår server. Därför hämtar SpotNav dem nu direkt " +
                "från ENTSO-E, med en egen nyckel som du skaffar gratis. Det tar några minuter att ansöka, och svaret " +
                "brukar komma inom ett par dagar. Har du Home Assistant kan du i stället koppla appen dit, så behövs ingen nyckel.",
            text(sv, "entsoe_why_body")
        )
        assertEquals("Skaffa en nyckel från ENTSO-E", text(sv, "entsoe_howto_title"))
        assertEquals("Skapa ett konto på transparency.entsoe.eu.", text(sv, "entsoe_howto_1"))
        assertEquals(
            "Mejla transparency@entsoe.eu med ämnet \\\"Restful API access\\\" och skriv kontots e-postadress i mejlet.",
            text(sv, "entsoe_howto_2")
        )
        assertEquals(
            "När ENTSO-E har svarat: logga in, öppna My Account Settings och skapa en nyckel (Generate a new token).",
            text(sv, "entsoe_howto_3")
        )
        assertEquals("Klistra in nyckeln här.", text(sv, "entsoe_howto_4"))
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
