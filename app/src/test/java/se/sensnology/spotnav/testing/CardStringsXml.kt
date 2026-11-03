package se.sensnology.spotnav.testing

import se.sensnology.spotnav.ui.common.CardStrings
import se.sensnology.spotnav.ui.common.CardWords
import java.io.File

/** The card's words as the app's resources hold them in one language, read from `strings.xml` as the screen fills them. */
object CardStringsXml {
    private val folders = mapOf(
        "en" to "values", "sv" to "values-sv", "da" to "values-da", "nb" to "values-nb", "fi" to "values-fi",
        "de" to "values-de", "nl" to "values-nl", "es" to "values-es", "fr" to "values-fr"
    )

    val LANGUAGES: Set<String> = folders.keys

    fun strings(language: String): Map<String, String> {
        val file = listOf("src/main/res", "app/src/main/res").map { File(it, "${folders.getValue(language)}/strings.xml") }.first { it.exists() }
        return Regex("<string name=\"(card_[a-z0-9_]+)\" formatted=\"false\">(.*?)</string>").findAll(file.readText())
            .associate { match ->
                match.groupValues[1] to match.groupValues[2]
                    .removeSurrounding("\"")
                    .replace("\\'", "'").replace("\\\"", "\"").replace("\\@", "@").replace("\\?", "?")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            }
    }

    /** [CardWords] over the resources of [language]: a key it lacks fails the test. */
    internal fun words(language: String): CardWords {
        val table = strings(language)
        return CardWords { key, params ->
            var text = table[CardStrings.resourceName(key)] ?: throw AssertionError("$language has no $key")
            for ((name, value) in params) text = text.replace("{$name}", value)
            text
        }
    }
}
