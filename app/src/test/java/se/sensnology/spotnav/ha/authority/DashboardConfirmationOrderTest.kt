package se.sensnology.spotnav.ha.authority

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.ui.Screen
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime

/** The dashboard-confirmation ordering, proved as a sequence rather than assumed. */
class DashboardConfirmationOrderTest {
    private val profileId = "local-a"
    private val profile = ChargerProfile(
        localId = profileId,
        displayName = "A",
        baseUrl = "https://spotnav.example.invalid:8123",
        webhookId = "webhook-secret-a",
        selectedVehicleId = null,
        targetSocPercent = 80
    )
    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)

    /** The widget's own record: */
    private val local = WidgetSettings(
        area = "NO1", chargerProfileId = profileId, chargingAmps = 10, chargingKwh = 12.0
    )

    private val cache = ConfirmedSettingsStore(FakeKeyValueStore()) { }
    private var built: AuthorityController? = null

    private fun record(revision: Int, requestedKwh: Double): HaPlanningSettings =
        SettingsFixtures.parsed(
            revision = revision,
            areaId = "SE4",
            amps = 16,
            requestedKwh = requestedKwh
        )

    private fun period(from: String, to: String): ChargingPeriod =
        ChargingPeriod(OffsetDateTime.parse(from), OffsetDateTime.parse(to))

    /** One dashboard: a canonical record, and the installed schedule that same dashboard reports. */
    private fun status(
        record: HaPlanningSettings,
        installed: Boolean = true,
        periods: List<ChargingPeriod> = listOf(period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z"))
    ): Dashboard = DashboardFixtures.parse(
        DashboardFixtures.json().apply {
            put("settings", HaSettingsCodec.encode(record))
            getJSONObject("live").put("schedule_active", installed)
            getJSONObject("plan").put(
                "installed",
                JSONObject().apply {
                    put("active_period_index", JSONObject.NULL)
                    put("amps", 16)
                    put("identity", "x")
                    put("phases", 1)
                    put("power_kw", JSONObject.NULL)
                    put("periods", JSONArray().apply {
                        periods.forEach { charged ->
                            put(JSONObject().put("start", charged.start.toString()).put("end", charged.end.toString()))
                        }
                    })
                }
            )
        }
    )

    /** The screen's own wiring, reproduced: */
    private fun controller(generation: Int): AuthorityController {
        val coordinator = SettingsAuthorityCoordinator(
            profiles = { listOf(profile) },
            catalogue = { catalogue },
            fetchDashboard = { captured ->
                if (captured.localId == profileId) {
                    built?.capturedResult ?: Result.failure(IllegalStateException("no dashboard yet"))
                } else {
                    Result.failure(IllegalStateException("another charger"))
                }
            },
            cache = cache
        )
        val controller = AuthorityController(
            profileId = profileId,
            screenGeneration = generation,
            coordinator = coordinator,
            cache = cache,
            catalogue = { catalogue }
        )
        built = controller
        controller.captureLocal(local.area, LocalPlanningInputs.ofOrNull(local))
        return controller
    }

    /** One screen: */
    private inner class Screen(generation: Int = 1) {
        val controller = controller(generation)

        /** Every read this screen has started, in order: the newest is the one still expected. */
        val reads = mutableListOf<DashboardAdmission?>()
        val readCount: Int get() = reads.size

        /** The screen's one fetch, admitted where the request goes out. */
        fun startRead(): DashboardAdmission? {
            val admission = controller.admitDashboard()
            reads += admission
            return admission
        }

        fun answer(admission: DashboardAdmission?, result: Result<Dashboard>) =
            controller.onDashboardResult(admission, result)

        /** The screen's rule, verbatim: */
        fun applyWrite(operation: Long, revision: Int, outcome: SettingsUpdate.Outcome): WriteOutcome {
            val written = controller.onWriteAnswer(WriteSubject(profileId, operation, revision), outcome)
            if (written !is WriteOutcome.Applied) return written
            if (AuthorityRefresh.settingsChangeNeedsDashboardReload(outcome)) startRead()
            return written
        }

        fun installedPlan(): RemotePlan? =
            (controller.authority as? VisibleAuthority.AutoRemote)?.remotePlan

        fun revision(): Int? = controller.authority?.remoteSettings?.revision
    }

    /** The starting point: a confirmed Auto record at revision 4, with one installed plan. */
    private fun confirmedScreen(generation: Int = 1): Screen {
        val screen = Screen(generation)
        screen.answer(screen.startRead(), Result.success(status(record(4, 20.0))))
        assertTrue("expected a confirmed Auto state", screen.controller.authority is VisibleAuthority.AutoRemote)
        return screen
    }

    private fun instantsOf(periods: List<ChargingPeriod>) =
        periods.map { it.start.toInstant() to it.end.toInstant() }

    private fun installedInstants(plan: RemotePlan?) = instantsOf(plan!!.periods)


    @Test
    fun anAppliedCurrentWriteStartsExactlyOneConfirmationRead() {
        val screen = confirmedScreen()
        assertEquals("the screen's own first read", 1, screen.readCount)

        val route = screen.controller.beginWrite(HaSettingsEdit.Energy(25.0)) as CommitRoute.Send
        val written = screen.applyWrite(
            route.operation, route.expectedRevision, SettingsUpdate.Outcome.Updated(record(5, 25.0))
        )

        assertTrue(written is WriteOutcome.Applied)
        assertEquals("exactly one confirming read", 2, screen.readCount)
        assertEquals(5, screen.revision())
    }


    @Test
    fun refusedConflictedUnavailableAndSupersededWritesStartNoConfirmationRead() {
        val screen = confirmedScreen()

        val refused = screen.controller.beginWrite(HaSettingsEdit.Energy(25.0)) as CommitRoute.Send
        screen.applyWrite(
            refused.operation, refused.expectedRevision,
            SettingsUpdate.Outcome.Invalid("spotnav_settings_refused_energy", record(4, 20.0))
        )

        val conflicted = screen.controller.beginWrite(HaSettingsEdit.Energy(26.0)) as CommitRoute.Send
        screen.applyWrite(
            conflicted.operation, conflicted.expectedRevision, SettingsUpdate.Outcome.Conflict(record(6, 26.0))
        )

        // A superseded write: the answer belongs to an operation the screen has moved past.
        val first = screen.controller.beginWrite(HaSettingsEdit.Energy(28.0)) as CommitRoute.Send
        screen.controller.beginWrite(HaSettingsEdit.Energy(29.0)) as CommitRoute.Send
        val superseded = screen.applyWrite(
            first.operation, first.expectedRevision, SettingsUpdate.Outcome.Updated(record(9, 28.0))
        )
        assertTrue("an older operation's answer is stale", superseded is WriteOutcome.Stale)

        // And an unreachable charger.
        val unreachable = screen.controller.beginWrite(HaSettingsEdit.Energy(27.0)) as CommitRoute.Send
        assertTrue(
            screen.applyWrite(unreachable.operation, unreachable.expectedRevision, SettingsUpdate.Outcome.Unavailable)
                is WriteOutcome.Reported
        )
        assertEquals(CommitRoute.ReadOnly, screen.controller.beginWrite(HaSettingsEdit.Energy(31.0)))

        assertEquals("no confirming read for any of them", 1, screen.readCount)
    }


    @Test
    fun anOlderReadReturningAfterTheConfirmingOneCannotRestoreTheFormerPlan() {
        val screen = confirmedScreen()
        val formerPlan = listOf(period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z"))
        val formerStatus = status(record(4, 20.0), periods = formerPlan)
        assertEquals("the screen starts from the former plan", instantsOf(formerPlan), installedInstants(screen.installedPlan()))

        // 1.
        val readA = screen.startRead()
        // 2. a settings write commits revision 5, and 3. its confirming read goes out after it.
        val route = screen.controller.beginWrite(HaSettingsEdit.Energy(25.0)) as CommitRoute.Send
        screen.applyWrite(route.operation, route.expectedRevision, SettingsUpdate.Outcome.Updated(record(5, 25.0)))
        val readB = screen.reads.last()!!
        assertTrue("a new request, not the one in flight", readB != readA)

        // ...
        val installedNow = listOf(period("2026-09-28T22:00:00Z", "2026-09-28T23:45:00Z"))
        val currentStatus = status(record(5, 25.0), periods = installedNow)
        screen.answer(readB, Result.success(currentStatus))
        val afterConfirmation = screen.controller.authority
        assertEquals(5, screen.revision())
        assertEquals(instantsOf(installedNow), installedInstants(screen.installedPlan()))

        // 4.
        val late = screen.answer(readA, Result.success(formerStatus))

        assertTrue("an answer to a superseded request is inert", late is AuthorityResolution.Stale)
        assertEquals("the state on screen did not move", afterConfirmation, screen.controller.authority)
        assertEquals(5, screen.revision())
        assertEquals("the former plan was not restored", instantsOf(installedNow), installedInstants(screen.installedPlan()))
        assertEquals("nor was the dashboard the card's facts are read from", currentStatus, screen.controller.dashboard)
    }

    @Test
    fun anOlderReadCannotRestoreTheTemporaryNoInstalledPlanPresentation() {
        val screen = confirmedScreen()
        // A write commits, and its own confirmation reports *no* installed schedule.
        val readA = screen.startRead()
        val route = screen.controller.beginWrite(HaSettingsEdit.Energy(25.0)) as CommitRoute.Send
        screen.applyWrite(route.operation, route.expectedRevision, SettingsUpdate.Outcome.Updated(record(5, 25.0)))
        val readB = screen.reads.last()!!
        screen.answer(readB, Result.success(status(record(5, 25.0), installed = false, periods = emptyList())))
        assertTrue("no installed schedule, honestly", screen.installedPlan()!!.periods.isEmpty())

        val late = screen.answer(readA, Result.success(status(record(4, 20.0))))

        assertTrue(late is AuthorityResolution.Stale)
        assertTrue("and the former plan did not come back with it", screen.installedPlan()!!.periods.isEmpty())
        assertEquals(5, screen.revision())
    }

    // 23.

    @Test
    fun aNewerAdmittedWriteMakesAnInFlightConfirmationInert() {
        val screen = confirmedScreen()
        val readA = screen.startRead()

        // A newer write is admitted while read A is in flight: A describes the record *before* it.
        val route = screen.controller.beginWrite(HaSettingsEdit.Energy(30.0)) as CommitRoute.Send
        val late = screen.answer(readA, Result.success(status(record(4, 20.0))))

        assertTrue(late is AuthorityResolution.Stale)
        assertEquals("nothing was repainted", 4, screen.revision())
        assertEquals("and the stale answer started no read of its own", 2, screen.readCount)

        // The write's own answer and its confirming read still land.
        screen.applyWrite(route.operation, route.expectedRevision, SettingsUpdate.Outcome.Updated(record(5, 30.0)))
        val readB = screen.reads.last()!!
        screen.answer(readB, Result.success(status(record(5, 30.0))))
        assertEquals(5, screen.revision())
    }

    @Test
    fun anAnswerForAnotherScreenGenerationOrChargerIsRefused() {
        val first = confirmedScreen()
        val inFlight = first.startRead()

        // The screen is rebuilt: a new controller, a new generation, and no state of its own yet.
        val rebuilt = Screen(generation = 2)
        assertTrue(
            "an answer admitted by the screen that is gone",
            rebuilt.answer(inFlight, Result.success(status(record(5, 30.0)))) is AuthorityResolution.Stale
        )
        assertTrue("and it resolved nothing", rebuilt.controller.authority == null)
    }

    @Test
    fun anAnswerAdmittedForAnotherBindingIsInertEvenWhenItsNumbersMatch() {
        // A screen for this charger, waiting on its own first read -- so its admission numbers are
        // a different binding's could be, exactly.
        val screen = Screen(generation = 1)
        val ownRead = screen.startRead()!!
        val anotherBinding = DashboardAdmission(
            profileId = "local-b",
            screenGeneration = ownRead.screenGeneration,
            operation = ownRead.operation
        )

        val refused = screen.answer(anotherBinding, Result.success(status(record(4, 20.0))))

        assertTrue("another charger's answer is not this screen's", refused is AuthorityResolution.Stale)
        assertTrue("and nothing was resolved for it", screen.controller.authority == null)
        assertEquals("no state, no revision", null, screen.revision())
    }
}
