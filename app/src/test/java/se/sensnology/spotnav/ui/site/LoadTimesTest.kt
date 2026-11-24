package se.sensnology.spotnav.ui.site

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.heat.HeatLoadPress
import se.sensnology.spotnav.ha.heat.HeatLoads
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The spa's bath times and the sauna's sessions as the app writes them, and the load dials' steps. */
class LoadTimesTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val now = Instant.parse("2026-11-24T10:00:00Z")
    private val current = listOf(HeatLoads.Session(listOf(5, 6), "19:00"))

    private fun presses(checked: LoadTimes.Checked): List<JSONObject> = (checked as LoadTimes.Checked.Presses).bodies

    @Test fun theWeeklyTimesAreTheWebhooksBathsAndSessions() {
        val body = HeatLoadPress.weekly("spa", listOf(HeatLoads.Session(listOf(1, 2, 3, 4, 5), "06:30")))
        assertEquals("spa", body.getString("action"))
        assertEquals("baths", body.getString("spa_action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals("""[{"weekdays":[1,2,3,4,5],"time":"06:30"}]""", body.getJSONArray("value").toString())
        val sauna = HeatLoadPress.weekly("sauna", emptyList())
        assertEquals("sessions", sauna.getString("sauna_action"))
        assertEquals("[]", sauna.getJSONArray("value").toString())
    }

    @Test fun theOneOffIsAnInstantWithItsOffsetOrNullToClear() {
        val at = LocalDate.of(2026, 11, 25).atTime(18, 0).atZone(zone).toInstant()
        val body = HeatLoadPress.once("spa", at, zone)
        assertEquals("bath_once", body.getString("spa_action"))
        assertEquals("2026-11-25T18:00:00+01:00", body.getString("value"))
        val cleared = HeatLoadPress.once("sauna", null, zone)
        assertEquals("session_once", cleared.getString("sauna_action"))
        assertTrue(cleared.has("value") && cleared.isNull("value"))
    }

    @Test fun theTargetsAreTheDialsActions() {
        val pool = HeatLoadPress.poolTarget(27.5)
        assertEquals("target", pool.getString("pool_action"))
        assertEquals(27.5, pool.getDouble("target_c"), 0.0)
        val spa = HeatLoadPress.deadlineTarget("spa", 38.5)
        assertEquals("bath_c", spa.getString("spa_action"))
        assertEquals(38.5, spa.getDouble("value"), 0.0)
        val sauna = HeatLoadPress.deadlineTarget("sauna", 80.0)
        assertEquals("target_c", sauna.getString("sauna_action"))
        assertEquals(80, sauna.get("value"))
    }

    @Test fun theDialsStepInsideTheirRanges() {
        assertEquals(27.5, LoadTimes.stepped("pool", 27.0, up = true), 0.0)
        assertEquals(40.0, LoadTimes.stepped("pool", 40.0, up = true), 0.0)
        assertEquals(20.0, LoadTimes.stepped("spa", 20.0, up = false), 0.0)
        assertEquals(81.0, LoadTimes.stepped("sauna", 80.0, up = true), 0.0)
        assertEquals(110.0, LoadTimes.stepped("sauna", 110.0, up = true), 0.0)
    }

    @Test fun onlyWhatChangedIsSentWeeklyFirst() {
        assertEquals(emptyList<JSONObject>(), presses(LoadTimes.check("spa", current, null, current, null, now, zone)))
        // Weekdays ticked in another order are the same time.
        assertEquals(emptyList<JSONObject>(), presses(LoadTimes.check("spa", current, null, listOf(HeatLoads.Session(listOf(6, 5), "19:00")), null, now, zone)))
        val both = presses(LoadTimes.check("spa", current, null, emptyList(), LocalDate.of(2026, 11, 25) to LocalTime.of(19, 0), now, zone))
        assertEquals(listOf("baths", "bath_once"), both.map { it.getString("spa_action") })
        val clearOnce = presses(LoadTimes.check("sauna", current, Instant.parse("2026-11-25T17:00:00Z"), current, null, now, zone))
        assertEquals(listOf("session_once"), clearOnce.map { it.getString("sauna_action") })
    }

    @Test fun aTimeWithoutWeekdaysOrAOneOffInThePastIsRefusedWithTheCardsWords() {
        assertEquals(LoadTimes.Checked.Invalid("heat.spa.timesInvalid"), LoadTimes.check("spa", current, null, listOf(HeatLoads.Session(emptyList(), "19:00")), null, now, zone))
        assertEquals(LoadTimes.Checked.Invalid("heat.error.past"), LoadTimes.check("spa", current, null, current, LocalDate.of(2026, 11, 24) to LocalTime.of(9, 0), now, zone))
        val five = List(5) { HeatLoads.Session(listOf(1), "19:00") }
        assertEquals(LoadTimes.Checked.Invalid("heat.spa.timesInvalid"), LoadTimes.check("spa", current, null, five, null, now, zone))
    }
}
