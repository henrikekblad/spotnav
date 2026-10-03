package se.sensnology.spotnav.ha.sessions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SessionsOutcome
import se.sensnology.spotnav.ha.client.SessionsRead
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.HaFixtures
import java.time.LocalDate
import java.time.YearMonth

/** The charge history, against the integration's own vendored `sessions` fixtures. */
class SessionsTest {
    /** What the webhook answers: the WebSocket answer with `ok` and the routing `action` beside it. */
    private fun webhookBody(name: String): String =
        HaFixtures.json("sessions/$name.json").put("ok", true).put("action", "sessions").toString()

    private fun month(name: String = "get_sessions") =
        (SessionsRead.month(200, webhookBody(name)) as SessionsOutcome.Loaded).value

    @Test fun theCurrentMonthIsReadWithEveryDayItsTotalsAndItsCharges() {
        val september = month()
        assertEquals(YearMonth.of(2026, 9), september.month)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8)), september.availableMonths)
        assertEquals(30, september.days.size)
        assertEquals(LocalDate.of(2026, 9, 1), september.days.first().date)
        assertEquals(LocalDate.of(2026, 9, 30), september.days.last().date)
        assertEquals(65.7, september.summary.energyKwh, 1e-9)
        assertEquals(4, september.summary.sessions)
        assertEquals(24.95, september.summary.cost!!, 1e-9)
        assertEquals("kr", september.summary.majorUnit)
        assertEquals("öre", september.summary.minorUnit)
        assertEquals(0.487, september.summary.solarShare!!, 1e-9)
        assertEquals(6.35, september.summary.savings!!, 1e-9)
        assertTrue(september.summary.estimated)
        // Newest first, and the sum of the days is the month.
        assertEquals(september.sessions.sortedByDescending { it.start }, september.sessions)
        assertEquals(september.summary.energyKwh, september.days.sumOf { it.energyKwh }, 1e-9)
        assertEquals(september.summary.sessions, september.days.sumOf { it.sessions })
    }

    @Test fun aDayWithNoChargeIsAZeroRowAndAnUnpricedOneHasNoCost() {
        val september = month()
        val quiet = september.days.first { it.date == LocalDate.of(2026, 9, 3) }
        assertEquals(0.0, quiet.energyKwh, 0.0)
        assertNull(quiet.cost)
        val estimated = september.days.first { it.date == LocalDate.of(2026, 9, 20) }
        assertEquals(9.5, estimated.energyKwh, 0.0)
        assertNull(estimated.cost)
        assertNull(estimated.averagePriceMinorPerKwh)
    }

    @Test fun anotherMonthIsReadTheSameWay() {
        val august = month("get_sessions_month")
        assertEquals(YearMonth.of(2026, 8), august.month)
        assertEquals(31, august.days.size)
        assertEquals(18.4, august.summary.energyKwh, 1e-9)
        assertEquals(1, august.sessions.size)
        assertEquals("Volvo EX30", august.sessions.single().vehicle)
    }

    @Test fun aMonthWithNoChargeIsAnEmptySummaryAndStillHasAllItsDays() {
        val json = HaFixtures.json("sessions/get_sessions.json").put("ok", true)
        json.put("month", "2026-07")
        json.getJSONObject("month_summary").apply {
            put("sessions", 0); put("energy_kwh", 0.0); put("cost", JSONObject.NULL)
            put("average_price_minor_per_kwh", JSONObject.NULL); put("solar_share", JSONObject.NULL)
            put("savings", JSONObject.NULL); put("currency", JSONObject.NULL); put("major_unit", JSONObject.NULL)
            put("minor_unit", JSONObject.NULL); put("estimated", false)
        }
        json.put("month_sessions", org.json.JSONArray())
        val july = (SessionsRead.month(200, json.toString()) as SessionsOutcome.Loaded).value
        assertTrue(july.summary.isEmpty)
        assertNull(july.summary.cost)
        assertTrue(july.sessions.isEmpty())
    }

    @Test fun theCsvAnswerCarriesTheFileNameAndTheText() {
        val csv = (SessionsRead.csv(200, webhookBody("get_sessions_csv")) as SessionsOutcome.Loaded).value
        assertEquals("spotnav-sessions-2026-09-01-2026-09-30.csv", csv.filename)
        assertTrue(csv.csv.startsWith("start,end,energy_kwh"))
    }

    @Test fun theRequestNamesTheMonthAndTheFormat() {
        val plain = SessionsRead.payload(null)
        assertEquals("sessions", plain.getString("action"))
        assertEquals(1, plain.getInt("version"))
        assertEquals(1, plain.getInt("api_version"))
        assertFalse(plain.has("month"))
        assertFalse(plain.has("format"))
        val chosen = SessionsRead.payload(YearMonth.of(2026, 8), csv = true)
        assertEquals("2026-08", chosen.getString("month"))
        assertEquals("csv", chosen.getString("format"))
    }

    @Test fun refusalsAndNonAnswersAreToldApart() {
        val unsupported = """{"ok": false, "error": "spotnav_unsupported_api_version", "action": "sessions"}"""
        assertEquals(SessionsOutcome.Unsupported, SessionsRead.month(400, unsupported))
        assertEquals(SessionsOutcome.Unsupported, SessionsRead.month(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(SessionsOutcome.Failed, SessionsRead.month(400, """{"ok": false, "error": "spotnav_invalid_range"}"""))
        assertEquals(SessionsOutcome.Failed, SessionsRead.month(null, null))
        assertEquals(SessionsOutcome.Failed, SessionsRead.month(200, "not json"))
        // An ok answer that is not charge history is not one.
        assertEquals(SessionsOutcome.Failed, SessionsRead.month(200, """{"ok": true, "action": "sessions"}"""))
        assertEquals(SessionsOutcome.Failed, SessionsRead.csv(200, """{"ok": true, "format": "csv"}"""))
    }

    @Test fun theDashboardsSummaryNamesThisAndLastMonth() {
        val summary = Dashboard.parse(HaFixtures.json("webhook/dashboard.json")).sessionsSummary
        assertNotNull(summary)
        assertEquals("2026-09", summary!!.thisMonth?.period)
        assertEquals("2026-08", summary.lastMonth?.period)
    }

    @Test fun aDashboardWithoutTheBlockOffersNoHistory() {
        val json = HaFixtures.json("webhook/dashboard.json")
        json.remove("sessions_summary")
        assertNull(Dashboard.parse(json).sessionsSummary)
        json.put("sessions_summary", "none")
        assertNull(Dashboard.parse(json).sessionsSummary)
        json.put("sessions_summary", JSONObject().put("this_month", "x"))
        assertNull(Dashboard.parse(json).sessionsSummary)
    }
}
