package se.sensnology.spotnav.ui.site

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** What SpotNav holds on the zones and the water heater, as the card words it (`heat-card.test.ts`, "the zones and the water heater"). */
class HeatHoldTextTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun heat(name: String = "controlled.json", edit: (JSONObject) -> Unit = {}): Heat {
        val json = HaFixtures.json("heat_card/$name")
        edit(json)
        return (HeatRead.decode(json) as SiteRead.Read).value
    }

    private fun zoneControl(json: JSONObject, entity: String, edit: JSONObject.() -> Unit) {
        val zones = json.getJSONArray("zones")
        for (i in 0 until zones.length()) {
            val zone = zones.getJSONObject(i)
            if (zone.getString("entity_id") == entity) zone.put("control", JSONObject(zone.getJSONObject("control").toString()).apply(edit))
        }
    }

    private val KEY = Regex("heat\\.(zone|waterHeater|now|note|settings)")
    private val up = "climate.golvvarme_toalett_uppe"

    @Test fun theZonesAndTheWaterHeaterAreDecoded() {
        val h = heat()
        assertEquals(4, h.zones)
        assertEquals(4, h.zoneList.size)
        val control = h.zoneList.first { it.entityId == up }.control!!
        assertEquals("running", control.state)
        assertEquals(21.0, control.heldC!!, 0.0)
        assertEquals(22.0, control.homeC!!, 0.0)
        assertEquals("planned", control.reason)
        assertNull(h.zoneList.first { it.kind == "air" }.control)
        val heater = h.waterHeater!!
        assertEquals("Varmvattenberedare", heater.name)
        assertEquals(false, heater.on)
        assertTrue(heater.live)
        assertEquals(2, heater.windows.size)
        assertTrue(h.config.waterHeaterControl)
        // A Home Assistant before them: no zone control, no water heater.
        val old = heat("controlled.json") { json ->
            json.remove("water_heater")
            json.getJSONObject("config").remove("water_heater")
            val zones = json.getJSONArray("zones")
            for (i in 0 until zones.length()) zones.getJSONObject(i).remove("control")
        }
        assertNull(old.waterHeater)
        assertFalse(old.config.waterHeaterControl)
        assertTrue(old.zoneList.all { it.control == null })
        // Water heater null: none.
        assertNull(heat("controlled.json") { it.put("water_heater", JSONObject.NULL) }.waterHeater)
        // A zone that is not an object is no document.
        assertTrue(HeatRead.decode(HaFixtures.json("heat_card/controlled.json").put("zones", JSONArray().put(1))) is SiteRead.Failed)
    }

    @Test fun nowListsWhatIsHeldAndTheWaterHeatersWindows() {
        val h = heat()
        val lines = HeatNowModel.holdLines(text(), h)
        assertEquals(listOf("zone:$up", "waterHeater", "waterHeaterWindows"), lines.map { it.first })
        assertEquals("Golvvärme toalett ovanvåning 21,0 °C till 19:00", lines[0].second)
        assertTrue(lines[1].second, lines[1].second.startsWith("Varmvattenberedaren av till ") && lines[1].second.endsWith("01:00"))
        assertTrue(lines[2].second, lines[2].second.startsWith("Varmvattenberedaren på "))
        assertTrue(HeatNowModel.holdLines(text("en"), h)[2].second.startsWith("Water heater on "))
    }

    @Test fun aZoneWatchedOnlyOrWithoutAHoldHasNoNowLine() {
        val none = heat { json ->
            zoneControl(json, up) { put("held_c", JSONObject.NULL) }
            json.put("water_heater", JSONObject.NULL)
        }
        assertEquals(emptyList<String>(), HeatNowModel.holdLines(text(), none).map { it.first })
        val waiting = heat { zoneControl(it, up) { put("state", "waiting") } }
        assertTrue(HeatNowModel.holdLines(text(), waiting).none { it.first == "zone:$up" })
    }

    @Test fun aWaterHeaterNotSwitchedBySpotNavOnlyWouldBeOn() {
        val watching = heat { it.getJSONObject("water_heater").put("live", false).put("state", "off") }
        val lines = HeatNowModel.holdLines(text("en"), watching).filter { it.first.startsWith("waterHeater") }
        assertEquals(listOf("waterHeaterWindows"), lines.map { it.first })
        assertTrue(lines[0].second, lines[0].second.startsWith("Water heater would be on "))
    }

    @Test fun theZonesLineUnderItsRow() {
        val h = heat()
        val hold = HeatHoldText.of(text(), h)
        assertEquals("SpotNav håller 21,0 °C till 19:00.", hold.zoneLine(h.zoneList.first { it.entityId == up }))
        // Watched only: no line.
        assertNull(hold.zoneLine(h.zoneList.first { it.kind == "air" }))
        fun line(edit: JSONObject.() -> Unit): String? {
            val changed = heat { zoneControl(it, up, edit) }
            return HeatHoldText.of(text("en"), changed).zoneLine(changed.zoneList.first { it.entityId == up })
        }
        assertEquals("SpotNav holds 21.0 °C.", line { put("until", JSONObject.NULL) })
        assertEquals("Your own setting, 22.0 °C, stands.", line { put("held_c", JSONObject.NULL) })
        assertEquals("Checking that the thermostat answers…", line { put("state", "verifying") })
        assertNull(line { put("state", "off") })
        assertNull(line { put("enabled", false) })
        assertEquals("Changed by hand: SpotNav leaves it until tomorrow.", line { put("state", "waiting").put("reason", "foreign_change") })
        assertEquals("Waiting: turn off the automation 'Golv' so SpotNav can set this thermostat.", line {
            put("state", "waiting").put("reason", "automation").put("automation", JSONObject().put("entity_id", "automation.golv").put("name", "Golv"))
        })
        assertEquals("Waiting.", line { put("state", "waiting").put("reason", "something_new") })
    }

    @Test fun theWaterHeatersLineUnderItsRow() {
        fun line(controlled: Boolean = true, edit: JSONObject.() -> Unit = {}): String? {
            val h = heat { edit(it.getJSONObject("water_heater")) }
            return HeatHoldText.of(text("en"), h).waterHeaterLine(h.waterHeater!!, controlled)
        }
        assertTrue(line()!!, line()!!.startsWith("Off until ") && line()!!.endsWith("01:00."))
        // Watched: the windows SpotNav would allow, or just "Watched." without any.
        assertTrue(line(controlled = false)!!, line(controlled = false)!!.startsWith("Watched: SpotNav would allow "))
        assertEquals("Watched.", line(controlled = false) { put("windows", JSONArray()) })
        assertEquals("Watched.", line { put("state", "off").put("windows", JSONArray()) })
        assertEquals("On: full heat.", line { put("on", true).put("reason", "full_heat") })
        assertEquals("On: the tank is cold.", line { put("on", true).put("reason", "tank_low") })
        assertNull(line { put("on", true).put("reason", "planned_off") })
        assertEquals("Checking that the switch turns the water heater off…", line { put("state", "verifying").put("reason", "check") })
        assertEquals("Stopped: the water heater has not reached its thermostat for two days.", line { put("state", "waiting").put("reason", "not_full") })
        assertEquals("Waiting.", line { put("state", "waiting").put("reason", "something_new") })
        assertTrue(line { put("meter_waiting", true) }!!.endsWith(" " + CardStringsXml.words("en").say("heat.waterHeater.meterWaiting")))
    }

    @Test fun aWaterHeaterPausedForTheCeilingSaysSo() {
        val h = heat { it.getJSONObject("water_heater").put("reason", "ceiling") }
        val now = HeatNowModel.holdLines(text(), h).first { it.first == "waterHeater" }.second
        assertTrue(now, now.startsWith("Varmvattenberedaren pausad för taket till ") && now.endsWith("01:00"))
        val line = HeatHoldText.of(text(), h).waterHeaterLine(h.waterHeater!!, true)!!
        assertTrue(line, line.startsWith("Pausad för taket till ") && line.endsWith("01:00."))
        for (language in CardStringsXml.LANGUAGES) {
            val said = HeatNowModel.holdLines(text(language), h).map { it.second } + HeatHoldText.of(text(language), h).waterHeaterLine(h.waterHeater!!, true)!!
            for (one in said) assertFalse("$language: $one", KEY.containsMatchIn(one) || one.contains("{"))
        }
    }

    @Test fun theNoteForAWaterHeaterThatNeverGetsFull() {
        val noted = heat { it.put("notes", JSONArray().put(JSONObject().put("code", "water_heater_not_full").put("params", JSONObject().put("name", "Varmvattenberedare")))) }
        assertEquals(
            listOf("water_heater_not_full" to "Varmvattenberedaren har inte nått sin termostat på två dygn: kontrollera termostaten och elementet."),
            HeatNowModel.noteLines(text(), noted)
        )
    }

    @Test fun everyWordIsInEveryLanguage() {
        for (language in CardStringsXml.LANGUAGES) {
            val h = heat()
            val hold = HeatHoldText.of(text(language), h)
            val said = HeatNowModel.holdLines(text(language), h).map { it.second } +
                h.zoneList.mapNotNull(hold::zoneLine) + listOfNotNull(hold.waterHeaterLine(h.waterHeater!!, true), hold.waterHeaterLine(h.waterHeater!!, false))
            assertTrue(said.isNotEmpty())
            for (line in said) assertFalse("$language: $line", KEY.containsMatchIn(line) || line.contains("{"))
            val reasons = listOf("automation", "foreign_change", "no_read_back", "check_failed", "not_qualified", "unavailable", "write_cap")
            for (reason in reasons) {
                val changed = heat { zoneControl(it, up) { put("state", "waiting").put("reason", reason) } }
                val line = HeatHoldText.of(text(language), changed).zoneLine(changed.zoneList.first { it.entityId == up })!!
                assertFalse("$language $reason: $line", KEY.containsMatchIn(line) || line.contains("{"))
            }
            for (reason in listOf("automation", "foreign_on", "foreign_off", "no_read_back", "check_failed", "not_full", "unavailable")) {
                val changed = heat { it.getJSONObject("water_heater").put("state", "waiting").put("reason", reason) }
                val line = HeatHoldText.of(text(language), changed).waterHeaterLine(changed.waterHeater!!, true)!!
                assertFalse("$language $reason: $line", KEY.containsMatchIn(line) || line.contains("{"))
            }
            for (why in listOf("cheap", "full_heat", "max_off", "sensor_lost", "no_plan", "tank_low", "write_cap")) {
                val changed = heat { it.getJSONObject("water_heater").put("on", true).put("reason", why) }
                val line = HeatHoldText.of(text(language), changed).waterHeaterLine(changed.waterHeater!!, true)!!
                assertFalse("$language $why: $line", KEY.containsMatchIn(line) || line.contains("{"))
            }
        }
    }
}
