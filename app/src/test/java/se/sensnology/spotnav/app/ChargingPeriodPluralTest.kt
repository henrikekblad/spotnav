package se.sensnology.spotnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The period count in all five locales, as the string resources actually hold it. */
class ChargingPeriodPluralTest {
    /** The five locales the app ships, and the directory each one's strings live in. */
    private val locales = listOf(
        "en" to "values",
        "sv" to "values-sv",
        "nb" to "values-nb",
        "da" to "values-da",
        "fi" to "values-fi"
    )

    /** The two visible forms the plan asks for, per locale. */
    private val required = mapOf(
        "en" to ("%1\$d period" to "%1\$d periods"),
        "sv" to ("%1\$d period" to "%1\$d perioder"),
        "nb" to ("%1\$d periode" to "%1\$d perioder"),
        "da" to ("%1\$d periode" to "%1\$d perioder"),
        "fi" to ("%1\$d jakso" to "%1\$d jaksoa")
    )

    /** What a reader sees for a count of one and of two: the same table, substituted. */
    private val visible = mapOf(
        "en" to ("1 period" to "2 periods"),
        "sv" to ("1 period" to "2 perioder"),
        "nb" to ("1 periode" to "2 perioder"),
        "da" to ("1 periode" to "2 perioder"),
        "fi" to ("1 jakso" to "2 jaksoa")
    )

    private fun strings(directory: String) = File("src/main/res/$directory/strings.xml").let { file ->
        assertTrue("${file.absolutePath} must exist", file.isFile)
        file.readText()
    }

    /** The `<plurals name="charging_period_count">` block of one locale, or `null`. */
    private fun pluralBlock(directory: String): String? =
        strings(directory).substringAfter("<plurals name=\"charging_period_count\">", missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() }
            ?.substringBefore("</plurals>")

    private fun items(directory: String): Map<String, String> =
        Regex("<item quantity=\"(\\w+)\">(.*?)</item>")
            .findAll(pluralBlock(directory) ?: "")
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun everyLocaleHasBothFormsOfTheCount() {
        for ((language, directory) in locales) {
            val items = items(directory)
            assertEquals("$language: exactly one and other", setOf("one", "other"), items.keys)
            assertEquals(
                "$language: the singular",
                required.getValue(language).first,
                items.getValue("one")
            )
            assertEquals(
                "$language: the plural",
                required.getValue(language).second,
                items.getValue("other")
            )
        }
    }

    @Test
    fun theNumberIsInTheTextAndTheFormIsPickedByIt() {
        for ((language, directory) in locales) {
            val items = items(directory)
            // `getQuantityString` chooses a form and then formats it with the arguments the caller
            // passed -- the same `String.format` in the same locale that this uses, so what is
            // asserted here is what the user reads.
            val locale = Locale.forLanguageTag(language)
            val one = String.format(locale, items.getValue("one"), 1)
            val two = String.format(locale, items.getValue("other"), 2)

            assertEquals("$language: one", visible.getValue(language).first, one)
            assertEquals("$language: two", visible.getValue(language).second, two)
        }
    }

    @Test
    fun thePlainStringIsGoneSoNoCallSiteCanFormatItUnawareOfQuantity() {
        // This is a *replacement*: a `<string>` left behind under the same name would still compile
        // at every old `R.string` reference and quietly say "1 perioder".
        for ((_, directory) in locales) {
            val text = strings(directory)
            assertNull(
                "$directory must not still have a plain string of this name",
                Regex("<string name=\"charging_period_count\">").find(text)
            )
            assertEquals(
                "$directory must define the plural exactly once",
                1,
                Regex("<plurals name=\"charging_period_count\">").findAll(text).count()
            )
        }
    }
}
