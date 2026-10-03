package se.sensnology.spotnav.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.chart.ChartBand
import se.sensnology.spotnav.chart.ChartOverlay
import se.sensnology.spotnav.chart.LocalCharts
import se.sensnology.spotnav.ha.authority.AuthorityAvailability
import se.sensnology.spotnav.ha.authority.HaPlanningInputs
import se.sensnology.spotnav.ha.authority.PriceRequestKey
import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** The widget's plan authority, proved at the boundary the provider actually consults. */
class WidgetPlanPassTest {
    private val charger = "local-a"
    private val otherCharger = "local-b"
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val today = LocalDate.of(2026, 9, 27)

    /** The widget's own record: another market, a large request, two periods -- a plan of its own. */
    private val settings = WidgetSettings(
        area = "NO1",
        chargerProfileId = charger,
        chargingAmps = 10,
        chargingKwh = 20.0,
        maxChargingPeriods = 2,
        showChargingPlan = true
    )

    @Before fun seedTheCatalogue() {
        PriceMarkets.replace(listOf(RelayFixtures.se4, RelayFixtures.no1))
    }

    @After fun clearTheCatalogue() {
        PriceMarkets.replace(emptyList())
    }

    private fun period(from: String, to: String): ChargingPeriod =
        ChargingPeriod(OffsetDateTime.parse(from), OffsetDateTime.parse(to))

    private fun installed(): ChargingPeriod = period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z")

    private fun day(date: LocalDate): List<PricePoint> {
        val midnight = date.atStartOfDay(stockholm).toOffsetDateTime()
        return (0 until 96).map { index -> PricePoint(midnight.plusMinutes(index * 15L), 0.4 + (index % 4) * 0.3) }
    }

    /** The documents the snapshots below were drawn against: this day and the next, in the market's clock. */
    private fun documents(): PriceResult = PriceResult(day(today), day(today.plusDays(1)), 0L)

    /** Prices for another day's documents: the same market, a different document set. */
    private fun otherDocuments(): PriceResult =
        PriceResult(day(today.plusDays(2)), day(today.plusDays(3)), 0L)

    private fun snapshot(
        profileId: String = charger,
        revision: Int = 3,
        areaId: String = "SE4",
        installed: Boolean = true,
        periods: List<ChargingPeriod> = listOf(installed()),
        capturedAt: Long = 1_000L
    ): WidgetPlanSnapshot = WidgetPlanSnapshot(
        profileId = profileId,
        revision = revision,
        areaId = areaId,
        zoneId = stockholm,
        intervalMinutes = 15,
        vat = FiscalInput.OFF,
        tax = FiscalInput.OFF,
        transfer = FiscalInput.OFF,
        installed = installed,
        periods = periods,
        amps = 16,
        chargingEnabled = false,
        priceIdentity = PriceDocumentIdentity.of(areaId, documents()),
        capturedAt = capturedAt
    )

    private fun pass(
        snapshot: WidgetPlanSnapshot?,
        prices: PriceResult? = documents(),
        chargerProfileId: String? = charger,
        chargerKnown: Boolean = true,
        settings: WidgetSettings = this.settings
    ): WidgetPlanPass = WidgetPlanDecision.pass(chargerProfileId, chargerKnown, settings, snapshot, prices)

    private fun remote(pass: WidgetPlanPass): WidgetPlanPass.Remote {
        assertTrue("expected a paired pass, got $pass", pass is WidgetPlanPass.Remote)
        return pass as WidgetPlanPass.Remote
    }

    /** Four days of quarter hours from now, in the market's clock: what a local plan needs to exist. */
    private fun pricesAroundNow(): PriceResult {
        val from = OffsetDateTime.now(stockholm).withMinute(0).withSecond(0).withNano(0)
        return PriceResult(
            (0 until 96 * 4).map { index -> PricePoint(from.plusMinutes(index * 15L), 0.4 + (index % 8) * 0.25) },
            emptyList(),
            0L
        )
    }


    @Test
    fun aPairedWidgetDrawsTheConfirmedScheduleAndNeverTheLocalPlanThoseSettingsCouldProduce() {
        val prices = pricesAroundNow()
        // The very settings that produce a local plan, priced in the widget's own market.
        val local = WidgetPlanDecision.pass(null, chargerKnown = false, settings = settings, snapshot = null, prices = prices)
        val localInputs = (local as WidgetPlanPass.Local).inputs!!
        val localChart = LocalCharts.of(localInputs, prices, settings.showChargingPlan)
        assertTrue("these settings can produce a local plan", localChart.bands.isNotEmpty())
        assertEquals("and it is the widget's own market", "NO1", localChart.market.areaId)

        val paired = remote(pass(snapshot = snapshot()))
        assertEquals("the snapshot's own market, not the widget's stored area", "SE4", paired.market?.areaId)
        assertEquals(
            "exactly the installed schedule's one band",
            ChartOverlay.installed(snapshot().remotePlan).bands(documents(), stockholm),
            paired.bands
        )
        assertNotEquals("and never the local plan's geometry", localChart.bands, paired.bands)
        assertEquals(snapshot(), paired.snapshot)
        assertNull(paired.refused)
    }


    @Test
    fun aPairedWidgetWithNoSnapshotShadesNothingAndNamesNoMarket() {
        val paired = remote(pass(snapshot = null))

        assertTrue("zero bands, not a plan", paired.bands.isEmpty())
        assertNull("and no snapshot claimed", paired.snapshot)
        assertEquals(WidgetPlanPass.Remote.Reason.NO_SNAPSHOT, paired.refused)
        assertNull("and no market at all, neither the snapshot's nor the widget's own", paired.market)
        assertTrue("nothing was retained, so nothing is marked stale", !paired.stale)
    }

    @Test
    fun oneInstalledPeriodDrawsExactlyOneNormalizedBand() {
        val paired = remote(pass(snapshot = snapshot()))

        assertEquals(listOf(ChartBand(today, 12f * 60, 15.75f * 60)), paired.bands)
        assertEquals(1, paired.bands.size)
    }

    @Test
    fun twoDisjointInstalledPeriodsDrawExactlyTwoBands() {
        val paired = remote(
            pass(
                snapshot = snapshot(
                    periods = listOf(
                        period("2026-09-27T10:00:00Z", "2026-09-27T11:00:00Z"),
                        period("2026-09-27T18:00:00Z", "2026-09-27T19:30:00Z")
                    )
                )
            )
        )

        assertEquals(
            listOf(ChartBand(today, 12f * 60, 13f * 60), ChartBand(today, 20f * 60, 21.5f * 60)),
            paired.bands
        )
    }

    @Test
    fun duplicateAndTouchingInstalledPeriodsUseTheExistingUnionGeometry() {
        val touching = remote(
            pass(
                snapshot = snapshot(
                    periods = listOf(
                        period("2026-09-27T10:00:00Z", "2026-09-27T11:00:00Z"),
                        period("2026-09-27T11:00:00Z", "2026-09-27T12:00:00Z")
                    )
                )
            )
        )
        assertEquals("two touching periods are one shaded stretch", 1, touching.bands.size)
        assertEquals(ChartBand(today, 12f * 60, 14f * 60), touching.bands.single())

        val duplicated = remote(pass(snapshot = snapshot(periods = listOf(installed(), installed()))))
        assertEquals("a duplicated period is still one stretch", 1, duplicated.bands.size)
        assertEquals(ChartBand(today, 12f * 60, 15.75f * 60), duplicated.bands.single())
    }

    @Test
    fun aScheduleHomeAssistantDoesNotStateAsInstalledShadesNothing() {
        val notInstalled = remote(pass(snapshot = snapshot(installed = false)))
        assertTrue("periods without `installed` are a stale payload", notInstalled.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.NOT_INSTALLED, notInstalled.refused)

        val emptySchedule = remote(pass(snapshot = snapshot(installed = true, periods = emptyList())))
        assertTrue(emptySchedule.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.NOT_INSTALLED, emptySchedule.refused)
        assertEquals("the market is still Home Assistant's", "SE4", emptySchedule.market?.areaId)
    }

    @Test
    fun theWidgetsOwnDisplayChoiceStillGovernsWhetherAnyBandsAreDrawn() {
        val hidden = remote(pass(snapshot = snapshot(), settings = settings.copy(showChargingPlan = false)))

        assertTrue("the person asked for no plan", hidden.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.NOT_SHOWN, hidden.refused)
        assertEquals("and the market is still the snapshot's", "SE4", hidden.market?.areaId)

        val unpairedHidden = WidgetPlanDecision.pass(
            null, chargerKnown = false, settings = settings.copy(showChargingPlan = false),
            snapshot = null, prices = pricesAroundNow()
        )
        val chart = LocalCharts.of((unpairedHidden as WidgetPlanPass.Local).inputs!!, pricesAroundNow(), false)
        assertTrue("and an unpaired widget honours it the same way", chart.bands.isEmpty())
    }


    @Test
    fun aSnapshotForAnotherChargerIsRefusedAndNamesNoMarketOfItsOwn() {
        val paired = remote(pass(snapshot = snapshot(profileId = otherCharger)))

        assertTrue("never another charger's plan", paired.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.OTHER_CHARGER, paired.refused)
        assertNull("and it is not retained as this widget's answer", paired.snapshot)
        assertNull("so there is no market here either -- and never the widget's stored area", paired.market)
    }

    @Test
    fun aBindingWhoseChargerIsGoneShadesNothingEvenWithASnapshotStored() {
        val paired = remote(pass(snapshot = snapshot(), chargerKnown = false))

        assertTrue("a removed charger is not a licence to plan locally", paired.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.CHARGER_GONE, paired.refused)
        assertNull(paired.snapshot)
        assertNull(paired.market)
        assertTrue("and nothing here is a *stale* plan of this widget's", !paired.stale)
    }

    @Test
    fun pricesThatAreNotTheDocumentsTheSnapshotWasDrawnAgainstRefuseReuse() {
        val moved = remote(pass(snapshot = snapshot(), prices = otherDocuments()))
        assertTrue("bands are geometry over one document set", moved.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.PRICES_MOVED, moved.refused)
        assertEquals("the market is still Home Assistant's", "SE4", moved.market?.areaId)

        val absent = remote(pass(snapshot = snapshot(), prices = null))
        assertTrue(absent.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.NO_PRICE_ANSWER, absent.refused)
    }


    @Test
    fun aSnapshotThatSurvivesAFailedRefreshIsKeptValueForValueAndMarkedStale() {
        val confirmed = snapshot()

        val moved = remote(pass(snapshot = confirmed, prices = otherDocuments()))
        assertEquals("the confirmed answer is kept whole", confirmed, moved.snapshot)
        assertTrue("and it is marked stale", moved.stale)
        assertTrue("with no band drawn in its market", moved.bands.isEmpty())
        assertEquals("because the prices are not the ones it was cut against",
            WidgetPlanPass.Remote.Reason.PRICES_MOVED, moved.refused)
        assertEquals("and the market it would have been drawn in is still known", "SE4", moved.market?.areaId)

        val absent = remote(pass(snapshot = confirmed, prices = null))
        assertEquals(confirmed, absent.snapshot)
        assertTrue(absent.stale)
        assertEquals(WidgetPlanPass.Remote.Reason.NO_PRICE_ANSWER, absent.refused)

        // The states that are *not* a failed refresh: each is a current, confirmed fact about this widget.
        assertTrue(
            "a schedule Home Assistant says is not installed is not a stale plan",
            !remote(pass(snapshot = snapshot(installed = false))).stale
        )
        assertTrue(
            "nor is the widget's own choice to hide the plan",
            !remote(pass(snapshot = confirmed, settings = settings.copy(showChargingPlan = false))).stale
        )
        assertTrue("nor a freshly confirmed plan", !remote(pass(snapshot = confirmed)).stale)
        assertTrue("nor a widget with nothing confirmed at all", !remote(pass(snapshot = null)).stale)
    }

    @Test
    fun aConfirmedScheduleOutsideThePricedDaysIsNotStaleAndSimplyShadesNothing() {
        val far = snapshot(periods = listOf(period("2026-10-10T10:00:00Z", "2026-10-10T12:00:00Z")))
        val paired = remote(pass(snapshot = far))

        assertTrue("the snapshot is current and installed, so nothing is wrong", !paired.stale)
        assertTrue("there is simply nothing on this axis to shade", paired.bands.isEmpty())
        assertEquals(WidgetPlanPass.Remote.Reason.OUTSIDE_PRICES, paired.refused)
        assertEquals("and Home Assistant's own market is still drawn", "SE4", paired.market?.areaId)
    }

    @Test
    fun theStalePanelStatesTheSnapshotsOwnWindowsInTheSnapshotsOwnClock() {
        val helsinki = ZoneId.of("Europe/Helsinki")
        val two = snapshot(
            periods = listOf(
                period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z"),
                period("2026-09-27T18:00:00Z", "2026-09-27T19:30:00Z")
            )
        )

        assertEquals(
            listOf("Sun 12:00 -> Sun 15:45", "Sun 20:00 -> Sun 21:30"),
            widgetNotice(two)
        )
        assertEquals(
            "the same instants in another market's clock: the snapshot's zone, never the device's",
            listOf("Sun 13:00 -> Sun 16:45", "Sun 21:00 -> Sun 22:30"),
            widgetNotice(two.copy(zoneId = helsinki))
        )
    }

    private fun widgetNotice(snapshot: WidgetPlanSnapshot): List<String> =
        WidgetPlanNotice.installedWindows(snapshot, Locale.ENGLISH) { start, end -> "$start -> $end" }

    @Test
    fun thePricesAreLoadedForTheSnapshotsMarketAndNeverTheWidgetsStoredOne() {
        assertEquals("SE4", WidgetPlanDecision.priceArea(charger, snapshot(), settings.area))
        assertNull(
            "a paired widget with nothing confirmed has no market to price at all",
            WidgetPlanDecision.priceArea(charger, null, settings.area)
        )
        assertNull(
            "and another charger's snapshot names none either",
            WidgetPlanDecision.priceArea(charger, snapshot(profileId = otherCharger), settings.area)
        )
        assertEquals("NO1", WidgetPlanDecision.priceArea(null, snapshot(), settings.area))
        assertEquals("NO1", WidgetPlanDecision.priceArea(null, null, settings.area))
    }

    @Test
    fun aPriceIdentityIsAboutItsOwnMarketsDocuments() {
        val identity = PriceDocumentIdentity.of("SE4", documents())

        assertTrue(identity.matches(documents()))
        assertTrue("the same instants, priced differently: same geometry", identity.matches(
            PriceResult(day(today).map { it.copy(pricePerKwh = 9.9) }, day(today.plusDays(1)), 100L)
        ))
        assertTrue("an extra document is a different set", !identity.matches(otherDocuments()))
        assertTrue("a missing day is a different set", !identity.matches(PriceResult(day(today), emptyList(), 0L)))
    }


    private fun subject(
        profileId: String = charger,
        areaId: String = "SE4",
        generation: Int = 1
    ) = PriceRequestKey(profileId, areaId, generation)

    private fun record(areaId: String? = "SE4", revision: Int = 3): HaPlanningSettings =
        SettingsFixtures.parsed(revision = revision, areaId = areaId)

    private fun confirmedPlan(
        installed: Boolean = true,
        periods: List<ChargingPeriod> = listOf(installed())
    ) = RemotePlan(installed = installed, periods = periods, amps = 16, chargingEnabled = false)

    private fun auto(record: HaPlanningSettings = record(), plan: RemotePlan? = confirmedPlan()) =
        VisibleAuthority.AutoRemote(record, plan, record.revision, AuthorityAvailability.CONFIRMED)

    private fun capture(
        state: VisibleAuthority?,
        prices: PriceResult? = documents(),
        subject: PriceRequestKey? = subject(),
        capturedAt: Long = 4_242L
    ): WidgetPlanSnapshot? = WidgetPlanCapture.of(charger, subject, state, prices, 15, capturedAt)

    @Test
    fun aConfirmedCoherentPassIsCapturedWhole() {
        val captured = capture(auto(record(revision = 7)))

        assertNotNull("a confirmed record with a decoded status and its own prices is capturable", captured)
        assertEquals(charger, captured!!.profileId)
        assertEquals(7, captured.revision)
        assertEquals("SE4", captured.areaId)
        assertEquals(stockholm, captured.zoneId)
        assertEquals(15, captured.intervalMinutes)
        assertEquals(
            "the installed schedule's instants, exactly",
            listOf(installed().start.toInstant() to installed().end.toInstant()),
            captured.periods.map { it.start.toInstant() to it.end.toInstant() }
        )
        assertEquals(PriceDocumentIdentity.of("SE4", documents()), captured.priceIdentity)
        assertEquals(4_242L, captured.capturedAt)
        assertTrue(captured.installed)
        assertEquals("and it draws the schedule it captured", 1, remote(pass(snapshot = captured)).bands.size)
    }

    @Test
    fun theCompatibilityExternalRecordIsCapturedToo() {
        val external = record()
        val state = VisibleAuthority.AutoRemote(
            settings = external,
            remotePlan = confirmedPlan(),
            revision = external.revision,
            availability = AuthorityAvailability.CONFIRMED
        )

        val captured = capture(state)
        assertNotNull("`external` is a stored compatibility value, not a different owner", captured)
        assertEquals(1, remote(pass(snapshot = captured)).bands.size)
    }

    @Test
    fun aChargerThatStatedNoInstalledScheduleIsCapturedAsExactlyThat() {
        val captured = capture(auto(plan = confirmedPlan(installed = false, periods = emptyList())))

        assertNotNull("an explicit \"nothing is installed\" is a confirmed answer", captured)
        assertTrue(!captured!!.installed)
        assertTrue(captured.periods.isEmpty())
        // And that confirmed answer supersedes an older installed one instead of being ignored.
        val store = WidgetPlanSnapshotStore(FakeKeyValueStore()) { }
        store.put(snapshot())
        assertTrue(store.put(captured) is WidgetPlanSnapshotStore.Merge.Stored)
        assertEquals(captured, store.snapshotFor(charger))
        assertTrue("the widget shades nothing for it", remote(pass(snapshot = captured)).bands.isEmpty())
    }

    @Test
    fun anOfflineStateWithoutAStatusOrWithoutItsOwnPricesCapturesNothing() {
        assertNull("offline: no confirmed record", capture(VisibleAuthority.ReadOnlyOffline(record())))
        assertNull("no decoded status: nothing was stated about a schedule", capture(auto(plan = null)))
        assertNull("prices still loading", capture(auto(), prices = null))
        assertNull(
            "a state this app cannot read",
            capture(VisibleAuthority.Incomplete(listOf(HaPlanningInputs.Reason.AMPS), record()))
        )
    }

    @Test
    fun aPassWhoseSubjectIsAnotherProfileAnotherAreaOrAnotherChargeCapturesNothing() {
        assertNull("another charger's prices", capture(auto(), subject = subject(profileId = otherCharger)))
        assertNull("another market's prices", capture(auto(), subject = subject(areaId = "NO1")))
        assertNull("the area the record names is what must be priced", capture(auto(record(areaId = null))))
        assertNull(
            "an area the catalogue does not carry is no market to draw",
            capture(auto(record(areaId = "XX9")))
        )
    }


    @Test
    fun oneAcceptedNewPlanAsksForExactlyOneRedrawAndOnlyAfterItIsStored() {
        val store = WidgetPlanSnapshotStore(FakeKeyValueStore()) { }
        var redraws = 0
        var seenAtRedraw: WidgetPlanSnapshot? = null
        val publication = WidgetPlanPublication(store) {
            redraws++
            seenAtRedraw = store.snapshotFor(charger)
        }

        val first = capture(auto(record(revision = 7)), capturedAt = 1_000L)!!
        assertTrue(publication.publish(first) is WidgetPlanSnapshotStore.Merge.Stored)
        assertEquals("exactly one redraw for one accepted plan", 1, redraws)
        assertEquals("asked for only once the widget can already read it", first, seenAtRedraw)

        // The same facts confirmed again: nothing new, so nothing is rewritten and nothing redraws.
        val again = capture(auto(record(revision = 7)), capturedAt = 2_000L)!!
        assertTrue(publication.publish(again) is WidgetPlanSnapshotStore.Merge.Unchanged)
        assertEquals(1, redraws)
        assertEquals(1_000L, store.snapshotFor(charger)?.capturedAt)

        // A genuinely new plan: one more write, one more redraw.
        val moved = capture(auto(record(revision = 8)), capturedAt = 3_000L)!!
        assertTrue(publication.publish(moved) is WidgetPlanSnapshotStore.Merge.Stored)
        assertEquals(2, redraws)
        assertEquals(8, store.snapshotFor(charger)?.revision)

        // A pass that is not a confirmed coherent one publishes nothing at all.
        assertNull(publication.publish(null))
        assertEquals(2, redraws)
        assertEquals(8, store.snapshotFor(charger)?.revision)
    }

    @Test
    fun aFailedRefreshRetainsTheExactPriorSnapshotAsStale() {
        val store = WidgetPlanSnapshotStore(FakeKeyValueStore()) { }
        var redraws = 0
        val publication = WidgetPlanPublication(store) { redraws++ }
        val confirmed = capture(auto(), capturedAt = 1_000L)!!
        publication.publish(confirmed)
        val before = store.snapshotFor(charger)!!

        // The status read fails and then succeeds with nothing decodable: both passes capture nothing.
        assertNull(capture(VisibleAuthority.ReadOnlyOffline(record()), capturedAt = 9_000L))
        assertNull(capture(auto(plan = null), capturedAt = 9_000L))
        assertNull(publication.publish(null))

        assertEquals("the exact prior snapshot, value for value", before, store.snapshotFor(charger))
        assertEquals("and none of it was re-stamped", 1_000L, store.snapshotFor(charger)?.capturedAt)
        assertEquals("nothing asked for a redraw", 1, redraws)
        assertEquals("it still draws the confirmed plan", 1, remote(pass(snapshot = store.snapshotFor(charger))).bands.size)
    }

    @Test
    fun aRepeatedPassCostsNoStorageReadAtAll() {
        val counted = CountingStore()
        val store = WidgetPlanSnapshotStore(counted) { }
        var redraws = 0
        val publication = WidgetPlanPublication(store) { redraws++ }

        publication.publish(capture(auto(record(revision = 7)), capturedAt = 1_000L)!!)
        val readsAfterFirst = counted.reads

        publication.publish(capture(auto(record(revision = 7)), capturedAt = 2_000L)!!)

        assertEquals("no second read for a repeated pass", readsAfterFirst, counted.reads)
        assertEquals(1, redraws)
    }

    /** A [KeyValueStore] that counts reads, so "no read at all" is provable rather than asserted. */
    private class CountingStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        var reads = 0
            private set

        override fun getString(key: String): String? {
            reads++
            return values[key]
        }

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    @Test
    fun anObsoletePassIsNeitherPublishedNorRedrawn() {
        val store = WidgetPlanSnapshotStore(FakeKeyValueStore()) { }
        var redraws = 0
        val publication = WidgetPlanPublication(store) { redraws++ }

        // The charger cleared its schedule at revision 8...
        val cleared = capture(
            auto(record(revision = 8), plan = confirmedPlan(installed = false, periods = emptyList())),
            capturedAt = 8_000L
        )!!
        publication.publish(cleared)

        // ... and a pass captured earlier, still carrying the former plan, arrives late.
        val obsolete = capture(auto(record(revision = 7)), capturedAt = 7_000L)!!
        assertTrue(publication.publish(obsolete) is WidgetPlanSnapshotStore.Merge.Unchanged)

        assertEquals("one redraw, for the plan that actually changed", 1, redraws)
        assertEquals(cleared, store.snapshotFor(charger))
        assertTrue(
            "the cleared plan was not resurrected",
            remote(pass(snapshot = store.snapshotFor(charger))).bands.isEmpty()
        )
    }
}
