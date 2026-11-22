package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.DoesNotFitChoice
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.ui.site.DoesNotFitText
import se.sensnology.spotnav.ui.site.SiteText
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The "does not fit" block (3.0.0b3): read from the dashboard, worded as the card, and the two writes' answers. */
class DoesNotFitTest {
    private val zone = ZoneId.of("Europe/Stockholm")

    private fun dashboard(edit: (JSONObject) -> Unit = {}): Dashboard =
        Dashboard.parse(HaFixtures.json("webhook/dashboard_does_not_fit.json").also(edit))

    private fun text(language: String = "sv", money: House.Money? = House.Money("SEK", "kr", null)) =
        DoesNotFitText(SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language)), zone, money)

    private fun block(edit: JSONObject.() -> Unit): DoesNotFit =
        DoesNotFit.parse(HaFixtures.json("webhook/dashboard_does_not_fit.json").getJSONObject("does_not_fit").apply(edit))!!

    @Test fun theDashboardsBlockIsRead() {
        val block = dashboard().doesNotFit!!
        assertEquals(Instant.parse("2026-11-02T13:30:35Z"), block.departure)
        assertEquals(9.1, block.shortKwh!!, 0.0)
        assertEquals(7.1, block.deliverableKwh!!, 0.0)
        assertEquals("import_target", block.ceiling)
        assertEquals(6.0, block.ceilingKw, 0.0)
        assertEquals(6.0, block.setKw, 0.0)
        assertNull(block.chosen)
        assertEquals(listOf("takes_what_fits", "later", "accept_peak"), block.choices.map { it.code })
        assertEquals(Instant.parse("2026-11-02T16:00:00Z"), block.choices[1].departure)
        assertTrue(block.choices[1].moves)
        assertEquals(12.0, block.choices[2].peakKw!!, 0.0)
    }

    @Test fun aDashboardWithoutTheBlockHasNoLine() {
        for (file in HaFixtures.files("dashboard")) assertNull(file.name, Dashboard.parse(JSONObject(file.readText())).doesNotFit)
        assertNull(dashboard { it.put("does_not_fit", JSONObject.NULL) }.doesNotFit)
    }

    @Test fun unknownKeysAndChoicesAreIgnoredAMissingNeedDropsTheBlock() {
        val newer = block {
            put("something_new", 1)
            getJSONArray("choices").put(JSONObject().put("code", "later_still").put("kwh", 1))
            getJSONArray("choices").getJSONObject(0).put("extra", true)
            put("chosen", "a_new_choice")
        }
        assertEquals(listOf("takes_what_fits", "later", "accept_peak"), newer.choices.map { it.code })
        assertNull(newer.chosen)
        assertNull(DoesNotFit.parse(HaFixtures.json("webhook/dashboard_does_not_fit.json").getJSONObject("does_not_fit").apply { remove("ceiling_kw") }))
        assertNull(DoesNotFit.parse(HaFixtures.json("webhook/dashboard_does_not_fit.json").getJSONObject("does_not_fit").put("departure", "soon")))
        assertNull(DoesNotFit.parse("does_not_fit"))
        assertNotNull("the rest of the dashboard still reads", dashboard { it.put("does_not_fit", JSONObject().put("ceiling", 1)) }.chargerId)
    }

    @Test fun theLineAndTheButtonsAreTheCards() {
        val held = dashboard()
        val block = held.doesNotFit!!
        val sv = text()
        assertEquals("Ryms inte under taket (Effektmål 6,0 kW): 16,2 kWh till 14:30 – 7,1 kWh hinner.", sv.line(block))
        val buttons = sv.buttons(block, releaseOffered = true)
        assertEquals(listOf("Ta det som ryms (≈ 7,1 kWh)", "Senare avresa (17:00)", "Tillåt toppen (≈ 12 kW)"), buttons.map { it.label })
        assertEquals(
            listOf(DoesNotFitText.Press.TAKE_WHAT_FITS, DoesNotFitText.Press.LATER_DEPARTURE, DoesNotFitText.Press.ACCEPT_PEAK),
            buttons.map { it.press }
        )
        // Without a release the dashboard can confirm, no "Allow the peak".
        assertEquals(2, sv.buttons(block, releaseOffered = false).size)
        // A later departure that does not fit within the prices held opens the editor.
        val noLater = block { getJSONArray("choices").getJSONObject(1).put("departure", JSONObject.NULL) }
        assertEquals(DoesNotFitText.Press.OPEN_DEPARTURE, sv.buttons(noLater, true)[1].press)
        assertEquals("Senare avresa", sv.buttons(noLater, true)[1].label)
        // A cost for the peak, in the major unit.
        val cost = block { getJSONArray("choices").getJSONObject(2).put("cost", 10500) }
        assertEquals("Tillåt toppen (≈ 12 kW, ≈ 105 kr)", sv.choice(cost.choices[2]))
        assertEquals("Allow the peak (≈ 12 kW, ≈ 105 kr)", text("en").choice(cost.choices[2]))
    }

    @Test fun whatWasChosenIsSaid() {
        val sv = text()
        val takes = block {
            put("chosen", "takes_what_fits")
            put("choices", JSONArray().put(getJSONArray("choices").get(1)).put(getJSONArray("choices").get(2)))
        }
        assertEquals("Bilen tar det som ryms till 14:30 (7,1 kWh).", sv.line(takes))
        val peak = block {
            put("chosen", "accept_peak")
            put("short_kwh", JSONObject.NULL)
            put("deliverable_kwh", JSONObject.NULL)
        }
        assertEquals("Taket (Effektmål 6,0 kW) är släppt för den här laddningen till 14:30. Toppen kan bli ungefär 12 kW.", sv.line(peak))
        val unplug = block { put("chosen", "accept_peak").put("departure", JSONObject.NULL).put("choices", JSONArray()) }
        assertEquals("Taket (Effektmål 6,0 kW) är släppt för den här laddningen tills bilen kopplas ur.", sv.line(unplug))
        assertEquals("Ryms inte under taket (Effektmål 6,0 kW): 9,1 kWh saknas till avresan.", sv.line(block { put("departure", JSONObject.NULL) }))
    }

    @Test fun everyWordIsInEveryLanguageAndNoneSaysAvgang() {
        val shapes = listOf(
            block {}, block { put("deliverable_kwh", JSONObject.NULL) }, block { put("departure", JSONObject.NULL) },
            block { put("chosen", "takes_what_fits") }, block { put("chosen", "accept_peak") },
            block { put("ceiling", "power_level") }
        )
        for (language in CardStringsXml.LANGUAGES) {
            val words = text(language)
            for (shape in shapes) {
                val said = listOf(words.line(shape), words.acceptPeakAgain()) + words.buttons(shape, true).map { it.label }
                for (line in said) assertFalse("$language: $line", line.contains("{") || line.contains("doesNotFit.") || line.contains("house.plan."))
            }
        }
        assertFalse(CardStringsXml.strings("sv").values.any { it.contains("avgång", ignoreCase = true) })
    }

    @Test fun theLinesItStandsInForAreLeftOutOfTheStatus() {
        val held = dashboard {
            val status = it.getJSONObject("status")
            status.put("lines", JSONArray().put(JSONObject().put("code", "import_target_at_risk").put("params", JSONObject())))
        }
        assertEquals(1, held.status.lines.size)
        assertTrue(DoesNotFit.statusShown(held).lines.isEmpty())
        val fits = held.copy(doesNotFit = null)
        assertEquals(1, DoesNotFit.statusShown(fits).lines.size)
    }

    @Test fun theTwoWritesAndTheirAnswers() {
        for (action in listOf(DoesNotFitChoice.TAKE_WHAT_FITS, DoesNotFitChoice.LATER_DEPARTURE)) {
            val body = DoesNotFitChoice.payload(action)
            assertEquals(action, body.getString("action"))
            assertEquals(1, body.getInt("version"))
            assertEquals(1, body.getInt("api_version"))
            assertFalse(body.has("charger_id"))
        }
        fun answer(name: String, status: Int) = DoesNotFitChoice.answer(status, HaFixtures.json("webhook/$name").toString())
        assertEquals(DoesNotFitChoice.Outcome.Done, answer("take_what_fits_success.json", 200))
        assertEquals(DoesNotFitChoice.Outcome.Done, answer("later_departure_success.json", 200))
        assertEquals(DoesNotFitChoice.Outcome.NothingToDo("spotnav_no_departure"), answer("take_what_fits_no_departure.json", 400))
        assertEquals(DoesNotFitChoice.Outcome.NothingToDo("spotnav_no_departure"), answer("later_departure_no_departure.json", 400))
        assertEquals(DoesNotFitChoice.Outcome.NothingToDo("spotnav_no_later_departure"), answer("later_departure_no_later_departure.json", 400))
        assertEquals(DoesNotFitChoice.Outcome.Failed("spotnav_unsupported_api_version"), answer("take_what_fits_unsupported_version.json", 400))
        // An older Home Assistant.
        assertEquals(DoesNotFitChoice.Outcome.Failed("Unsupported action"), DoesNotFitChoice.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(DoesNotFitChoice.Outcome.Failed(null), DoesNotFitChoice.answer(null, null))
    }
}
