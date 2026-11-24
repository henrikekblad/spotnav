package se.sensnology.spotnav.ui.site

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatLoadPress
import se.sensnology.spotnav.ha.heat.HeatLoads
import se.sensnology.spotnav.ha.heat.HeatRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.util.Locale

/** The pool, the spa and the sauna as `get_heat` states them (`heat_card/controlled.json`), their words and their presses. */
class HeatLoadsTest {
    private fun heat(edit: (JSONObject) -> Unit = {}): Heat =
        (HeatRead.decode(HaFixtures.json("heat_card/controlled.json").also(edit)) as SiteRead.Read).value

    private fun said(heat: Heat, language: String = "sv") =
        HeatLoadsText.of(SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language)), heat)

    @Test fun theControlledSiteHasAPoolASpaAndASauna() {
        val h = heat()
        val pool = h.pool!!
        assertTrue(pool.seasonOpen)
        assertEquals(24.6, pool.waterC!!, 0.0)
        assertEquals("controlled", pool.circulation!!.control.state)
        assertEquals(1, pool.circulation!!.control.failures)
        assertEquals("watched", pool.heatPump!!.control.state)
        assertEquals(8.25, pool.today.needH!!, 0.0)
        assertEquals(listOf("filter", "heat"), pool.planned.map { it.what })
        val spa = h.spa!!
        assertEquals(38.0, spa.targetC, 0.0)
        assertEquals(30.0, spa.idleC!!, 0.0)
        assertEquals(listOf(HeatLoads.Session(listOf(4, 5), "19:00")), spa.weekly)
        assertEquals(listOf("heat", "hold"), spa.planned.map { it.what })
        val sauna = h.sauna!!
        assertNull(sauna.idleC)
        assertEquals("watch_only", sauna.control.state)
        assertEquals("fuse", sauna.notReady!!.cause)
        assertEquals(Instant.parse("2026-11-17T17:00:00Z"), sauna.notReady!!.later)
        assertTrue(h.hasSomething)
    }

    @Test fun aSiteWithoutThemHasNoneAndAnOlderHomeAssistantHasNoKeys() {
        val villa = (HeatRead.decode(HaFixtures.json("heat_card/villa.json")) as SiteRead.Read).value
        assertNull(villa.pool)
        assertNull(villa.spa)
        val older = heat { it.remove("pool"); it.remove("spa"); it.remove("sauna") }
        assertNull(older.sauna)
        // A block that is not its shape is no document, as every key the app reads.
        assertTrue(HeatRead.decode(HaFixtures.json("heat_card/controlled.json").also { it.getJSONObject("spa").remove("bath_c") }) is SiteRead.Failed)
    }

    @Test fun thePoolBoxSaysWhatItDoesAndWhatSpotNavControls() {
        val h = heat()
        val pool = h.pool!!
        val words = said(h)
        assertEquals("Vattnet 24,6 °C", words.poolWater(pool))
        assertEquals("Cirkulation och värmepump står still", words.poolStatus(pool))
        assertEquals("Filtrering i dag: 3 h klart, 4 h planerat av 8,3 h", words.poolToday(pool)[0])
        assertEquals("Värmepumpen: 2 h planerat", words.poolToday(pool)[1])
        assertTrue(words.poolPlan(pool)!!.startsWith("Filtrerar "))
        assertEquals(
            listOf("Cirkulationen: Styrs av SpotNav. Ändringar som inte lästes tillbaka: 1."),
            words.poolControl(pool)
        )
        val closed = heat { it.getJSONObject("pool").put("season_open", false) }.pool!!
        assertEquals("Säsongen är stängd: SpotNav rör inte poolen.", words.poolStatus(closed))
        assertNull(words.poolWould(closed))
        assertTrue(words.poolToday(closed).isEmpty())
    }

    @Test fun theControlStatesSayPausedAndHandedBackWithUntilWhen() {
        val h = heat()
        val words = said(h)
        val until = Instant.parse("2026-11-16T23:00:00Z")
        assertTrue(words.control(HeatLoads.Control("paused", "write_cap", until, 0))!!.startsWith("Pausad till "))
        assertTrue(words.control(HeatLoads.Control("paused", "write_cap", until, 0))!!.endsWith("dagens ändringar är använda."))
        assertTrue(words.control(HeatLoads.Control("handed_back", null, until, 2))!!.startsWith("Återlämnad till dig till "))
        assertTrue(words.control(HeatLoads.Control("handed_back", null, until, 2))!!.endsWith("Ändringar som inte lästes tillbaka: 2."))
        assertEquals("Pausad: en automation styr den.", words.control(HeatLoads.Control("paused", "automation", null, 0)))
        assertEquals("Styrs av SpotNav: väntar på att cirkulationen går.", words.control(HeatLoads.Control("controlled", "interlock", null, 0)))
        assertNull(words.control(HeatLoads.Control("watched", null, null, 0)))
        assertNull(words.control(HeatLoads.Control("season_closed", null, null, 0)))
    }

    @Test fun theSpaAndTheSaunaSayTheirStatusTheirTimesAndWhatWillNotBeReady() {
        val h = heat()
        val words = said(h)
        val spa = h.spa!!
        assertEquals("Klar 18:40, värmer 2 °C/h", words.deadlineStatus(spa))
        assertEquals("Badtider: fre, lör 19:00", words.times(spa))
        assertEquals("Mellan baden: 30,0 °C", words.idle(spa))
        assertEquals("Skulle värma till 18:45: billiga timmar", words.deadlineWould(spa))
        assertTrue(words.deadlineControl(spa).startsWith("SpotNav bevakar bara spat"))
        val sauna = h.sauna!!
        assertEquals("Av, inget bastupass bokat", words.deadlineStatus(sauna))
        assertEquals("Bastupass: Inga bastupass", words.times(sauna))
        assertTrue(words.deadlineControl(sauna).startsWith("SpotNav startar aldrig ett aggregat på relä"))
        val line = words.notReadyLine(sauna, sauna.notReady!!)
        assertTrue(line, line.startsWith("Bastupasset tis 17 nov. 17:00 blir inte klart i tid: det når 60,0 °C."))
        assertTrue(line, line.endsWith("Säkringen ger för lite utrymme."))
        // The fuse is never set aside: no "Allow the peak".
        assertEquals(listOf("take_what_fits", "later_session"), words.notReadyChoices(sauna, sauna.notReady!!).map { it.action })
        assertEquals("Senare bastupass (tis 17 nov. 18:00)", words.notReadyChoices(sauna, sauna.notReady!!)[1].label)
        assertEquals("mån–fre", words.weekdays(listOf(0, 1, 2, 3, 4)))
        assertEquals("varje dag", words.weekdays((0..6).toList()))
        assertEquals("Bada nu", words.nowLabel(spa))
        assertEquals("bath_now", words.nowAction(spa))
        assertEquals("session_now", words.nowAction(sauna))
    }

    @Test fun thePressesAreTheWebhooksTwinsWithTheirActionKeys() {
        val pool = HeatLoadPress.pool("extra_filtering", mapOf("hours" to 4))
        assertEquals("pool", pool.getString("action"))
        assertEquals("extra_filtering", pool.getString("pool_action"))
        assertEquals(4, pool.getInt("hours"))
        assertEquals(1, pool.getInt("api_version"))
        val season = HeatLoadPress.pool("season", mapOf("open" to false))
        assertEquals(false, season.getBoolean("open"))
        val spa = HeatLoadPress.deadline("spa", "later_bath")
        assertEquals("spa", spa.getString("action"))
        assertEquals("later_bath", spa.getString("spa_action"))
        assertTrue(!spa.has("value"))
        assertEquals("session_now", HeatLoadPress.deadline("sauna", "session_now").getString("sauna_action"))
        assertEquals(HeatLoadPress.Outcome.Done, HeatLoadPress.answer(200, """{"api_version": 1, "ok": true, "error": null, "spa": null, "action": "spa"}"""))
        assertEquals(
            HeatLoadPress.Outcome.Refused("spotnav_rate_limited"),
            HeatLoadPress.answer(429, """{"api_version": 1, "ok": false, "error": "spotnav_rate_limited", "spa": null, "action": "spa"}""")
        )
        assertEquals(HeatLoadPress.Outcome.Unsupported, HeatLoadPress.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(HeatLoadPress.Outcome.Failed(null), HeatLoadPress.answer(null, null))
        assertEquals("Försök", HeatLoadsText.pressError(HeatLoadPress.Outcome.Refused("spotnav_invalid_value"), "Försök", "Vänta"))
        assertEquals("Vänta", HeatLoadsText.pressError(HeatLoadPress.Outcome.Refused("spotnav_rate_limited"), "Försök", "Vänta"))
    }

    @Test fun theUseViewHasThePoolsTheSpasAndTheSaunasPartsAndSaysTheyAreCounted() {
        val json = HaFixtures.json("heat_history/zones_month.json").also {
            for (kind in listOf("pool", "spa", "sauna")) {
                it.getJSONArray("parts").put(JSONObject().put("key", kind).put("kind", kind).put("kwh", 12.5).put("cost", JSONObject.NULL).put("name", JSONObject.NULL))
            }
        }
        val history = (se.sensnology.spotnav.ha.heat.HeatHistoryRead.decode(json) as SiteRead.Read).value
        val text = SiteText(CardStringsXml.words("sv"), Locale.forLanguageTag("sv"))
        val loads = history.parts.filter { it.kind in setOf("pool", "spa", "sauna") }
        assertEquals(listOf("Pool", "Spa", "Bastu"), loads.map { HeatUseModel.partWord(text, it) })
        assertEquals(listOf("pool", "spa", "sauna"), loads.map { HeatUseModel.slot(history.parts, it) })
        assertTrue(HeatUseModel.notes(text, history, wantMoney = false).contains("Pool, spa och bastu räknas från när de gick och deras effekt."))
    }
}
