package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.sensnology.spotnav.ha.client.CardStringsRead
import se.sensnology.spotnav.testing.CardStringsXml

/** The card's words from Home Assistant: which text a key shows, which fetched texts are used, and what is kept. */
class CardTextTest {
    @get:Rule val folder = TemporaryFolder()

    private val englishTable = CardStringsXml.strings("en")
    private val english: (String) -> String? = { key -> englishTable[CardStrings.resourceName(key)] }

    /** The app's own words of a language, by resource id, as the screen reads them. */
    private fun bundled(language: String): (Int) -> String {
        val table = CardStringsXml.strings(language)
        val names = CardStrings.IDS.entries.associate { (key, id) -> id to CardStrings.resourceName(key) }
        return { id -> table.getValue(names.getValue(id)) }
    }

    private fun words(language: String, hash: String, vararg words: Pair<String, String>) =
        CardStringsRead.Outcome.Words(language, hash, words.toMap())

    @Test fun aFetchedTextComesFirstThenTheAppsOwn() {
        val fetched = CardText.accepted(mapOf("house.title" to "Hemmet"), english)
        assertEquals("Hemmet", CardText.text("house.title", emptyMap(), fetched, bundled("sv")))
        // A key the fetched words lack: the app's own in its language.
        assertEquals("Inne 21 °C", CardText.text("heat.measured", mapOf("value" to "21 °C"), fetched, bundled("sv")))
        // Nothing fetched (first start, offline, an older Home Assistant): the app's own.
        assertEquals("Huset", CardText.text("house.title", emptyMap(), null, bundled("sv")))
    }

    @Test fun aKeyMissingFromTheTableIsLoggedAndShowsTheAppsOwnTextNeverTheFetchedOneOrTheKey() {
        val logged = mutableListOf<String>()
        CardText.log = { logged += it }
        try {
            // A resource the table lacks: the app's own text by its name (in the app's language, else English).
            val swedish = mapOf(4_242 to "Läs igen {what}")
            val byName = { name: String -> if (name == "card_extra_reread") 4_242 else null }
            val said = CardText.text(
                "extra.reread", mapOf("what" to "planen"), mapOf("extra.reread" to "Fetched"), { swedish.getValue(it) }, byName
            )
            assertEquals("Läs igen planen", said)
            assertEquals(listOf("No card text for extra.reread"), logged)
            // No resource of it at all: nothing to show, and never the key itself.
            assertEquals("", CardText.text("no.such.key", emptyMap(), mapOf("no.such.key" to "Hello"), bundled("en"), byName))
            assertEquals(2, logged.size)
        } finally {
            CardText.log = {}
        }
    }

    @Test fun placeholdersAreFilledAndAnUnknownOneFallsBackToTheAppsText() {
        val fetched = CardText.accepted(
            mapOf("heat.measured" to "Inomhus {value}", "power.hour" to "{time}: {kw} ({extra})"),
            english
        )
        assertEquals("Inomhus 21 °C", CardText.text("heat.measured", mapOf("value" to "21 °C"), fetched, bundled("sv")))
        assertFalse("power.hour" in fetched)
        assertEquals("kl. 17:00: 3,2 kW", CardText.text("power.hour", mapOf("time" to "17:00", "kw" to "3,2 kW"), fetched, bundled("sv")))
        assertEquals(setOf("time", "kw"), CardText.placeholders("{time}: {kw} {time}"))
    }

    @Test fun pluralFormsAreTheCardsOwnKeys() {
        val fetched = CardText.accepted(
            mapOf("learning.daysLeft.one" to "{days} dag kvar", "learning.daysLeft.other" to "{days} dagar kvar"),
            english
        )
        assertEquals("1 dag kvar", CardText.text("learning.daysLeft.one", mapOf("days" to "1"), fetched, bundled("sv")))
        assertEquals("3 dagar kvar", CardText.text("learning.daysLeft.other", mapOf("days" to "3"), fetched, bundled("sv")))
    }

    @Test fun aFetchedFileCannotAddUiNorShowMarkupOrAnOverlongText() {
        val fetched = CardText.accepted(
            mapOf(
                "brand.new.key" to "New",
                "house.title" to "<a href=\"https://example.com\">House</a>",
                "heat.title" to "x".repeat(CardText.MAX_LENGTH + 1),
                "heat.loading" to "Reading\u0007",
                "heat.failed" to "Line one\nline two",
                "heat.unsupported" to "x".repeat(CardText.MAX_LENGTH)
            ),
            english
        )
        assertEquals(setOf("heat.failed", "heat.unsupported"), fetched.keys)
        assertEquals(englishTable.getValue("card_house_title"), CardText.text("house.title", emptyMap(), fetched, bundled("en")))
        assertFalse(CardText.usable("house.title", "Tom &amp; Jerry", "House"))
        assertTrue(CardText.usable("house.title", "Less than < 3 kW", "House"))
    }

    @Test fun anOfflineStartUsesTheWordsKeptByAnEarlierOne() {
        val dir = folder.newFolder("card_strings")
        val first = CardStringCache(dir, english)
        assertNull(first.words("http://ha.local:8123", "sv"))
        assertTrue(first.remember("http://ha.local:8123", "sv", words("sv", "11111111", "house.title" to "Hemmet", "made.up" to "x")))
        assertEquals("Hemmet", first.words("http://ha.local:8123", "sv")?.get("house.title"))
        // A later start, nothing asked yet.
        val later = CardStringCache(dir, english)
        assertEquals(mapOf("house.title" to "Hemmet"), later.words("http://ha.local:8123", "sv"))
        assertEquals("11111111", later.hash("http://ha.local:8123", "sv"))
        // Another instance holds nothing of this one's.
        assertNull(later.words("https://other.example", "sv"))
    }

    @Test fun aLanguageSwitchUsesThatLanguagesWordsOrTheAppsOwn() {
        val cache = CardStringCache(folder.newFolder("card_strings"), english)
        cache.remember("ha", "sv", words("sv", "11111111", "house.title" to "Hemmet"))
        assertNull(cache.words("ha", "de"))
        assertEquals("Haus", CardText.text("house.title", emptyMap(), cache.words("ha", "de"), bundled("de")))
        // An answer in another language than the one asked (Home Assistant has none of it) is not kept.
        assertFalse(cache.remember("ha", "de", words("en", "22222222", "house.title" to "House")))
        assertNull(cache.words("ha", "de"))
    }

    @Test fun aNewAnswerReplacesTheWordsKept() {
        val dir = folder.newFolder("card_strings")
        val cache = CardStringCache(dir, english)
        cache.remember("ha", "sv", words("sv", "11111111", "house.title" to "Hemmet"))
        cache.remember("ha", "sv", words("sv", "33333333", "house.title" to "Huset ändrat"))
        val later = CardStringCache(dir, english)
        assertEquals("33333333", later.hash("ha", "sv"))
        assertEquals("Huset ändrat", later.words("ha", "sv")?.get("house.title"))
        assertEquals("http://ha.local:8123", CardStringCache.instance(" HTTP://HA.local:8123/ "))
    }

    @Test fun anInstanceIsAskedOnceARunAndAgainOnlyAfterNoAnswer() {
        val asks = CardStringAsks(retryMs = 1_000)
        assertTrue(asks.claim("ha|sv", 0))
        assertFalse("a second screen at once", asks.claim("ha|sv", 10))
        asks.outcome("ha|sv", CardStringsRead.Outcome.Later)
        assertFalse(asks.claim("ha|sv", 500))
        assertTrue(asks.claim("ha|sv", 1_000))
        asks.outcome("ha|sv", CardStringsRead.Outcome.Unsupported)
        assertFalse("an older Home Assistant is not asked again this run", asks.claim("ha|sv", 10_000))
        assertTrue("another language is its own ask", asks.claim("ha|de", 10_000))
    }
}
