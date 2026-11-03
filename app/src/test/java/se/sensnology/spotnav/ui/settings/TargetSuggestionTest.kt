package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SuggestImportTarget
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.time.ZoneId
import java.util.Locale

/** The suggested import target (`suggest_import_target`), read and worded as the card's `target-suggestion.ts`. */
class TargetSuggestionTest {
    private val sv = Locale.forLanguageTag("sv")
    private val words = CardStringsXml.words("sv")
    private val text = DemandText(words, sv, sv, ZoneId.of("Europe/Stockholm"))
    private val body get() = HaFixtures.root.resolve("webhook/suggest_import_target_success.json").readText()

    @Test fun theCapabilityAndTheRequest() {
        assertTrue(Dashboard.parse(HaFixtures.json("webhook/dashboard.json")).capabilities.suggestImportTarget)
        assertEquals("suggest_import_target", SuggestImportTarget.payload().getString("action"))
    }

    @Test fun theSuggestionIsWordedWithTheFigureToUse() {
        val advice = text.advice(words, SuggestImportTarget.answer(200, body)!!)!!
        assertEquals(7.0, advice.value!!, 0.0)
        assertTrue(advice.text, advice.text.startsWith("Huset ensamt: oftast högst 2,4 kW en timme per dygn, som mest 7,5 kW (fre 4 sep"))
        assertTrue(advice.text, advice.text.contains("Förslag: 7,0 kW – bilen får då omkring 6,4 kW de flesta timmar."))
        assertTrue(advice.text, advice.text.endsWith("Laddarens egen effekt finns inte i statistiken, så husets siffror räknar med laddningen."))
    }

    @Test fun withoutASuggestionItSaysWhy() {
        val none = """{"ok": true, "api_version": 1, "available": false, "reason": "too_few_days", "days": 4, "suggestion_kw": null}"""
        assertEquals(TargetAdvice("Inget förslag än: Home Assistants statistik räcker bara 4 dagar bakåt.", null), text.advice(words, SuggestImportTarget.answer(200, none)!!))
        val unknown = """{"ok": true, "api_version": 1, "available": false, "reason": "something_new", "days": 0}"""
        assertNull(text.advice(words, SuggestImportTarget.answer(200, unknown)!!))
    }

    @Test fun aRefusalOrAnotherVersionIsNoSuggestion() {
        assertNull(SuggestImportTarget.answer(400, """{"ok": false, "error": "spotnav_no_site", "action": "suggest_import_target"}"""))
        assertNull(SuggestImportTarget.answer(200, body.replace("\"api_version\": 1", "\"api_version\": 2")))
        assertNull(SuggestImportTarget.answer(null, null))
    }
}
