package se.sensnology.spotnav.ui.history

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SessionsOutcome
import se.sensnology.spotnav.ha.client.SessionsRead
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.ha.sessions.PlugInSaving
import se.sensnology.spotnav.ha.sessions.SessionTotals
import se.sensnology.spotnav.ha.sessions.SessionsMonth
import se.sensnology.spotnav.prices.PriceUnit
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The plug-in saving of the charge history (`reads: ["plug_in_saving"]`), against the integration's vendored fixtures. */
class HistoryPlugInTest {
    private fun month(name: String, change: (JSONObject) -> Unit = {}): SessionsMonth {
        val body = HaFixtures.json("webhook/$name.json").also(change)
        return (SessionsRead.month(200, body.toString()) as SessionsOutcome.Loaded).value
    }

    private fun totals(plugIn: PlugInSaving?) = SessionTotals(
        "2026-09", 4, 65.7, 24.95, "SEK", "kr", "öre", 38.0, 0.4, 6.35, false, plugIn
    )

    @Test fun theRequestOptsInToThePlugInSaving() {
        assertTrue(WebhookReads.FIELDS.contains("plug_in_saving"))
        val reads = SessionsRead.payload(null).getJSONArray("reads")
        assertTrue((0 until reads.length()).any { reads.getString(it) == "plug_in_saving" })
    }

    @Test fun anAnswerWithTheKeyCarriesItAndOneWithoutItDoesNot() {
        // The fixture's month has no charge: the key is there and is null.
        val without = month("sessions_plug_in_saving")
        assertEquals(PlugInSaving(null), without.summary.plugIn)
        val saved = month("sessions_plug_in_saving") {
            it.getJSONObject("month_summary").put("plug_in_saving", 12.5)
            it.getJSONArray("month_sessions").put(JSONObject(it.getJSONObject("month_summary").toString()).apply {
                put("id", "s1"); put("start", "2026-09-03T22:00:00+02:00"); put("end", JSONObject.NULL); put("plug_in_saving", -1.25)
                put("started_by", "plan"); put("strategy", JSONObject.NULL); put("vehicle", JSONObject.NULL)
            })
        }
        assertEquals(12.5, saved.summary.plugIn!!.amount!!, 0.0)
        assertEquals(-1.25, saved.sessions.single().plugIn!!.amount!!, 0.0)
        // An answer from before it, or for a charger in no site: no key, so none of it.
        assertNull(month("sessions_success").summary.plugIn)
        // A key of another type is not read as a figure.
        assertNull(month("sessions_plug_in_saving") { it.getJSONObject("month_summary").put("plug_in_saving", "much") }.summary.plugIn)
    }

    @Test fun theSentenceSaysSavedOrExtraAndNothingWhereItIsNotKnownOrNil() {
        val words = CardStringsXml.words("sv")
        fun said(amount: Double?) = HistoryPlugIn.sentence(words, PlugInSaving(amount), HistoryFigures(Locale("sv", "SE"), totals(null), PriceUnit.DEFAULT))
        assertEquals("SpotNav sparade 12,50 kr mot att ladda direkt vid inkoppling", said(12.5))
        assertEquals("1,25 kr mer än att ladda direkt vid inkoppling", said(-1.25))
        assertNull(said(null))
        assertNull(said(0.004))
    }

    @Test fun theWordsAreInAllNineLanguages() {
        for (language in CardStringsXml.LANGUAGES) {
            val words = CardStringsXml.words(language)
            val figures = HistoryFigures(Locale.forLanguageTag(language), totals(null), PriceUnit.DEFAULT)
            for (amount in listOf(3.0, -3.0)) {
                val said = HistoryPlugIn.sentence(words, PlugInSaving(amount), figures)!!
                assertTrue("$language: $said", said.contains("3") && !said.contains("{"))
            }
            assertFalse(words.say("history.savings.plugInNote").isBlank())
        }
    }
}
