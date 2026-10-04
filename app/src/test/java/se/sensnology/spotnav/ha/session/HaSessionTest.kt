package se.sensnology.spotnav.ha.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.authority.DashboardAdmission
import se.sensnology.spotnav.ha.client.FetchedDashboard
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.client.VehicleField
import se.sensnology.spotnav.ha.client.VehicleUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.VehicleRefresh

/** The session's ordering rules, against a fake transport and executors that run in place: */
class HaSessionTest {
    /** A transport that records what was asked of it, in order, and answers from what a test sets. */
    private class FakeTransport : HaTransport {
        val calls = mutableListOf<String>()
        var dashboards = 0
        var sendFails = false
        var dashboardFails = false
        var refreshAnswer: VehicleRefresh.Answer = VehicleRefresh.Answer.Refreshed
        val served = mutableListOf<Dashboard>()

        override fun dashboard(): FetchedDashboard {
            calls += "dashboard"
            dashboards++
            if (dashboardFails) throw IllegalStateException("unreachable")
            return FetchedDashboard(DashboardFixtures.dashboard().also { served += it }, "{}")
        }

        override fun send(command: HomeAssistantCommand) {
            calls += "send:${command.action}"
            if (sendFails) throw IllegalStateException("refused")
        }

        override fun refreshVehicle(vehicleId: String): VehicleRefresh.Answer {
            calls += "refresh:$vehicleId"
            return refreshAnswer
        }

        override fun setChargeLimit(vehicleId: String, percent: Int): ChargeLimit.Answer {
            calls += "limit:$vehicleId:$percent"
            return ChargeLimit.Answer.Set
        }

        override fun updateVehicle(vehicleId: String, changes: List<VehicleUpdate.FieldChange>): VehicleUpdate.Outcome {
            calls += "vehicle:$vehicleId"
            return VehicleUpdate.Outcome.Failed(null)
        }

        override fun updateSettings(expectedRevision: Int, replacement: HaPlanningSettings): SettingsUpdate.Outcome {
            calls += "settings:$expectedRevision"
            return SettingsUpdate.Outcome.Unavailable
        }

        override fun updateSite(request: SiteUpdate.Request): SiteUpdate.Outcome {
            calls += "site"
            return SiteUpdate.Outcome.Failed(null)
        }
    }

    private class Scheduled(val delayMs: Long, val block: () -> Unit)

    private class Rig(val transport: FakeTransport = FakeTransport()) {
        var current = true
        val recorded = mutableListOf<Result<FetchedDashboard>>()
        val scheduled = mutableListOf<Scheduled>()
        val session = HaSession(
            transport = transport,
            recorder = { result -> recorded += result; if (result.isSuccess) 3 else null },
            background = { it.run() },
            mainThread = { block -> if (current) block() },
            schedule = { delayMs, block -> scheduled += Scheduled(delayMs) { if (current) block() } }
        )
    }

    private val admission = DashboardAdmission("charger", 1, 7L)

    @Test fun aReadReservesItsIdentityBeforeTheRequestAndCarriesItWithTheAnswer() {
        val rig = Rig()
        var reads = 0
        var got: HaSession.Read? = null
        rig.session.read(
            admit = { reads++; rig.transport.calls += "admit"; admission },
            done = { got = it }
        )
        assertEquals(listOf("admit", "dashboard"), rig.transport.calls)
        assertEquals(1, reads)
        assertSame(admission, got!!.admission)
        assertTrue(got!!.result.isSuccess)
        assertEquals(3, got!!.detectedPhases)
    }

    @Test fun aFailedReadIsRecordedAndHandedOnAsAFailure() {
        val rig = Rig(FakeTransport().apply { dashboardFails = true })
        var got: HaSession.Read? = null
        rig.session.read(admit = { admission }, done = { got = it })
        assertTrue(got!!.result.isFailure)
        assertEquals(1, rig.recorded.size)
        assertNull(got!!.detectedPhases)
    }

    @Test fun aStaleScreenHearsNothing() {
        val rig = Rig()
        rig.current = false
        var heard = false
        rig.session.read(admit = { admission }, done = { heard = true })
        rig.session.setChargeLimit("car", 80) { heard = true }
        assertFalse(heard)
    }

    @Test fun aCommandIsFollowedByAConfirmationReadAndIsRecorded() {
        val rig = Rig()
        var confirmed: HaSession.Sent? = null
        rig.session.send(HomeAssistantCommand("start", amps = 10, phases = 3), { admission }, { confirmed = it }, {})
        assertEquals(listOf("send:start", "dashboard"), rig.transport.calls)
        assertTrue(confirmed!!.result.isSuccess)
        assertEquals(1, rig.recorded.size)
    }

    @Test fun theConfirmationReadIsAdmittedOnlyOnceTheCommandWasAccepted() {
        val rig = Rig()
        var confirmed: HaSession.Sent? = null
        rig.session.send(HomeAssistantCommand("start"), { admission }, { confirmed = it }, {})
        assertSame(admission, confirmed!!.admission)

        val refused = Rig(FakeTransport().apply { sendFails = true })
        var admitted = false
        refused.session.send(HomeAssistantCommand("start"), { admitted = true; admission }, { confirmed = it }, {})
        assertFalse(admitted)
        assertNull(confirmed!!.admission)
    }

    @Test fun aStartIsFollowedByOneMoreOrdinaryReadAfterTheDelay() {
        val rig = Rig()
        var refreshed: HaSession.Read? = null
        rig.session.send(HomeAssistantCommand("start"), { admission }, {}, { refreshed = it })
        assertNull(refreshed)
        assertEquals(1, rig.scheduled.size)
        assertEquals(HaSession.FOLLOW_UP_MS, rig.scheduled.single().delayMs)
        rig.scheduled.single().block()
        assertEquals(listOf("send:start", "dashboard", "dashboard"), rig.transport.calls)
        assertNotNull(refreshed)
        assertSame(admission, refreshed!!.admission)
        assertEquals(2, rig.recorded.size)
    }

    @Test fun aStopIsFollowedTheSameWay() {
        val rig = Rig()
        rig.session.send(HomeAssistantCommand("stop"), { admission }, {}, {})
        assertEquals(1, rig.scheduled.size)
    }

    @Test fun otherCommandsGetTheConfirmationReadAndNothingMore() {
        val rig = Rig()
        rig.session.send(HomeAssistantCommand("settings"), { admission }, {}, {})
        assertEquals(listOf("send:settings", "dashboard"), rig.transport.calls)
        assertTrue(rig.scheduled.isEmpty())
    }

    @Test fun aCommandThatFailedReadsNothingAndSchedulesNothing() {
        val rig = Rig(FakeTransport().apply { sendFails = true })
        var confirmed: HaSession.Sent? = null
        rig.session.send(HomeAssistantCommand("start"), { admission }, { confirmed = it }, {})
        assertEquals(listOf("send:start"), rig.transport.calls)
        assertTrue(confirmed!!.result.isFailure)
        assertTrue(rig.scheduled.isEmpty())
    }

    @Test fun theFollowUpDiesWithTheScreenItWasScheduledFor() {
        val rig = Rig()
        var refreshed = false
        rig.session.send(HomeAssistantCommand("stop"), { admission }, {}, { refreshed = true })
        rig.current = false
        rig.scheduled.single().block()
        assertFalse(refreshed)
        assertEquals(listOf("send:stop", "dashboard"), rig.transport.calls)
    }

    @Test fun aVehicleReReadIsFollowedByAReadOnlyWhenTheIntegrationReRead() {
        val rig = Rig()
        var done: HaSession.ReRead? = null
        rig.session.reReadVehicle("car") { done = it }
        assertEquals(listOf("refresh:car", "dashboard"), rig.transport.calls)
        assertNotNull(done!!.read)

        val refused = Rig(FakeTransport().apply { refreshAnswer = VehicleRefresh.Answer.Failed })
        var refusedDone: HaSession.ReRead? = null
        refused.session.reReadVehicle("car") { refusedDone = it }
        assertEquals(listOf("refresh:car"), refused.transport.calls)
        assertNull(refusedDone!!.read)
        assertEquals(VehicleRefresh.Answer.Failed, refusedDone!!.answer)
    }

    @Test fun aChargeLimitWriteIsNotFollowedByARead() {
        val rig = Rig()
        var answer: ChargeLimit.Answer? = null
        rig.session.setChargeLimit("car", 80) { answer = it }
        assertEquals(listOf("limit:car:80"), rig.transport.calls)
        assertEquals(ChargeLimit.Answer.Set, answer)
    }

    @Test fun aSiteReadThatNobodyRecordsIsNotRecorded() {
        val rig = Rig()
        var got: Dashboard? = null
        rig.session.peekDashboard { got = it }
        assertNotNull(got)
        assertTrue(rig.recorded.isEmpty())

        val failing = Rig(FakeTransport().apply { dashboardFails = true })
        var failed: Dashboard? = DashboardFixtures.dashboard()
        failing.session.peekDashboard { failed = it }
        assertNull(failed)
    }

    @Test fun theWritesGoStraightThroughAndAnswerOnTheMainThread() {
        val rig = Rig()
        var vehicle: VehicleUpdate.Outcome? = null
        var site: SiteUpdate.Outcome? = null
        rig.session.updateVehicle("car", listOf(VehicleUpdate.FieldChange(VehicleField.CAPACITY, 50.0, null))) { vehicle = it }
        rig.session.updateSite(SiteUpdate.priorityRequest(null, SiteUpdate.CAR_FIRST)) { site = it }
        assertEquals(listOf("vehicle:car", "site"), rig.transport.calls)
        assertNotNull(vehicle)
        assertNotNull(site)
    }
}
