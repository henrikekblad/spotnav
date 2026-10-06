package se.sensnology.spotnav.ui.charging

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.testing.DashboardFixtures
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The charge bar's numbers: each driver, missing data, clamping, solar, the end and its clock. */
class ChargeBarTest {
    private fun at(text: String): Instant = Instant.parse(text)

    private fun hours(kwh: Double, kw: Double): Duration = Duration.ofMillis(Math.round(kwh / kw * 3_600_000.0))

    private fun close(expected: Instant, actual: Instant?) {
        assertNotNull(actual)
        assertTrue("$expected vs $actual", Duration.between(expected, actual).abs() <= Duration.ofSeconds(1))
    }

    private fun JSONObject.charging(connection: String? = "charging") {
        getJSONObject("live").put("charging", true)
        put("connection", JSONObject().put("state", connection ?: "unknown").put("source", JSONObject.NULL))
    }

    private fun JSONObject.periods(vararg spans: Pair<String, String>) {
        getJSONObject("plan").getJSONObject("installed").put("periods", JSONArray().apply {
            spans.forEach { (start, end) -> put(JSONObject().put("start", start).put("end", end)) }
        })
    }

    private fun JSONObject.soc(
        value: Double?,
        max: Double? = null,
        room: Double? = null,
        need: Double? = null,
        target: Double? = null
    ) {
        put("soc", JSONObject().apply {
            put("age_s", 60); put("capacity_kwh", 77.0); put("efficiency", 0.9); put("estimated", false)
            put("missing", JSONArray()); put("need_kwh", need ?: JSONObject.NULL); put("room_kwh", room ?: JSONObject.NULL)
            put("source", "vehicle"); put("target_percent", target ?: JSONObject.NULL); put("value", value ?: JSONObject.NULL)
            put("vehicle_id", "ev"); put("vehicle_max_percent", max ?: JSONObject.NULL); put("vehicle_name", "EV")
            put("vehicles", JSONArray())
        })
    }

    private fun target(edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("target_soc_estimated.json") { charging(); edit() }

    private fun kwh(edit: JSONObject.() -> Unit = {}): Dashboard = DashboardFixtures.dashboard {
        charging()
        getJSONObject("plan").put("delivered_kwh", 5.0).put("remaining_kwh", 15.0)
        edit()
    }

    private fun started(edit: JSONObject.() -> Unit = {}): Dashboard =
        DashboardFixtures.dashboard("action_pending.json") { charging(); edit() }

    // the target driver: level / target, the end from the need

    private val inWindow = at("2026-09-22T10:00:00Z")

    @Test fun aTargetIsTheLevelAsAShareOfTheTarget() {
        val bar = ChargeBarRule.of(target(), inWindow)!!
        assertEquals(ChargeBarBasis.TARGET, bar.basis)
        // 75.1 of 80, rounded down.
        assertEquals(93, bar.percent)
        assertTrue(bar.moving)
        // 4.22 kWh at the installed 10 A on one phase (2.3 kW), inside the 08:15-23:15 window.
        close(inWindow.plus(hours(4.22, 2.3)), bar.endsAt)
    }

    @Test fun theMeasuredCurrentGoesBeforeTheSchedulesCurrent() {
        val bar = ChargeBarRule.of(target { getJSONObject("live").put("measured_current_a", 16.0) }, inWindow)!!
        close(inWindow.plus(hours(4.22, 3.68)), bar.endsAt)
    }

    @Test fun theSchedulesOwnPowerGoesBeforeItsCurrent() {
        val bar = ChargeBarRule.of(target { getJSONObject("plan").getJSONObject("installed").put("power_kw", 2.11) }, inWindow)!!
        close(inWindow.plus(hours(4.22, 2.11)), bar.endsAt)
    }

    @Test fun theEndNeverFallsAfterThePlansLastWindow() {
        val bar = ChargeBarRule.of(target { getJSONObject("soc").put("need_kwh", 80.0) }, inWindow)!!
        assertEquals(at("2026-09-22T23:15:00Z"), bar.endsAt)
    }

    @Test fun theEndSkipsTheGapsBetweenWindows() {
        val bar = ChargeBarRule.of(target {
            getJSONObject("soc").put("need_kwh", 2.3)
            periods("2026-09-22T10:00:00Z" to "2026-09-22T10:30:00Z", "2026-09-22T12:00:00Z" to "2026-09-22T13:00:00Z")
        }, inWindow)!!
        assertEquals(at("2026-09-22T12:30:00Z"), bar.endsAt)
    }

    // a charge the plan does not drive now: the car's own limit, whatever the driver

    private fun JSONObject.statusLines(vararg lines: JSONObject) {
        put("status", JSONObject().put("tone", "normal").put("lines", JSONArray().apply { lines.forEach { put(it) } }))
    }

    private fun line(code: String, vararg params: Pair<String, Any?>) = JSONObject().put("code", code).put(
        "params", JSONObject().apply { params.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
    )

    @Test fun aChargeUnderAScheduledPauseCountsToTheCarsLimitNotTheTarget() {
        // The schedule paused until tomorrow, the car charging anyway, and no window now: the
        // owner's screen, which showed "96 % av målet" for a car at 89 % charging to its 100 %.
        val now = at("2026-09-22T07:00:00Z")
        val bar = ChargeBarRule.of(target {
            soc(89.0, room = 9.4, need = 0.0, target = 93.0)
            statusLines(
                line("paused", "until" to "2026-09-22T22:00:00+00:00", "choice" to "until_tomorrow", "action" to null, "ends" to null),
                line("charging_now", "until" to null)
            )
        }, now)!!
        assertEquals(ChargeBarBasis.CAR_LIMIT, bar.basis)
        assertEquals(89, bar.percent)
        close(now.plus(hours(9.4, 2.3)), bar.endsAt)
    }

    @Test fun outsideTheWindowsTheTargetGivesWayToTheCarsLimit() {
        val now = at("2026-09-22T07:00:00Z")
        val bar = ChargeBarRule.of(target { soc(75.1, max = 90.0, room = 12.0, need = 4.22, target = 80.0) }, now)!!
        assertEquals(ChargeBarBasis.CAR_LIMIT, bar.basis)
        assertEquals(83, bar.percent)
        close(now.plus(hours(12.0, 2.3)), bar.endsAt)
    }

    @Test fun aPausedScheduleDoesNotDriveTheChargeEvenInsideAWindow() {
        val bar = ChargeBarRule.of(target {
            soc(60.0, room = 30.0, need = 15.0, target = 80.0)
            statusLines(line("paused", "until" to null, "choice" to "until_resumed", "action" to null, "ends" to null))
        }, inWindow)!!
        assertEquals(ChargeBarBasis.CAR_LIMIT, bar.basis)
        assertEquals(60, bar.percent)
        // The straight line from now, not the schedule's windows.
        close(inWindow.plus(hours(30.0, 2.3)), bar.endsAt)
    }

    @Test fun anAmountOutsideThePlanWithNoLevelIsTheOpenBar() {
        val bar = ChargeBarRule.of(kwh { getJSONObject("plan").put("installed", JSONObject.NULL) }, kwhNow)!!
        assertEquals(ChargeBarBasis.OPEN, bar.basis)
        assertNull(bar.percent)
        assertNull(bar.endsAt)
    }

    @Test fun aSunChargeOutsideThePlanCountsToTheLimitWithoutAnEnd() {
        for (strategy in listOf("solar", "hybrid")) {
            val bar = ChargeBarRule.of(kwh {
                getJSONObject("settings").put("strategy", strategy)
                getJSONObject("plan").put("installed", JSONObject.NULL)
                soc(40.0, room = 40.0)
            }, kwhNow)!!
            assertEquals(strategy, ChargeBarBasis.CAR_LIMIT, bar.basis)
            assertEquals(40, bar.percent)
            assertNull(strategy, bar.endsAt)
        }
    }

    @Test fun theConnectionLineIsHiddenWhileTheBarSaysItCharges() {
        assertFalse(ChargeBarLayout.connectionLineShown(ChargeBarRule.of(kwh(), kwhNow)))
        assertTrue(ChargeBarLayout.connectionLineShown(null))
    }

    @Test fun aLevelAboveTheTargetIsHundredAndHasNoEnd() {
        val bar = ChargeBarRule.of(target {
            getJSONObject("soc").put("value", 85.0).put("need_kwh", 0.0)
        }, inWindow)!!
        assertEquals(100, bar.percent)
        assertNull(bar.endsAt)
    }

    @Test fun theTargetIsNeverAboveTheCarsLimit() {
        val bar = ChargeBarRule.of(target { getJSONObject("soc").put("vehicle_max_percent", 75.0).put("value", 60.0) }, inWindow)!!
        assertEquals(80, bar.percent)
    }

    @Test fun aTargetWithoutALevelOrTargetHasNoBar() {
        assertNull(ChargeBarRule.of(target { getJSONObject("soc").put("value", JSONObject.NULL) }, inWindow))
        assertNull(ChargeBarRule.of(target { put("soc", JSONObject.NULL) }, inWindow))
        assertNull(ChargeBarRule.of(target {
            getJSONObject("soc").put("target_percent", JSONObject.NULL)
            getJSONObject("settings").getJSONObject("target").put("target_percent", JSONObject.NULL)
        }, inWindow))
    }

    @Test fun aTargetWithoutANeedHasAPercentButNoEnd() {
        val bar = ChargeBarRule.of(target { getJSONObject("soc").put("need_kwh", JSONObject.NULL) }, inWindow)!!
        assertEquals(93, bar.percent)
        assertNull(bar.endsAt)
    }

    // a fixed amount: delivered / the whole amount

    private val kwhNow = at("2026-09-22T07:00:00Z")

    @Test fun anAmountIsTheDeliveredShare() {
        val bar = ChargeBarRule.of(kwh(), kwhNow)!!
        assertEquals(ChargeBarBasis.KWH, bar.basis)
        assertEquals(25, bar.percent)
        close(kwhNow.plus(hours(15.0, 2.3)), bar.endsAt)
    }

    @Test fun withoutARemainderTheRequestedAmountIsTheWhole() {
        val bar = ChargeBarRule.of(kwh {
            getJSONObject("plan").put("remaining_kwh", JSONObject.NULL)
            getJSONObject("plan").getJSONObject("proposal").put("requested_kwh", 20.0)
        }, kwhNow)!!
        assertEquals(25, bar.percent)
        close(kwhNow.plus(hours(15.0, 2.3)), bar.endsAt)
    }

    @Test fun anAmountWithNothingDeliveredKnownHasNoBar() {
        assertNull(ChargeBarRule.of(kwh { getJSONObject("plan").put("delivered_kwh", JSONObject.NULL) }, kwhNow))
    }

    @Test fun aDeliveredAmountIsHundredAndHasNoEnd() {
        val bar = ChargeBarRule.of(kwh { getJSONObject("plan").put("delivered_kwh", 20.0).put("remaining_kwh", 0.0) }, kwhNow)!!
        assertEquals(100, bar.percent)
        assertNull(bar.endsAt)
    }

    @Test fun withNoPowerKnownThereIsAPercentButNoEnd() {
        val bar = ChargeBarRule.of(kwh {
            getJSONObject("plan").getJSONObject("installed").put("amps", JSONObject.NULL).put("power_kw", JSONObject.NULL)
            getJSONObject("plan").put("proposal", JSONObject.NULL)
            getJSONObject("settings").put("amps", JSONObject.NULL)
        }, kwhNow)!!
        assertEquals(25, bar.percent)
        assertNull(bar.endsAt)
    }

    // Fill: level / the car's limit, the end from the room

    @Test fun fillIsTheLevelAsAShareOfTheCarsLimit() {
        val bar = ChargeBarRule.of(kwh {
            getJSONObject("settings").put("fill_to_limit", true)
            soc(60.0, max = 80.0, room = 17.1)
        }, kwhNow)!!
        assertEquals(ChargeBarBasis.FILL, bar.basis)
        assertEquals(75, bar.percent)
        close(kwhNow.plus(hours(17.1, 2.3)), bar.endsAt)
    }

    @Test fun fillWithoutALimitCountsToHundred() {
        val bar = ChargeBarRule.of(kwh { getJSONObject("settings").put("fill_to_limit", true); soc(42.0) }, kwhNow)!!
        assertEquals(42, bar.percent)
        assertNull(bar.endsAt)
    }

    @Test fun fillWithoutALevelHasNoBar() {
        assertNull(ChargeBarRule.of(kwh { getJSONObject("settings").put("fill_to_limit", true) }, kwhNow))
    }

    // solar and hybrid follow the sun: the percent, never an end

    @Test fun solarAndHybridShowThePercentWithoutAnEnd() {
        for (strategy in listOf("solar", "hybrid")) {
            val bar = ChargeBarRule.of(kwh { getJSONObject("settings").put("strategy", strategy) }, kwhNow)!!
            assertEquals(25, bar.percent)
            assertNull(strategy, bar.endsAt)
        }
    }

    // a person's Start: to the car's own limit, or the open bar

    @Test fun aPersonsStartCountsToTheCarsLimitWithTheRoomFromTheBattery() {
        val now = at("2026-09-22T06:00:00Z")
        val bar = ChargeBarRule.of(started {
            soc(50.0, max = 80.0)
            getJSONObject("live").put("measured_current_a", 16.0)
        }, now)!!
        assertEquals(ChargeBarBasis.CAR_LIMIT, bar.basis)
        assertEquals(62, bar.percent)
        // 77 kWh x 30 % / 0.9, at 16 A on the one phase the charge uses.
        close(now.plus(hours(77.0 * 30.0 / 100.0 / 0.9, 3.68)), bar.endsAt)
    }

    @Test fun aPersonsStartPrefersTheStatedRoom() {
        val now = at("2026-09-22T06:00:00Z")
        val bar = ChargeBarRule.of(started { soc(50.0, room = 10.0) }, now)!!
        assertEquals(50, bar.percent)
        // No schedule and no measurement: the settings' 10 A on one phase.
        close(now.plus(hours(10.0, 2.3)), bar.endsAt)
    }

    @Test fun aPersonsStartWithoutALevelIsTheOpenBarWithItsPower() {
        val bar = ChargeBarRule.of(started { getJSONObject("live").put("measured_current_a", 16.0) }, at("2026-09-22T06:00:00Z"))!!
        assertEquals(ChargeBarBasis.OPEN, bar.basis)
        assertNull(bar.percent)
        assertNull(bar.endsAt)
        assertEquals(3.68, bar.powerKw!!, 1e-9)
        assertTrue(bar.moving)
    }

    @Test fun aPersonsStartUnderSolarStillStatesItsEnd() {
        val now = at("2026-09-22T06:00:00Z")
        val bar = ChargeBarRule.of(started { getJSONObject("settings").put("strategy", "solar"); soc(50.0, room = 10.0) }, now)!!
        assertNotNull(bar.endsAt)
    }

    @Test fun aPersonsStopIsNotAStart() {
        assertFalse(ChargeBarRule.personStarted(DashboardFixtures.dashboard("manual_stop.json")))
        assertTrue(ChargeBarRule.personStarted(DashboardFixtures.dashboard("action_pending.json")))
    }

    // visibility and motion

    @Test fun noBarWhileTheChargeIsOff() {
        assertNull(ChargeBarRule.of(kwh { getJSONObject("live").put("charging", false) }, kwhNow))
        assertNull(ChargeBarRule.of(null, kwhNow))
    }

    @Test fun noBarWhileStartingUpOrForNoCarAFullCarOrAnError() {
        assertNull(ChargeBarRule.of(kwh {
            put("starting_up", JSONObject().put("active", true).put("until", "2026-09-22T07:03:00+00:00").put("waiting_for", JSONArray()))
        }, kwhNow))
        for (state in listOf("disconnected", "finished", "error")) {
            assertNull(state, ChargeBarRule.of(kwh { charging(state) }, kwhNow))
        }
    }

    @Test fun aPausedChargeStandsStillAndStatesNoEnd() {
        for (state in listOf("paused", "connected")) {
            val bar = ChargeBarRule.of(kwh { charging(state) }, kwhNow)!!
            assertFalse(state, bar.moving)
            assertEquals(25, bar.percent)
            assertNull(bar.endsAt)
        }
    }

    @Test fun aCarThatTakesNoCurrentStandsStill() {
        val bar = ChargeBarRule.of(kwh {
            put("charge_progress", JSONObject().put("state", "vehicle_not_requesting_current")
                .put("reason", "suspended_ev_zero_current").put("since", "2026-09-22T06:50:00+00:00"))
        }, kwhNow)!!
        assertFalse(bar.moving)
    }

    @Test fun withNoConnectionStatedTheChargeMoves() {
        assertTrue(ChargeBarRule.of(kwh { charging(null) }, kwhNow)!!.moving)
    }

    @Test fun theSheenMovesOnlyWhileCurrentFlowsAndAnimationsAreOn() {
        val moving = ChargeBarRule.of(kwh(), kwhNow)
        val still = ChargeBarRule.of(kwh { charging("paused") }, kwhNow)
        assertTrue(ChargeBarMotion.animate(moving, animatorsEnabled = true))
        assertFalse(ChargeBarMotion.animate(moving, animatorsEnabled = false))
        assertFalse(ChargeBarMotion.animate(still, animatorsEnabled = true))
        assertFalse(ChargeBarMotion.animate(null, animatorsEnabled = true))
    }

    @Test fun percentIsRoundedDownAndClamped() {
        assertEquals(0, ChargeBarRule.percent(-0.2))
        assertEquals(99, ChargeBarRule.percent(0.999))
        assertEquals(100, ChargeBarRule.percent(1.0))
        assertEquals(100, ChargeBarRule.percent(1.3))
        assertEquals(57, ChargeBarRule.percent(0.57))
        assertNull(ChargeBarRule.percent(Double.NaN))
    }

    // the end's clock: the phone's zone and 12/24-hour format

    private val stockholm = ZoneId.of("Europe/Stockholm")

    @Test fun theClockIsThePhonesTwentyFourHourTime() {
        assertEquals("14:35", ChargeBarText.clock(at("2026-10-06T12:35:00Z"), at("2026-10-06T10:00:00Z"), stockholm, true, Locale("sv")))
    }

    @Test fun theClockIsThePhonesTwelveHourTime() {
        assertEquals("2:35 PM", ChargeBarText.clock(at("2026-10-06T12:35:00Z"), at("2026-10-06T10:00:00Z"), stockholm, false, Locale.ENGLISH))
    }

    @Test fun anotherDayNamesTheWeekday() {
        assertEquals("Wed 01:30", ChargeBarText.clock(at("2026-10-06T23:30:00Z"), at("2026-10-06T21:00:00Z"), stockholm, true, Locale.ENGLISH))
    }

    @Test fun theClockFollowsTheAutumnShift() {
        // 02:00 CEST, then 02:30 CET once the clocks went back at 03:00 CEST.
        assertEquals("02:30", ChargeBarText.clock(at("2026-10-25T01:30:00Z"), at("2026-10-25T00:00:00Z"), stockholm, true, Locale("sv")))
    }

    @Test fun theClockFollowsTheSpringShift() {
        // 01:30 CET, then 03:15 CEST past the skipped hour.
        assertEquals("03:15", ChargeBarText.clock(at("2026-03-29T01:15:00Z"), at("2026-03-29T00:30:00Z"), stockholm, true, Locale("sv")))
    }

    @Test fun theClockInUtc() {
        assertEquals("12:35", ChargeBarText.clock(at("2026-10-06T12:35:00Z"), at("2026-10-06T10:00:00Z"), ZoneId.of("UTC"), true, Locale.ENGLISH))
    }

    @Test fun thePowerIsOneDecimalInTheNumberLocale() {
        assertEquals("11", ChargeBarText.kw(11.04, Locale("sv")))
        assertEquals("3,7", ChargeBarText.kw(3.68, Locale("sv")))
        assertEquals("3.7", ChargeBarText.kw(3.68, Locale.ENGLISH))
    }
}
