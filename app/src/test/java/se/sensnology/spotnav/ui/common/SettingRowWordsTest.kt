package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A settings value row as a screen reader says it: the label and the value, and that it can be changed
 * when it can; in every language the app speaks.
 */
class SettingRowWordsTest {
    @Test fun aRowNamesItsLabelAndValueAndWhetherItCanBeChanged() {
        val changeable = { label: String, value: String -> "$label, $value, can be changed" }
        assertEquals("Theme, Dark, can be changed", SettingRowWords.description("Theme", "Dark", true, changeable))
        assertEquals("Main fuse, 25 A", SettingRowWords.description("Main fuse", "25 A", false, changeable))
    }

    @Test fun everyLanguageSaysARowCanBeChanged() {
        for (directory in listOf("values", "values-sv", "values-da", "values-nb", "values-fi")) {
            val xml = listOf("src/main/res", "app/src/main/res").map { File(it, "$directory/strings.xml") }.first { it.exists() }.readText()
            val text = Regex("<string name=\\"setting_row_changeable\\">(.*?)</string>").find(xml)?.groupValues?.get(1)
            assertTrue("$directory has setting_row_changeable", text != null && "%1\\$s" in text && "%2\\$s" in text)
        }
    }
}
