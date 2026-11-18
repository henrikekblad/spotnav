package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatRead
import se.sensnology.spotnav.ha.heat.HeatWrite
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.util.Locale

/** The heat page's Now tab as the card words it (the card's `heat-card.test.ts` Now cases, on the same fixtures). */
class HeatNowModelTest {
    private val now = Instant.parse("2026-11-16T16:20:00Z")

    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun heat(name: String, edit: (org.json.JSONObject) -> Unit = {}): Heat {
        val json = HaFixtures.json("heat_card/$name")
        edit(json)
        return (HeatRead.decode(json) as SiteRead.Read).value
    }

    @Test fun theHeaderSaysTestAndWhatThePumpDoes() {
        // In shadow: "Värme · Test · Värmer 1,1 kW".
        val villa = heat("villa.json")
        assertEquals("Test · Värmer 1,1 kW", HeatNowModel.statusAfterName(text(), villa))
        assertTrue(HeatNowModel.live(text(), villa)!!.first == "heating")
        // Controlled and lowering: only what it does, no Test.
        assertEquals("Sänkt till 19:00", HeatNowModel.statusAfterName(text(), heat("controlled.json")))
        assertEquals("Lowered until 19:00", HeatNowModel.statusAfterName(text("en"), heat("controlled.json")))
        // Control on but not lowering: the pump's own state, no Test.
        val running = heat("controlled.json") { it.getJSONObject("control").put("lowered", false) }
        assertEquals("Värmer 1,1 kW", HeatNowModel.statusAfterName(text(), running))
        // Control off and nothing read: Test alone.
        val bare = heat("villa.json") { it.getJSONObject("status").put("pump_state", org.json.JSONObject.NULL) }
        assertEquals("Test", HeatNowModel.statusAfterName(text(), bare))
        val idle = heat("villa.json") { it.getJSONObject("status").put("pump_state", "idle").put("kw", 0.02) }
        assertEquals("Test · Vilar", HeatNowModel.statusAfterName(text(), idle))
        val water = heat("villa.json") { it.getJSONObject("status").put("pump_state", "hot_water").put("kw", 2.94) }
        assertEquals("Test · Varmvatten 2,9 kW", HeatNowModel.statusAfterName(text(), water))
        assertTrue(!HeatNowModel.controls(villa) && HeatNowModel.controls(heat("controlled.json")))
    }

    @Test fun theLinesUnderTheDial() {
        val villa = heat("villa.json")
        assertEquals("Inne 20,6 °C (kallast: Toalett NV) · Ute 6,2 °C", HeatNowModel.measuredLine(text(), villa))
        assertEquals("Värmepumpen värmer · 1,1 kW", HeatNowModel.pumpLine(text(), villa))
        assertEquals("Skulle sänka till 20,5 °C till 19:00: dyra timmar", HeatNowModel.wouldLine(text(), villa))
        assertEquals("inom 20,5–21,5 °C", HeatNowModel.bandLine(text(), 21.0))
        assertEquals("I dag · värme 17,5 kWh · varav zoner 3,6 kWh · varmvatten 1,7 kWh · 22 kr", HeatNowModel.todayLine(text(), villa))
        val plain = heat("villa.json") { it.getJSONObject("today").put("zones_kwh", org.json.JSONObject.NULL) }
        assertFalse(HeatNowModel.todayLine(text("en"), plain)!!.contains("zones"))
        val warm = heat("villa.json") { it.getJSONObject("status").getJSONObject("would").put("reason", "room_warm") }
        assertEquals("Skulle sänka till 20,5 °C till 19:00: för varmt inne", HeatNowModel.wouldLine(text(), warm))
        assertEquals("Would lower to 20.5 °C until 19:00: too warm indoors", HeatNowModel.wouldLine(text("en"), warm))
        assertNull(HeatNowModel.todayLine(text(), heat("network_pump.json")))
    }

    @Test fun awayAndAPumpWithoutRoomSensors() {
        val pump = heat("network_pump.json")
        assertEquals("Heat pump: heating", HeatNowModel.pumpLine(text("en"), pump))
        assertEquals("No indoor sensor chosen · Outdoors 4.5 °C", HeatNowModel.measuredLine(text("en"), pump))
        assertTrue(HeatNowModel.wouldLine(text("en"), pump)!!.startsWith("Would keep 17.5 °C until "))
        assertTrue(HeatNowModel.wouldLine(text("en"), pump)!!.endsWith(" 14:00 while you are away"))
        // The return day and time under the buttons.
        val line = HeatNowModel.awayLine(text(), pump)!!
        assertTrue(line, line.startsWith("Borta till ") && line.endsWith("16:00"))
        assertNull(HeatNowModel.awayLine(text(), heat("villa.json")))
    }

    @Test fun theRelayLine() {
        val villa = heat("villa.json")
        assertEquals("Reläet sänker värmen sedan 16:00, väntas öppna 19:00", HeatNowModel.relayLine(text(), villa, now))
        val open = heat("villa.json") { json ->
            json.getJSONObject("relay").put("closed", false)
            val hours = json.getJSONObject("band").getJSONArray("hours")
            for (i in 0 until hours.length()) hours.getJSONObject(i).put("relay_expected", false).put("relay_min", org.json.JSONObject.NULL)
            hours.getJSONObject(20).put("relay_expected", true).put("relay_min", 60)
        }
        val next = HeatNowModel.relayLine(text(), open, now)
        assertTrue(next, next!!.startsWith("Reläet väntas sänka värmen "))
        assertNull(HeatNowModel.relayLine(text(), heat("network_pump.json"), now))
    }

    @Test fun thePlanAheadInWordsAndCells() {
        val villa = heat("villa.json")
        val line = HeatNowModel.aheadLine(text("en"), villa)!!
        assertTrue(line, line.startsWith("Plan: lowering "))
        val cells = HeatNowModel.aheadCells(villa)
        assertEquals(12, cells.size)
        assertEquals("lower", cells[0]!!.action)
        // The hours the plan does not reach yet are open.
        assertEquals(List(12 - villa.ahead.size) { null }, cells.drop(villa.ahead.size))
        assertEquals(4, HeatNowModel.aheadAxis(text(), villa).size)
        val none = heat("villa.json") { json -> val a = json.getJSONArray("ahead"); for (i in 0 until a.length()) a.getJSONObject(i).put("action", "normal") }
        assertEquals("No lowering planned for the next 12 hours", HeatNowModel.aheadLine(text("en"), none))
        val empty = heat("villa.json") { it.put("ahead", org.json.JSONArray()) }
        assertNull(HeatNowModel.aheadLine(text(), empty))
        assertTrue(HeatNowModel.aheadCells(empty).all { it == null })
    }

    @Test fun theNotesTheHouseholdCanActOn() {
        val notes = HeatNowModel.noteLines(text("en"), heat("network_pump.json")).map { it.second }
        assertEquals(3, notes.size)
        assertTrue(notes[0], notes[0].contains("NIBE F750") && notes[0].contains("Smart Price Adaption"))
        assertTrue(notes[1], notes[1].contains("degree minutes"))
        assertTrue(notes[2], notes[2].contains("EffektGuard"))
        val villa = HeatNowModel.noteLines(text("en"), heat("villa.json")).map { it.second }
        assertEquals(2, villa.size)
        assertTrue(villa[0].startsWith("The automation 'Golvv"))
        assertTrue(villa[1].startsWith("Turn off the automation"))
        val unknown = heat("villa.json") { it.put("notes", org.json.JSONArray().put(org.json.JSONObject().put("code", "from_the_future").put("params", org.json.JSONObject()))) }
        assertTrue(HeatNowModel.noteLines(text(), unknown).isEmpty())
    }

    @Test fun theDialStepsInHalfDegreesWithinItsRange() {
        assertEquals(21.5, HeatNowModel.stepped(21.0, 0.5), 0.0)
        assertEquals(20.5, HeatNowModel.stepped(21.0, -0.5), 0.0)
        assertEquals(25.0, HeatNowModel.stepped(25.0, 0.5), 0.0)
        assertEquals(16.0, HeatNowModel.stepped(16.0, -0.5), 0.0)
        assertEquals(0.0, HeatNowModel.share(16.0), 0.0)
        assertEquals(1.0, HeatNowModel.share(30.0), 0.0)
    }

    @Test fun aRefusedWriteIsToldInTheCardsWords() {
        assertEquals("Någon annan ändrade samtidigt; det senaste visas.", HeatNowModel.errorWords(text(), HeatWrite.Outcome.Conflict))
        assertEquals("Välj halva grader.", HeatNowModel.errorWords(text(), HeatWrite.Outcome.Refused(listOf("not_a_step"))))
        assertEquals("Choose a time ahead.", HeatNowModel.errorWords(text("en"), HeatWrite.Outcome.Refused(listOf("away_in_past"))))
        assertEquals("Could not save.", HeatNowModel.errorWords(text("en"), HeatWrite.Outcome.Refused(listOf("entity_not_found"))))
        assertEquals("Could not save.", HeatNowModel.errorWords(text("en"), HeatWrite.Outcome.Failed(null)))
    }

    @Test fun everyWordIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val t = text(language)
            for (name in listOf("villa.json", "controlled.json", "network_pump.json")) {
                val h = heat(name)
                val said = listOfNotNull(
                    HeatNowModel.statusAfterName(t, h), HeatNowModel.measuredLine(t, h), HeatNowModel.pumpLine(t, h),
                    HeatNowModel.wouldLine(t, h), HeatNowModel.relayLine(t, h, now), HeatNowModel.aheadLine(t, h),
                    HeatNowModel.todayLine(t, h), HeatNowModel.awayLine(t, h)
                ) + HeatNowModel.noteLines(t, h).map { it.second }
                for (line in said) assertFalse("$language $name: $line", line.contains("heat.") || line.contains("{"))
            }
        }
    }
}
