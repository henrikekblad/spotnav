package se.sensnology.spotnav.ha.authority

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chart.ChartBand
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ConfirmedChart
import se.sensnology.spotnav.chart.LocalChart
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.ha.settings.HaSettingsEdit
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch

/** The screen's authority decisions, at the seam the screen actually uses. */
class AuthorityControllerTest {
    private val profileId = "local-a"
    private val profile = ChargerProfile(
        localId = profileId,
        displayName = "A",
        baseUrl = "https://spotnav.example.invalid:8123",
        webhookId = "webhook-secret-a",
        selectedVehicleId = null,
        targetSocPercent = 80
    )
    /**
     * The catalogue both the coordinator and the record adapter read (two areas, so a move is
     * testable).
     */
    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)
    private val presentation = HaPresentation.QUARTER_HOUR

    /** The widget's own record: NO1, ten amperes, twelve kWh -- deliberately not SE4. */
    private var local = WidgetSettings(
        area = "NO1", chargerProfileId = profileId, chargingAmps = 10, chargingKwh = 12
    )

    private val cache = ConfirmedSettingsStore(FakeKeyValueStore()) { }
    private var built: AuthorityController? = null

    private val written = SettingsFixtures.parsed(revision = 4, areaId = "SE4", amps = 16, requestedKwh = 20.5)

    /** A dashboard stating [record] as the charger's settings. */
    private fun supported(record: HaPlanningSettings): Dashboard =
        DashboardFixtures.dashboard { put("settings", HaSettingsCodec.encode(record)) }

    /** A dashboard on which Home Assistant states no settings record at all. */
    private fun withoutRecord(): Dashboard =
        DashboardFixtures.dashboard { put("settings", JSONObject.NULL) }

    /** The screen's own wiring, reproduced: */
    private fun controller(): AuthorityController {
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
            screenGeneration = 1,
            coordinator = coordinator,
            cache = cache,
            catalogue = { catalogue },
            presentation = { presentation }
        )
        built = controller
        controller.captureLocal(local.area, LocalPlanningInputs.ofOrNull(local))
        return controller
    }

    /** One dashboard answer, admitted and published exactly as the screen does it: */
    private fun publish(
        controller: AuthorityController,
        result: Result<Dashboard>
    ): AuthorityResolution = controller.onDashboardResult(controller.admitDashboard(), result)

    private fun resolved(controller: AuthorityController, dashboard: Dashboard): VisibleAuthority {
        val resolution = publish(controller, Result.success(dashboard))
        assertTrue("expected a state, got $resolution", resolution is AuthorityResolution.State)
        return (resolution as AuthorityResolution.State).authority
    }

    // Finding 1.

    @Test
    fun aFailedDashboardWithACachedRecordBecomesOfflineReadOnly() {
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(written))
        val controller = controller()

        val resolution = publish(controller, Result.failure(IllegalStateException("no route")))
        val state = (resolution as AuthorityResolution.State).authority

        assertEquals(VisibleAuthority.ReadOnlyOffline(written), state)
        assertTrue("an unreachable charger owns the plan", state.haOwnsPlanning)
        assertFalse("and nothing may be edited while it cannot be checked", state.writable)
        // The commit route is the rule that matters: nothing is written anywhere.
        assertEquals(CommitRoute.ReadOnly, controller.beginWrite(HaSettingsEdit.Amps(11)))
    }

    @Test
    fun aFailedDashboardWithoutACachedRecordSaysSoAndOffersNothing() {
        val controller = controller()

        val resolution = publish(controller, Result.failure(IllegalStateException("no route")))
        val state = (resolution as AuthorityResolution.State).authority

        assertEquals(VisibleAuthority.ReadOnlyOffline(null), state)
        assertFalse(state.writable)
        assertEquals(CommitRoute.ReadOnly, controller.beginWrite(HaSettingsEdit.Amps(11)))
    }

    @Test
    fun aLaterSuccessReplacesTheOfflineStateAndReEnablesTheEditors() {
        val controller = controller()
        val offline = publish(controller, Result.failure(IllegalStateException("no route")))
        assertEquals(
            VisibleAuthority.ReadOnlyOffline(null),
            (offline as AuthorityResolution.State).authority
        )

        val online = resolved(controller, supported(written))

        assertTrue("expected the record's own state, got $online", online is VisibleAuthority.AutoRemote)
        assertEquals(written, (online as VisibleAuthority.AutoRemote).remoteSettings)
        assertTrue(online.writable)
        val route = controller.beginWrite(HaSettingsEdit.Amps(11))
        assertTrue("an edit is possible again: $route", route is CommitRoute.Send)
        val send = route as CommitRoute.Send
        assertEquals("the displayed revision is the one edited", 4, send.expectedRevision)
        assertEquals("and the replacement is built from that record", 11, send.replacement.amps)
    }

    @Test
    fun aPairedWidgetBeforeItsFirstAnswerWritesNowhere() {
        val controller = controller()
        // No dashboard result has been handed in yet.
        assertEquals(CommitRoute.ReadOnly, controller.beginWrite(HaSettingsEdit.Amps(11)))
    }

    // Finding 2.

    @Test
    fun onlyAnUnpairedWidgetSavesLocally() {
        // No profile at all: the widget's own record is the only owner there is.
        val unpaired = AuthorityController(
            profileId = null,
            screenGeneration = 1,
            coordinator = null,
            cache = cache,
            catalogue = { catalogue },
            presentation = { presentation }
        )
        assertEquals(CommitRoute.LocalSave, unpaired.beginWrite(HaSettingsEdit.Amps(11)))

        // A paired one never does, whatever the dashboard says.
        val paired = controller()
        resolved(paired, supported(written))
        assertTrue(paired.beginWrite(HaSettingsEdit.Amps(11)) is CommitRoute.Send)
    }

    // A dashboard that states no record leaves the revision uncheckable, so the screen is as read-
    // only as when Home Assistant cannot be reached, and never becomes a local owner.
    @Test
    fun aDashboardWithoutARecordIsReadOnlyAndNeverALocalOwner() {
        val controller = controller()

        val state = resolved(controller, withoutRecord())

        assertEquals(VisibleAuthority.ReadOnlyOffline(null, answeredWithoutRecord = true), state)
        assertTrue(state.haOwnsPlanning)
        assertEquals(CommitRoute.ReadOnly, controller.beginWrite(HaSettingsEdit.Amps(11)))
    }

    // Home Assistant's own defaulted record at revision 0 is adopted as it stands.
    @Test
    fun aDefaultedRecordAtRevisionZeroIsAdoptedAndEditableAtItsOwnRevision() {
        val controller = controller()
        val defaulted = SettingsFixtures.parsed(revision = 0, areaId = null, phases = null, amps = null)

        val state = resolved(controller, supported(defaulted))

        assertEquals(defaulted, state.remoteSettings)
        val route = controller.beginWrite(HaSettingsEdit.Amps(11)) as CommitRoute.Send
        assertEquals(0, route.expectedRevision)
        assertEquals(11, route.replacement.amps)
        // The widget's own values are nowhere in it.
        assertNull(route.replacement.areaId)
    }

    @Test
    fun anOfflinePairedScreenRefusesEveryEditWithoutWritingAnything() {
        val controller = controller()
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(written))
        resolved(controller, supported(written))
        publish(controller, Result.failure(IllegalStateException("no route")))

        val edits = listOf(
            HaSettingsEdit.Amps(11),
            HaSettingsEdit.Energy(22.0),
            HaSettingsEdit.Phases(1),
            HaSettingsEdit.MaxPeriods(3),
            HaSettingsEdit.Departure(true, "07:30"),
            HaSettingsEdit.Area("SE4")
        )
        edits.forEach { edit ->
            assertEquals("offline: $edit", CommitRoute.ReadOnly, controller.beginWrite(edit))
        }
    }

    @Test
    fun aPairedAreaOrFiscalEditIsASettingsWriteRatherThanALocalSave() {
        val controller = controller()
        resolved(controller, supported(written))

        // The fields a paired record owns include the area and its fiscal overrides.
        val area = controller.beginWrite(HaSettingsEdit.Area("SE3")) as CommitRoute.Send
        assertEquals(4, area.expectedRevision)
        assertEquals("SE3", area.replacement.areaId)
        val fiscal = controller.beginWrite(
            HaSettingsEdit.Fiscal("SE4", HaAreaOverrideComponent.VAT, HaFiscalValue(true, 12.0))
        ) as CommitRoute.Send
        assertEquals(4, fiscal.expectedRevision)
    }

    // Finding 3.

    @Test
    fun changingTheAuthorityAreaInvalidatesThePricesBeforeTheNewAreaRenders() {
        val controller = controller()
        publish(controller, Result.failure(IllegalStateException("no route")))
        val no1 = controller.beginPriceLoad()
        assertEquals(PriceRequestKey(profileId, "NO1", 1), no1)
        assertNotNull(controller.onPriceLoaded(no1!!, PriceResult(today = emptyList(), tomorrow = emptyList(), fetchedAt = 0L)))

        // The record that arrives names another market.
        resolved(controller, supported(written))
        val se4 = controller.beginPriceLoad()
        assertEquals(PriceRequestKey(profileId, "SE4", 1), se4)
        assertNull("another area's prices must not survive the change", controller.currentPrices)

        assertNull(controller.onPriceLoaded(no1, PriceResult(today = emptyList(), tomorrow = emptyList(), fetchedAt = 0L)))
        assertNull(controller.currentPrices)
        assertNotNull(controller.onPriceLoaded(se4!!, PriceResult(today = emptyList(), tomorrow = emptyList(), fetchedAt = 0L)))
    }

    @Test
    fun aPlanMemoIsOnlyHandedOverForExactlyItsOwnSubject() {
        val controller = controller()
        val state = resolved(controller, supported(written.copy(requestedKwh = 4.0))) as VisibleAuthority.AutoRemote
        val subject = controller.beginPriceLoad()!!
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        val points = (listOf(0.5, 0.4, 0.3, 0.2, 0.9, 0.8) + List(90) { 0.9 }).mapIndexed { index, price ->
            PricePoint(start.plusMinutes(index * 15L), price)
        }
        val prices = PriceResult(points, emptyList(), 0)
        val plan = ChargingPlanner.calculate(prices, externalInputs(state), start)
        assertNotNull("the fixture has to produce a plan to hand over", plan)
        val revision = state.revision
        controller.onPriceLoaded(subject, prices)
        controller.rememberPlan(externalInputs(state), prices, plan, revision)

        assertNotNull(
            "the same subject still owns the memo",
            controller.handoverFor(externalInputs(state), prices, revision)
        )
        // A newer settings revision is not that subject either.
        assertNull(
            "another revision is not handed this memo",
            controller.handoverFor(externalInputs(state), prices, revision + 1)
        )

        // A late answer for another market is not this memo's subject.
        val other = PriceRequestKey(profileId, "SE3", 1)
        assertNull(controller.onPriceLoaded(other, prices))
        controller.rememberPlan(externalInputs(state), prices, null, revision)
        assertNull("another subject is not handed this memo", controller.handoverFor(externalInputs(state), prices, revision))
    }

    // Finding 8.

    @Test
    fun anOlderWriteAnswerCannotLowerTheVisibleRevision() {
        val controller = controller()
        resolved(controller, supported(written))
        val first = controller.beginWrite(HaSettingsEdit.Amps(11)) as CommitRoute.Send
        val second = controller.beginWrite(HaSettingsEdit.Amps(12)) as CommitRoute.Send
        assertTrue("each send reserves its own operation", second.operation > first.operation)

        val newer = written.copy(revision = 6, amps = 12)
        val applied = controller.onWriteAnswer(
            WriteSubject(profileId, second.operation, second.expectedRevision),
            SettingsUpdate.Outcome.Updated(newer)
        ) as WriteOutcome.Applied
        assertEquals(6, (applied.authority as VisibleAuthority.AutoRemote).remoteSettings!!.revision)

        val stale = controller.onWriteAnswer(
            WriteSubject(profileId, first.operation, first.expectedRevision),
            SettingsUpdate.Outcome.Updated(written.copy(revision = 5, amps = 11))
        )
        assertEquals("a superseded answer changes nothing", WriteOutcome.Stale, stale)
        assertEquals(
            "the visible revision never decreases",
            6,
            (controller.authority as VisibleAuthority.AutoRemote).remoteSettings!!.revision
        )
    }

    @Test
    fun theAnswerIsFoldedFirstAndTheCacheDecidesWhatIsRendered() {
        // The cache holds a newer record than the answer about to arrive.
        val newer = written.copy(revision = 6, amps = 12)
        cache.recordOutcome(profileId, SettingsUpdate.Outcome.Updated(newer))
        val controller = controller()
        resolved(controller, supported(newer))
        val send = controller.beginWrite(HaSettingsEdit.Amps(11)) as CommitRoute.Send

        val conflict = controller.onWriteAnswer(
            WriteSubject(profileId, send.operation, send.expectedRevision),
            SettingsUpdate.Outcome.Conflict(written.copy(revision = 3))
        ) as WriteOutcome.Applied

        assertEquals(
            "the record the cache says stands is the one rendered",
            6,
            (conflict.authority as VisibleAuthority.AutoRemote).remoteSettings!!.revision
        )
    }

    // Finding 9.

    @Test
    fun aConfirmedDecimalIsReadExactlyAndSaidToBeBeyondTheControl() {
        val energy = PairedControlRanges.energy(20.5)
        assertEquals("20.5", energy.exact)
        assertFalse("a whole-kWh slider cannot hold 20.5 exactly", energy.representable)

        val whole = PairedControlRanges.energy(20.0)
        assertEquals("20.0", whole.exact)
        assertTrue(whole.representable)

        val target = PairedControlRanges.target(80.5)
        assertEquals("80.5", target.exact)
        assertFalse(target.representable)
        assertTrue(PairedControlRanges.target(80.0).representable)
    }

    @Test
    fun theControlsThatCanHoldTheirStepsAreNotMarked() {
        assertTrue(PairedControlRanges.amps(16).representable)
        assertFalse("beyond the slider's range", PairedControlRanges.amps(32).representable)
        assertTrue(PairedControlRanges.periods(4).representable)
        assertFalse(PairedControlRanges.periods(9).representable)
    }

    // The memo's
    // ownership.

    /** The calculation inputs the compatibility record's own adaptation produces. */
    private fun externalInputs(state: VisibleAuthority.AutoRemote): PlanningInputs =
        SettingsFixtures.localInputs(state.settings, catalogue, presentation)

    /** One External state with its accepted prices, and the plan the fixture calculates for them. */
    private fun externalWithPrices(
        controller: AuthorityController
    ): Triple<VisibleAuthority.AutoRemote, PriceResult, ChargingPlan> {
        val state = resolved(controller, supported(written.copy(requestedKwh = 4.0))) as VisibleAuthority.AutoRemote
        val subject = controller.beginPriceLoad()!!
        val start = OffsetDateTime.parse("2026-09-12T18:00:00+02:00")
        val points = (listOf(0.5, 0.4, 0.3, 0.2, 0.9, 0.8) + List(90) { 0.9 }).mapIndexed { index, price ->
            PricePoint(start.plusMinutes(index * 15L), price)
        }
        val prices = PriceResult(points, emptyList(), 0)
        controller.onPriceLoaded(subject, prices)
        val plan = ChargingPlanner.calculate(prices, externalInputs(state), start)
        assertNotNull("the fixture has to produce a plan to hand over", plan)
        return Triple(state, prices, plan!!)
    }

    @Test
    fun anExternalPassRemembersItsPlanAndAnAutoPassClearsIt() {
        val controller = controller()
        val (state, prices, plan) = externalWithPrices(controller)
        val revision = state.revision

        controller.planMemoFor(PlanSource.AndroidCalculates(externalInputs(state), revision), externalInputs(state), prices, plan)
        assertEquals("the pass that calculated the plan remembers it", plan, controller.handoverFor(externalInputs(state), prices, revision))

        // The same pass rule runs with a remote-owned source, and the memo must be *gone* --
        // asserted with the very arguments that were just remembered, which can only be true if the
        // remember path was not taken at all.
        controller.planMemoFor(PlanSource.RemoteOwned(remotePlan = null, revision = revision), externalInputs(state), prices, plan)
        assertNull("an Auto pass clears the Android memo", controller.handoverFor(externalInputs(state), prices, revision))

        // And back again: switching Auto -> External stores a fresh memo normally.
        val next = plan.copy(energyKwh = plan.energyKwh + 1.0)
        controller.planMemoFor(PlanSource.AndroidCalculates(externalInputs(state), revision), externalInputs(state), prices, next)
        assertEquals("an External pass remembers again", next, controller.handoverFor(externalInputs(state), prices, revision))
    }

    @Test
    fun anAutoPassIsIdempotentAndLeavesTheAcceptedPricesAlone() {
        val controller = controller()
        val (state, prices, plan) = externalWithPrices(controller)
        controller.planMemoFor(PlanSource.AndroidCalculates(externalInputs(state), state.revision), externalInputs(state), prices, plan)
        assertNotNull(controller.handoverFor(externalInputs(state), prices, state.revision))

        // What an Auto pass actually hands over.
        repeat(2) { controller.planMemoFor(PlanSource.RemoteOwned(null, state.revision), null, null, null) }
        assertNull("nothing is left to hand over", controller.handoverFor(externalInputs(state), prices, state.revision))
        assertEquals("clearing the memo is not clearing the market", prices, controller.currentPrices)
    }

    // A gesture that
    // races the transition to offline writes nothing at all.

    @Test
    fun aGestureRacingTheTransitionToOfflineIsRefusedAndRestoresTheConfirmedValues() {
        val controller = controller()
        // The screen had a confirmed record, and then the dashboard fetch failed.
        val resolution = publish(controller, Result.failure(IllegalStateException("no network")))
        assertTrue("a failed dashboard resolves to a state, got $resolution", resolution is AuthorityResolution.State)
        val state = (resolution as AuthorityResolution.State).authority
        assertTrue("expected the offline state, got $state", state is VisibleAuthority.ReadOnlyOffline)
        assertFalse("nothing paired may be touched there", state.pairedControlsEnabled)

        // nothing is reserved, so nothing can be sent, and the screen re-renders from the confirmed
        // record (CommitRoute.ReadOnly -> readOnlyNote in ChargingScreen).
        for (edit in listOf(HaSettingsEdit.Amps(11), HaSettingsEdit.Energy(30.0))) {
            assertEquals("$edit writes nowhere while the revision cannot be checked", CommitRoute.ReadOnly, controller.beginWrite(edit))
        }
    }

    @Test
    fun aPairedEditBeforeTheFirstAnswerIsRefusedToo() {
        // The other half of the same rule.
        val controller = controller()
        assertEquals(CommitRoute.ReadOnly, controller.beginWrite(HaSettingsEdit.Amps(11)))
        assertNull("and no answer is invented for it", controller.authority)
    }

    // The retained
    // chart's publication and lookup are one linearization point each.

    /** The same record moved to another area, with its own figures stated so it adapts cleanly. */
    private fun movedRecord(): HaPlanningSettings = SettingsFixtures.parsed(
        revision = written.revision + 1,
        areaId = "NO1",
        amps = 16,
        requestedKwh = 20.5,
        overrides = JSONArray().put(
            SettingsFixtures.override(
                areaId = "NO1",
                vat = SettingsFixtures.fiscal(enabled = true, value = 25.0),
                tax = SettingsFixtures.fiscal(enabled = true, value = 7.13),
                transfer = SettingsFixtures.fiscal(enabled = true, value = 5.0)
            )
        )
    )

    /** One accepted price answer for the fixture's own area. */
    private fun acceptedPrices(): PriceResult = PriceResult(
        listOf(PricePoint(OffsetDateTime.parse("2026-09-12T18:00:00+02:00"), 0.5)),
        emptyList(),
        0
    )

    /** The chart a confirmed pass drew: the record's own market, one band, no footer. */
    private fun passChart(state: VisibleAuthority.AutoRemote) = LocalChart(
        market = ChartMarket.of(externalInputs(state)),
        bands = listOf(ChartBand(LocalDate.of(2026, 9, 12), 60f, 120f)),
        footer = null
    )

    /** The paired-offline state after the connection is gone. */
    private fun offline(controller: AuthorityController): VisibleAuthority.ReadOnlyOffline =
        (publish(controller, Result.failure(IllegalStateException("no network"))) as AuthorityResolution.State)
            .authority as VisibleAuthority.ReadOnlyOffline

    @Test
    fun anObsoleteRenderCannotRestoreAClearedSnapshot() {
        val controller = controller()
        val state = resolved(controller, supported(written)) as VisibleAuthority.AutoRemote
        val subject = controller.beginPriceLoad()!!
        val prices = acceptedPrices()
        controller.onPriceLoaded(subject, prices)
        val chart = passChart(state)

        // The confirmed pass retains its chart, and the offline state gets exactly that back.
        assertNotNull(
            "a confirmed coherent pass retains its chart",
            controller.publishChart(controller.candidateChart(state, prices, chart), state, prices)
        )
        val offline = offline(controller)
        val record = offline.lastConfirmed!!
        assertEquals("the retained chart is shown while offline", chart.bands, controller.confirmedChartFor(record)!!.bands)

        // The race.
        val obsolete = controller.candidateChart(state, prices, chart)
        assertNotNull("the obsolete pass did build a candidate", obsolete)
        resolved(controller, supported(movedRecord())) as VisibleAuthority.AutoRemote
        controller.beginPriceLoad()
        assertNull(
            "an obsolete render must not write a cleared snapshot back",
            controller.publishChart(obsolete, state, prices)
        )
        assertNull("and nothing is retained for the record it was about", controller.confirmedChartFor(record))
    }

    @Test
    fun aPriceResultThatIsNotTheAcceptedOneCannotBeRetained() {
        val controller = controller()
        val state = resolved(controller, supported(written)) as VisibleAuthority.AutoRemote
        controller.beginPriceLoad()!!
        val accepted = acceptedPrices()
        controller.onPriceLoaded(controller.beginPriceLoad()!!, accepted)
        val chart = passChart(state)

        // An equal-valued copy is a *different* answer.
        assertEquals("the copy really is equal", accepted, accepted.copy())
        assertNull(
            "a copy of the accepted result cannot be retained",
            controller.publishChart(controller.candidateChart(state, accepted.copy(), chart), state, accepted)
        )
        // The accepted result itself is.
        assertNotNull(
            "while the accepted result is",
            controller.publishChart(controller.candidateChart(state, accepted, chart), state, accepted)
        )
        assertNull(
            "a pass that drew with another answer retains nothing",
            controller.publishChart(controller.candidateChart(state, accepted, chart), state, accepted.copy())
        )
    }

    @Test
    fun aLookupWhileTheAuthorityIsNoLongerOfflineReturnsNothing() {
        val controller = controller()
        val state = resolved(controller, supported(written)) as VisibleAuthority.AutoRemote
        val subject = controller.beginPriceLoad()!!
        val prices = acceptedPrices()
        controller.onPriceLoaded(subject, prices)
        controller.publishChart(controller.candidateChart(state, prices, passChart(state)), state, prices)
        val record = offline(controller).lastConfirmed!!
        assertNotNull("the offline state shows it", controller.confirmedChartFor(record))

        // The connection comes back.
        resolved(controller, supported(written))
        assertNull(
            "a confirmed state draws its own chart, never the retained one",
            controller.confirmedChartFor(record)
        )
        // A record the offline state is not about is refused as well.
        val offline = offline(controller)
        assertNull(
            "nor does another record get this chart",
            controller.confirmedChartFor(written.copy(revision = written.revision + 1))
        )
        assertNotNull("while its own record still does", controller.confirmedChartFor(offline.lastConfirmed!!))
    }

    @Test
    fun lookupsRacingASubjectAndAuthorityChangeAreNeverAMixedDecision() {
        val controller = controller()
        val state = resolved(controller, supported(written)) as VisibleAuthority.AutoRemote
        val subject = controller.beginPriceLoad()!!
        val prices = acceptedPrices()
        controller.onPriceLoaded(subject, prices)
        val retained = controller.publishChart(
            controller.candidateChart(state, prices, passChart(state)), state, prices
        )!!
        val record = offline(controller).lastConfirmed!!

        // One reader spinning on the lookup while the screen is taken off the offline state and its
        // subject is moved.
        val start = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<ConfirmedChart?>())
        val reader = Thread {
            start.await()
            repeat(2000) { seen.add(controller.confirmedChartFor(record)) }
            finished.countDown()
        }
        reader.start()
        start.countDown()
        resolved(controller, supported(movedRecord()))
        controller.beginPriceLoad()
        finished.await()
        reader.join()

        for (value in seen) {
            // Nothing at all is a decision too.
            if (value != null) {
                assertEquals("a returned chart is exactly the retained one, whole", retained, value)
            }
        }
        assertTrue("the reader really did run", seen.size == 2000)
        assertNull("and after the change nothing at all is shown", controller.confirmedChartFor(record))
    }
}
