package se.sensnology.spotnav.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The summary rows' wording exists in all five locales and never names an entity. */
class SummaryStringsTest {
    private val names = listOf(
        "charger_start_stop_label", "charger_current_ocpp", "charger_current_number_unnamed",
        "charger_current_easee", "charger_energy_label", "charger_energy_automatic", "site_main_fuse_label",
        "site_measurement_label", "site_measurement_direct", "site_measurement_derived", "site_battery_label"
    )

    private fun text(dir: String): String =
        listOf("src/main/res", "app/src/main/res").map { File(it, "$dir/strings.xml") }.first { it.exists() }.readText()

    @Test fun everyLocaleStatesEveryRow() {
        for (dir in listOf("values", "values-sv", "values-nb", "values-da", "values-fi", "values-de", "values-nl", "values-es", "values-fr")) {
            val xml = text(dir)
            for (name in names) assertTrue("$dir $name", xml.contains("<string name=\"$name\">"))
        }
    }

    @Test fun theNumberWordingNamesNoEntityAtAll() {
        assertFalse(text("values").contains("name=\"charger_current_number\""))
        assertFalse(text("values").contains("entity_id"))
    }
}
