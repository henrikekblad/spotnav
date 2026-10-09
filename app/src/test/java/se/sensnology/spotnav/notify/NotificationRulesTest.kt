package se.sensnology.spotnav.notify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.ha.settings.NotificationEvent.CHARGE_COMPLETE
import se.sensnology.spotnav.ha.settings.NotificationEvent.CHARGE_STARTED
import se.sensnology.spotnav.ha.settings.NotificationEvent.PLAN_AT_RISK
import se.sensnology.spotnav.ha.settings.NotificationEvent.PLAN_INSTALLED
import se.sensnology.spotnav.ha.settings.NotificationEvent.PLAN_STOPPED
import se.sensnology.spotnav.ha.settings.NotificationEvent.PLUGGED_IN
import se.sensnology.spotnav.ha.settings.NotificationEvent.UNPLUGGED
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class NotificationRulesTest {
    private val t0 = Instant.parse("2026-09-22T06:00:00Z")
    private val all = NotificationEvent.entries.toSet()

    /**
     * The idle charger's dashboard with its installed window stated here (06:45-15:30), not taken from how the
     * fixture's plan happens to fall (Home Assistant's automatic charge periods may split it).
     */
    private fun startIdle(): JSONObject = HaFixtures.json("dashboard/start_idle.json").apply {
        getJSONObject("plan").getJSONObject("installed").put("periods", org.json.JSONArray().put(
            JSONObject().put("start", "2026-09-22T06:45:00+00:00").put("end", "2026-09-22T15:30:00+00:00")
        ))
    }

    private fun snap(
        at: Instant = t0,
        charging: Boolean = false,
        connected: Boolean? = true,
        planKey: String? = null,
        planStart: String? = null,
        windowOpen: Boolean = false,
        windowEnd: String? = null,
        windowsLeft: Boolean = false,
        stopReason: String? = null,
        atRisk: Boolean = false,
        departure: String? = null,
        targetReached: Boolean = false,
        targetPercent: Double? = null,
        remainingKwh: Double? = null,
        plannedKwh: Double? = null
    ) = NotificationSnapshot(
        at, charging, connected, planKey, planStart, windowOpen, windowEnd, windowsLeft, stopReason, atRisk,
        departure, targetReached, targetPercent, remainingKwh, plannedKwh
    )

    private fun events(
        before: NotificationSnapshot?,
        now: NotificationSnapshot,
        chosen: Set<NotificationEvent> = all,
        lastSent: Map<NotificationEvent, Instant> = emptyMap()
    ) = NotificationRules.derive(before, now, chosen, lastSent).events

    private val later = t0.plus(Duration.ofMinutes(15))

    @Test fun theFirstCheckIsABaselineAndNeverAnEvent() {
        assertEquals(emptyList<LocalEvent>(), events(null, snap(charging = true, stopReason = "not_started", atRisk = true)))
    }

    @Test fun aCheckLongAfterTheLastStartsAfresh() {
        val stale = snap(at = t0.minus(Duration.ofHours(4)))
        assertEquals(emptyList<LocalEvent>(), events(stale, snap(connected = false)))
    }

    @Test fun aChargeThatStoppedInAWindowIsToldOnceWhileItLasts() {
        val running = snap(charging = true, windowOpen = true, windowsLeft = true)
        val stopped = snap(at = later, charging = false, windowOpen = true, windowsLeft = true, stopReason = "not_started")
        assertEquals(listOf(LocalEvent(PLAN_STOPPED, reason = "stopped")), events(running, stopped))
        // Still stopped at the next check: nothing new.
        assertEquals(emptyList<LocalEvent>(), events(stopped, stopped.copy(at = later.plus(Duration.ofMinutes(15)))))
        // Never started at all.
        val waiting = snap(windowOpen = true, windowsLeft = true)
        assertEquals(listOf(LocalEvent(PLAN_STOPPED, reason = "not_started")), events(waiting, stopped))
        // A named cause is told as such.
        assertEquals(
            listOf(LocalEvent(PLAN_STOPPED, reason = "held_by_charger")),
            events(waiting, stopped.copy(stopReason = "held_by_charger"))
        )
    }

    @Test fun anEventThatIsNotChosenIsNotTold() {
        val running = snap(charging = true, windowOpen = true, windowsLeft = true)
        val stopped = snap(at = later, windowOpen = true, windowsLeft = true, stopReason = "not_started")
        assertEquals(emptyList<LocalEvent>(), events(running, stopped, chosen = setOf(CHARGE_COMPLETE)))
    }

    @Test fun aDepartureAtRiskIsToldWithItsTime() {
        assertEquals(
            listOf(LocalEvent(PLAN_AT_RISK, time = "07:30")),
            events(snap(departure = "07:30"), snap(at = later, atRisk = true, departure = "07:30"))
        )
    }

    @Test fun aCompleteChargeIsToldByWhatCompletedIt() {
        assertEquals(
            listOf(LocalEvent(CHARGE_COMPLETE, reason = "target", percent = 80.0)),
            events(snap(charging = true), snap(at = later, targetReached = true, targetPercent = 80.0))
                .filter { it.event == CHARGE_COMPLETE }
        )
        assertEquals(
            listOf(LocalEvent(CHARGE_COMPLETE, reason = "energy")),
            events(snap(remainingKwh = 3.2), snap(at = later, remainingKwh = 0.0))
        )
        assertEquals(
            listOf(LocalEvent(CHARGE_COMPLETE, reason = "plan_done")),
            events(snap(charging = true, windowOpen = true, windowsLeft = true), snap(at = later))
        )
    }

    @Test fun startsPlugsAndNewPlansAreToldAsTheyChange() {
        assertEquals(
            listOf(LocalEvent(CHARGE_STARTED, time = "17:30")),
            events(snap(), snap(at = later, charging = true, windowOpen = true, windowEnd = "17:30", windowsLeft = true))
                .filter { it.event == CHARGE_STARTED }
        )
        assertEquals(listOf(LocalEvent(PLUGGED_IN)), events(snap(connected = false), snap(at = later, connected = true)))
        assertEquals(listOf(LocalEvent(UNPLUGGED)), events(snap(connected = true), snap(at = later, connected = false)))
        // A charger that cannot say whether a car is connected tells neither.
        assertEquals(emptyList<LocalEvent>(), events(snap(connected = null), snap(at = later, connected = false)))
        assertEquals(
            listOf(LocalEvent(PLAN_INSTALLED, time = "02:00", kwh = 12.5)),
            events(
                snap(planKey = "a"),
                snap(at = later, planKey = "b", planStart = "02:00", windowsLeft = true, plannedKwh = 12.5)
            )
        )
    }

    @Test fun theSameEventWithinFifteenMinutesIsQuietAndThenToldAgain() {
        val out = snap(connected = false)
        val inn = snap(at = later, connected = true)
        val first = NotificationRules.derive(out, inn, all, emptyMap())
        assertEquals(listOf(LocalEvent(PLUGGED_IN)), first.events)
        assertEquals(later, first.lastSent[PLUGGED_IN])
        // Unplugged and plugged in again within the quiet time: the second plug-in is not told.
        val again = NotificationRules.derive(
            out.copy(at = later.plusSeconds(60)), inn.copy(at = later.plusSeconds(600)), all, first.lastSent
        )
        assertEquals(emptyList<LocalEvent>(), again.events)
        assertEquals(later, again.lastSent[PLUGGED_IN])
        // After the quiet time it is.
        val afterwards = NotificationRules.derive(
            out.copy(at = later.plus(Duration.ofMinutes(20))), inn.copy(at = later.plus(Duration.ofMinutes(30))), all, first.lastSent
        )
        assertEquals(listOf(LocalEvent(PLUGGED_IN)), afterwards.events)
    }

    @Test fun aDashboardBecomesASnapshotInTheMarketsClock() {
        val dashboard = Dashboard.parse(startIdle())
        val zone = ZoneId.of("UTC")
        val before = NotificationRules.snapshot(dashboard, Instant.parse("2026-09-22T06:00:00Z"), zone)
        assertFalse(before.windowOpen)
        assertTrue(before.windowsLeft)
        assertEquals("08:45", before.planStart)
        assertNull(before.stopReason)
        assertNull(before.connected)
        // Inside the window but within the grace time: no fault yet.
        assertNull(NotificationRules.snapshot(dashboard, Instant.parse("2026-09-22T06:46:00Z"), zone).stopReason)
        // Inside the window, past the grace time, and not charging.
        val inWindow = NotificationRules.snapshot(dashboard, Instant.parse("2026-09-22T07:00:00Z"), zone)
        assertTrue(inWindow.windowOpen)
        assertEquals("17:30", inWindow.windowEnd)
        assertEquals("not_started", inWindow.stopReason)
        // Paused by a person: no fault.
        val paused = JSONObject(startIdle().toString())
        paused.getJSONObject("status").getJSONArray("lines").put(JSONObject().put("code", "paused").put("params", JSONObject()))
        assertNull(NotificationRules.snapshot(Dashboard.parse(paused), Instant.parse("2026-09-22T07:00:00Z"), zone).stopReason)
        // An unplugged car: no fault either.
        val unplugged = JSONObject(startIdle().toString())
        unplugged.put("connection", JSONObject().put("state", "disconnected").put("source", JSONObject.NULL))
        val out = NotificationRules.snapshot(Dashboard.parse(unplugged), Instant.parse("2026-09-22T07:00:00Z"), zone)
        assertEquals(false, out.connected)
        assertNull(out.stopReason)
    }

    private fun idleWith(code: String, params: JSONObject = JSONObject()): Dashboard {
        val json = startIdle()
        json.getJSONObject("status").getJSONArray("lines").put(JSONObject().put("code", code).put("params", params))
        return Dashboard.parse(json)
    }

    @Test fun aChargerThatIgnoresAPersonsStopIsToldOnceUnderPlanStopped() {
        val zone = ZoneId.of("UTC")
        val quiet = NotificationRules.snapshot(Dashboard.parse(startIdle()), Instant.parse("2026-09-22T06:00:00Z"), zone)
        // Even outside a window and inside the grace time: the Stop is the person's, the charging is the fault.
        val ignored = NotificationRules.snapshot(idleWith("charger_ignores_stop"), Instant.parse("2026-09-22T06:05:00Z"), zone)
        assertEquals("charger_ignores_stop", ignored.stopReason)
        assertEquals(
            listOf(LocalEvent(NotificationEvent.PLAN_STOPPED, reason = "charger_ignores_stop")),
            events(quiet, ignored)
        )
        // While it lasts nothing more is told, and a charge not started becomes this reason once.
        assertEquals(emptyList<LocalEvent>(), events(ignored, ignored.copy(at = later.plus(Duration.ofHours(1)))))
        val notStarted = ignored.copy(stopReason = "not_started")
        assertEquals(
            listOf(LocalEvent(NotificationEvent.PLAN_STOPPED, reason = "charger_ignores_stop")),
            events(notStarted, ignored)
        )
        assertEquals(emptyList<LocalEvent>(), events(quiet, ignored, chosen = setOf(NotificationEvent.CHARGE_STARTED)))
    }

    @Test fun aPersonsManualStopPauseIsNeverAStoppedUnexpectedly() {
        val json = startIdle()
        val manual = HaFixtures.json("dashboard/manual_stop.json")
        json.put("status", manual.getJSONObject("status"))
        json.put("control", manual.getJSONObject("control"))
        val snapshot = NotificationRules.snapshot(Dashboard.parse(json), Instant.parse("2026-09-22T07:00:00Z"), ZoneId.of("UTC"))
        assertTrue(snapshot.windowOpen)
        assertNull(snapshot.stopReason)
    }

    @Test fun aChargeTheCarEndsAtItsOwnLimitIsNoFaultWhenItStops() {
        val full = startIdle()
        full.getJSONObject("status").getJSONArray("lines")
            .put(JSONObject().put("code", "charging_to_vehicle_limit").put("params", JSONObject().put("percent", 100)))
        val snapshot = NotificationRules.snapshot(Dashboard.parse(full), Instant.parse("2026-09-22T07:00:00Z"), ZoneId.of("UTC"))
        assertNull(snapshot.stopReason)
    }

    @Test fun aChargeLoadBalancingPausedIsNoFault() {
        val paused = idleWith(
            "balancing_paused",
            JSONObject().put("retry_at", "2026-09-22T07:15:00+00:00").put("cause", "battery_shares_fuse")
        )
        val snapshot = NotificationRules.snapshot(paused, Instant.parse("2026-09-22T07:00:00Z"), ZoneId.of("UTC"))
        assertNull(snapshot.stopReason)
    }

    @Test fun aCarFinishingPastTheLastWindowIsNoFaultWhenItStops() {
        val finishing = startIdle()
        finishing.getJSONObject("status").getJSONArray("lines")
            .put(JSONObject().put("code", "topping_off").put("params", JSONObject().put("until", "2026-09-22T07:40:00+00:00")))
        val snapshot = NotificationRules.snapshot(Dashboard.parse(finishing), Instant.parse("2026-09-22T07:00:00Z"), ZoneId.of("UTC"))
        assertNull(snapshot.stopReason)
    }

    @Test fun aDeadlineTooShortIsAtRiskAndAMetNeedIsNoFault() {
        val json = startIdle()
        json.getJSONObject("planning").put("reason", "deadline_too_short")
        json.getJSONObject("plan").put("remaining_kwh", 0.0).put("delivered_kwh", 20.0)
        val snapshot = NotificationRules.snapshot(Dashboard.parse(json), Instant.parse("2026-09-22T07:00:00Z"), ZoneId.of("UTC"))
        assertTrue(snapshot.atRisk)
        assertEquals(0.0, snapshot.remainingKwh!!, 0.0)
        assertNull(snapshot.stopReason)
    }

    @Test fun aSnapshotAndTheLastSentTimesSurviveTheStore() {
        val store = LocalNotificationStore(FakeKeyValueStore())
        assertFalse(store.enabled)
        assertEquals(NotificationEvent.LOCAL_DEFAULTS.toSet(), store.events)
        val snapshot = snap(planKey = "k", planStart = "02:00", windowsLeft = true, remainingKwh = 4.5, departure = "07:30")
        store.remember("p", snapshot, mapOf(PLUGGED_IN to later))
        assertEquals(snapshot, store.snapshot("p"))
        assertEquals(mapOf(PLUGGED_IN to later), store.lastSent("p"))
        store.events = setOf(PLUGGED_IN, PLAN_STOPPED)
        assertEquals(setOf(PLUGGED_IN, PLAN_STOPPED), store.events)
        store.forget("p")
        assertNull(store.snapshot("p"))
        assertEquals(emptyMap<NotificationEvent, Instant>(), store.lastSent("p"))
    }
}
