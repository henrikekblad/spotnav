package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.WebhookReads
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.ui.charging.CurrentReadout
import se.sensnology.spotnav.ui.charging.DepartureCalendarNote
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * What Home Assistant 3.0 adds to the dashboard and this app asks for (`sun`, `power_charger`, `supplier_fees`,
 * `departure_calendar`): read where it is sent, and absent without harm from a 2.x Home Assistant.
 */
class DashboardNewerReadsTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")

    @Test fun theAppAsksForTheFourReadsOfHomeAssistant3() {
        assertTrue(WebhookReads.FIELDS.containsAll(listOf("sun", "power_charger", "supplier_fees", "departure_calendar")))
        assertEquals("no read is named twice", WebhookReads.FIELDS.size, WebhookReads.FIELDS.toSet().size)
    }

    @Test fun everyVendoredDashboardStillDecodes() {
        val files = HaFixtures.files("dashboard")
        assertTrue(files.size >= 27)
        files.forEach { file -> assertNotNull(file.name, Dashboard.parse(JSONObject(file.readText()))) }
    }

    @Test fun aChargerGivenAPowerStatesItsKilowattsPerAmpere() {
        val powered = DashboardFixtures.dashboard("dc_charger_power.json")
        assertEquals(0.6928, powered.currentRange.kwPerA!!, 1e-9)
        assertEquals(11.7776, CurrentReadout.powerKw(17, powered.currentRange.kwPerA)!!, 1e-9)
        // A charger given a current, and any 2.x Home Assistant: amperes.
        val amperes = DashboardFixtures.dashboard("start_idle.json")
        assertNull(amperes.currentRange.kwPerA)
        assertNull(CurrentReadout.powerKw(16, amperes.currentRange.kwPerA))
        // A figure outside the contract's range is not a power.
        for (bad in listOf(0, -1, 2.5, "0.69")) {
            assertNull(DashboardFixtures.dashboard("start_idle.json") { getJSONObject("current_range").put("kw_per_a", bad) }.currentRange.kwPerA)
        }
    }

    private fun sun(edit: JSONObject.() -> Unit = {}) = JSONObject()
        .put("hours", JSONArray()
            .put(JSONObject().put("start", "2026-09-22T09:00:00+00:00").put("kw", 2.4).put("measured", false))
            .put(JSONObject().put("start", "2026-09-22T07:00:00+00:00").put("kw", 1.1).put("measured", true)))
        .put("quarters", JSONArray().put(JSONObject().put("start", "2026-09-22T08:15:00+00:00").put("kw", 1.8)))
        .put("peak_kw", 8.4)
        .apply(edit)

    @Test fun theSunBlockIsReadSortedWithItsQuartersAndPeak() {
        val block = DashboardSun.parse(sun())!!
        assertEquals(listOf(Instant.parse("2026-09-22T07:00:00Z"), Instant.parse("2026-09-22T09:00:00Z")), block.hours.map { it.start })
        assertEquals(listOf(true, false), block.hours.map { it.measured })
        assertEquals(SunQuarter(Instant.parse("2026-09-22T08:15:00Z"), 1.8), block.quarters.single())
        assertEquals(8.4, block.peakKw!!, 0.0)
        // The quarters and the peak are additive, and a key this app does not know is ignored.
        val bare = DashboardSun.parse(sun { remove("quarters"); remove("peak_kw"); put("something_new", 1) })!!
        assertTrue(bare.quarters.isEmpty())
        assertNull(bare.peakKw)
    }

    @Test fun aSunBlockThatIsNotWhatTheContractSaysIsNoBlock() {
        assertNull(DashboardSun.parse(null))
        assertNull(DashboardSun.parse(JSONObject.NULL))
        assertNull(DashboardSun.parse(sun { remove("hours") }))
        assertNull(DashboardSun.parse(sun { put("peak_kw", 0) }))
        assertNull(DashboardSun.parse(sun { getJSONArray("hours").getJSONObject(0).put("kw", -1) }))
        assertNull(DashboardSun.parse(sun { getJSONArray("hours").getJSONObject(0).put("measured", "yes") }))
        assertNull(DashboardSun.parse(sun { getJSONArray("quarters").getJSONObject(0).put("start", "yesterday") }))
    }

    @Test fun theDashboardCarriesTheSunWhereHomeAssistantSendsIt() {
        // The vendored hybrid site has the block, with nothing measured or forecast in it.
        assertEquals(DashboardSun(emptyList(), emptyList(), null), DashboardFixtures.dashboard("hybrid_derived_site_with_forecast.json").sun)
        assertEquals(8.4, DashboardFixtures.dashboard("start_idle.json") { put("sun", sun()) }.sun!!.peakKw!!, 0.0)
        // Home Assistant 2.x sends none, and an unreadable one never refuses the dashboard.
        assertNull(DashboardFixtures.dashboard("start_idle.json") { remove("sun") }.sun)
        assertNull(DashboardFixtures.dashboard("start_idle.json") { put("sun", "bright") }.sun)
    }

    private fun calendar(state: Any?, edit: JSONObject.() -> Unit = {}) = JSONObject()
        .put("calendars", JSONArray().put(JSONObject().put("entity_id", "calendar.family").put("name", "Family")))
        .put("entity_id", "calendar.family")
        .put("state", state ?: JSONObject.NULL)
        .put("departure", "2026-09-23T05:30:00+00:00")
        .put("summary", "Work")
        .put("all_day", false)
        .put("target_percent", 80)
        .put("target_kwh", JSONObject.NULL)
        .put("target_used", "percent")
        .apply(edit)

    @Test fun aUsedCalendarNamesItsDepartureInTheMarketsZoneAndTheTargetThePlanTook() {
        val block = DashboardDepartureCalendar.parse(calendar("used"))!!
        assertEquals("Family", block.name)
        assertEquals(
            DepartureCalendarNote.Used(LocalDate.of(2026, 9, 23), LocalTime.of(7, 30), 80.0, null, targetIgnored = false),
            DepartureCalendarNote.of(block, departureEnabled = true, zone = stockholm)
        )
        // A target of the other kind is not used, and said so.
        val ignored = DashboardDepartureCalendar.parse(calendar("used") { put("target_used", JSONObject.NULL) })!!
        assertEquals(
            DepartureCalendarNote.Used(LocalDate.of(2026, 9, 23), LocalTime.of(7, 30), null, null, targetIgnored = true),
            DepartureCalendarNote.of(ignored, departureEnabled = true, zone = stockholm)
        )
        // With the departure switched off, or no zone to place it in, there is nothing to say.
        assertNull(DepartureCalendarNote.of(block, departureEnabled = false, zone = stockholm))
        assertNull(DepartureCalendarNote.of(block, departureEnabled = true, zone = null))
    }

    @Test fun theOtherStatesAreTheirOwnNotesAndNoChosenCalendarSaysNothing() {
        fun note(state: Any?, edit: JSONObject.() -> Unit = {}) =
            DepartureCalendarNote.of(DashboardDepartureCalendar.parse(calendar(state, edit)), true, stockholm)
        assertEquals(DepartureCalendarNote.Daily, note("daily"))
        assertEquals(DepartureCalendarNote.Date, note("date"))
        assertEquals(DepartureCalendarNote.Unavailable, note("unavailable"))
        assertEquals(DepartureCalendarNote.NoEvent, note("no_event"))
        assertNull(note("off"))
        assertNull(note(null))
        assertNull(note("daily") { put("entity_id", JSONObject.NULL) })
        assertNull(DepartureCalendarNote.of(null, true, stockholm))
    }

    @Test fun aCalendarBlockThatIsNotWhatTheContractSaysIsNoBlock() {
        assertNull(DashboardDepartureCalendar.parse(calendar("someday")))
        assertNull(DashboardDepartureCalendar.parse(calendar("used") { remove("departure") }))
        assertNull(DashboardDepartureCalendar.parse(calendar("used") { put("departure", "soon") }))
        assertNull(DashboardDepartureCalendar.parse(calendar("used") { put("target_used", "miles") }))
        // The vendored dashboards have no calendar, as any 2.x Home Assistant.
        assertNull(DashboardFixtures.dashboard("start_idle.json").departureCalendar)
        assertFalse(DashboardFixtures.dashboard("start_idle.json") { put("departure_calendar", calendar("used")) }.departureCalendar == null)
    }

    @Test fun aSettingsRecordWithTheCalendarFieldsIsReadAndTheyAreNeverSentBack() {
        val record = HaFixtures.json("settings/v1/success.json").getJSONObject("settings")
        assertTrue(record.has("departure_calendar") && record.has("departure_calendar_only"))
        val read = se.sensnology.spotnav.ha.settings.HaSettingsCodec.parseResponse(record)
        val body = se.sensnology.spotnav.ha.settings.HaSettingsCodec.encodeBody(read)
        // Home Assistant keeps what a replacement leaves out.
        assertFalse(body.has("departure_calendar"))
        assertFalse(body.has("departure_calendar_only"))
    }
}
