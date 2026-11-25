package se.sensnology.spotnav.ha.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

/** `get_strings` as Home Assistant answers it (the vendored fixtures), and as an older one refuses it. */
class CardStringsReadTest {
    private fun fixture(name: String) = HaFixtures.json("webhook/$name").toString()

    @Test fun theWordsOfALanguage() {
        val words = CardStringsRead.answer(200, fixture("get_strings_success.json"), null) as CardStringsRead.Outcome.Words
        assertEquals("sv", words.language)
        assertEquals("Huset", words.words["house.title"])
        assertEquals("Inne {value}", words.words["heat.measured"])
        assertEquals("{days} dygn kvar", words.words["learning.daysLeft.one"])
        assertEquals(HaFixtures.json("webhook/get_strings_success.json").getString("hash"), words.hash)
    }

    @Test fun theHashHeldIsStillCurrent() {
        val body = HaFixtures.json("webhook/get_strings_unchanged.json")
        assertEquals(CardStringsRead.Outcome.Unchanged(body.getString("hash")), CardStringsRead.answer(200, body.toString(), null))
    }

    @Test fun aLanguageHomeAssistantHasNoneOfIsAnsweredInEnglish() {
        val words = CardStringsRead.answer(200, fixture("get_strings_english.json"), null) as CardStringsRead.Outcome.Words
        assertEquals("en", words.language)
        assertEquals("House", words.words["house.title"])
    }

    @Test fun anOlderHomeAssistantOrARefusalKeepsWhatIsHeld() {
        assertEquals(CardStringsRead.Outcome.Unsupported, CardStringsRead.answer(400, """{"ok": false, "error": "Unsupported action"}""", null))
        assertEquals(CardStringsRead.Outcome.Unsupported, CardStringsRead.answer(400, """{"ok": false, "error": "spotnav_invalid_value", "action": "get_strings"}""", null))
        assertEquals(CardStringsRead.Outcome.Unsupported, CardStringsRead.answer(404, """{"ok": false, "error": "spotnav_strings_unavailable"}""", null))
        assertEquals(CardStringsRead.Outcome.Unsupported, CardStringsRead.answer(200, "not json", null))
    }

    @Test fun noAnswerIsAskedAgainLater() {
        assertEquals(CardStringsRead.Outcome.Later, CardStringsRead.answer(null, null, java.io.IOException("offline")))
        assertEquals(CardStringsRead.Outcome.Later, CardStringsRead.answer(503, """{"ok": false, "error": "spotnav_site_unloaded"}""", null))
    }

    @Test fun theRequestNamesTheLanguageAndTheHashHeld() {
        val asked = CardStringsRead.payload("sv", "4635a84f")
        assertEquals("get_strings", asked.getString("action"))
        assertEquals("sv", asked.getString("language"))
        assertEquals("4635a84f", asked.getString("known_hash"))
        assertFalse(CardStringsRead.payload("sv", null).has("known_hash"))
    }
}
