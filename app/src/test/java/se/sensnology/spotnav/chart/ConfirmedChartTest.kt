package se.sensnology.spotnav.chart

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.ha.authority.AuthorityAvailability
import se.sensnology.spotnav.ha.authority.PriceRequestKey
import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import java.time.LocalDate
import java.time.ZoneId

/** The last confirmed chart, and what may be shown from it while the connection is gone. */
class ConfirmedChartTest {
    private val se4 = RelayFixtures.se4
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val date: LocalDate = LocalDate.of(2026, 9, 12)
    private val profile = "profile-a"
    private val generation = 3

    @Before
    fun loadCatalogue() {
        PriceMarkets.replace(listOf(se4, RelayFixtures.no1))
    }

    /** A record whose three fiscal components are all stated, so a market is always buildable from it. */
    private fun record(revision: Int, areaId: String = "SE4"): HaPlanningSettings =
        SettingsFixtures.parsed(
            revision = revision,
            areaId = areaId,
            overrides = JSONArray().put(
                SettingsFixtures.override(
                    areaId = areaId,
                    vat = SettingsFixtures.fiscal(enabled = true, value = 25.0),
                    tax = SettingsFixtures.fiscal(enabled = true, value = 36.0),
                    transfer = SettingsFixtures.fiscal(enabled = true, value = 30.0)
                )
            )
        )

    private fun auto(record: HaPlanningSettings, plan: RemotePlan?): VisibleAuthority = VisibleAuthority.AutoRemote(
        settings = record,
        remotePlan = plan,
        revision = record.revision,
        availability = AuthorityAvailability.CONFIRMED
    )

    private fun external(record: HaPlanningSettings): VisibleAuthority =
        VisibleAuthority.AutoRemote(
            settings = record,
            remotePlan = null,
            revision = record.revision,
            availability = AuthorityAvailability.CONFIRMED
        )

    /** The external plan the *planner* would produce for [record], for the tests that need one. */
    private fun externalInputs(record: HaPlanningSettings): PlanningInputs =
        SettingsFixtures.localInputs(record, listOf(se4))

    /** A day of quarter hours on a fixed date: the real planner finds nothing in a past day. */
    private fun prices(): PriceResult {
        val midnight = date.atStartOfDay(stockholm).toOffsetDateTime()
        return PriceResult(
            (0 until 96).map { index -> PricePoint(midnight.plusMinutes(index * 15L), 0.4 + (index % 4) * 0.3) },
            emptyList(),
            0L
        )
    }

    private fun subject(areaId: String = "SE4", profileId: String? = "profile-a", screen: Int = 3) =
        PriceRequestKey(profileId, areaId, screen)

    private fun period(fromHour: Int, toHour: Int) = ChargingPeriod(
        date.atTime(fromHour, 0).atZone(stockholm).toOffsetDateTime(),
        date.atTime(toHour, 0).atZone(stockholm).toOffsetDateTime()
    )

    /** A plan no planner would produce from [prices]: its window is not the cheapest one there. */
    private fun plan(hours: Pair<Int, Int> = 1 to 3, energyKwh: Double = 12.5) = ChargingPlan(
        start = period(hours.first, hours.first).start,
        end = period(hours.second, hours.second).end,
        powerKw = 6.9,
        energyKwh = energyKwh,
        distanceMil = 7.5,
        cost = 42.5,
        unpricedSlots = 0,
        periods = listOf(period(hours.first, hours.second))
    )

    private fun market(record: HaPlanningSettings): ChartMarket =
        (ChartMarket.from(record, record.areaId!!, se4) as ChartMarketBuild.Ready).market


    @Test
    fun confirmedAutoOfflineKeepsItsExactMarketAndInstalledBands() {
        val record = record(revision = 6)
        val installed = RemotePlan(
            installed = true,
            periods = listOf(period(1, 3), period(20, 22)),
            amps = 16,
            chargingEnabled = true
        )
        val state = auto(record, installed)
        val prices = prices()
        val market = market(record)
        val bands = ChartOverlay.installed(installed).bands(prices, se4.zoneId)

        val retained = ConfirmedChartRules.capture(
            state = state, subject = subject(), prices = prices, market = market,
            bands = bands, footer = null, profileId = profile, screenGeneration = generation
        )
        assertTrue("a confirmed Auto pass retains its chart", retained != null)
        assertEquals("the same market", market, retained!!.market)
        assertEquals("and exactly the installed bands", bands, retained.bands)
        assertEquals(2, retained.bands.size)
        assertNull("Auto has no footer to retain", retained.footer)
        assertEquals("and the schedule the same dashboard reported", installed, retained.remotePlan)

        // It is reusable for that record and refused for any other subject.
        assertTrue(ConfirmedChartRules.matches(retained, profile, generation, record, subject()))
        assertFalse(
            "another record is not this chart's subject",
            ConfirmedChartRules.matches(retained, profile, generation, record(revision = 7), subject())
        )
    }

    @Test
    fun aPairedChartIsRetainedWithTheHomeAssistantPricesAndFiguresItWasDrawnFrom() {
        val record = record(revision = 6)
        val relay = prices()
        val drawn = relay.copy(fetchedAt = 42L)
        val figures = PairedPlanFigures(
            periods = listOf(period(1, 3)), fromProposal = true, costMajor = 34.6, costCurrency = "SEK",
            costUnit = "kr", unpriced = false, unpricedSlots = 0, energyKwh = 20.0, distanceMil = 10.0
        )
        val retained = ConfirmedChartRules.capture(
            state = auto(record, null), subject = subject(), prices = relay, market = market(record),
            bands = emptyList(), footer = null, profileId = profile, screenGeneration = generation,
            drawnPrices = drawn, figures = figures
        )!!
        assertEquals("the graph's own prices are kept beside the relay answer it is keyed on", drawn, retained.drawnPrices)
        assertEquals(relay, retained.prices)
        assertEquals(figures, retained.figures)
        // A chart drawn from the relay retains no separate drawn prices.
        val plain = ConfirmedChartRules.capture(
            state = auto(record, null), subject = subject(), prices = relay, market = market(record),
            bands = emptyList(), footer = null, profileId = profile, screenGeneration = generation
        )!!
        assertNull(plain.drawnPrices)
        assertNull(plain.figures)
    }

    @Test
    fun aConfirmedPassRetainsTheExactChartItDrewWithoutAnotherPlannerCall() {
        val record = record(revision = 6)
        val prices = prices()
        val market = market(record)
        // A window the planner would *not* choose from these prices.
        val drawn = plan(hours = 1 to 3, energyKwh = 12.5)
        val bands = ChartOverlay.planned(drawn.periods).bands(prices, se4.zoneId)
        val footer = ChartFooterPlan(
            start = drawn.start, end = drawn.end, periodCount = 1,
            unpriced = false, energyKwh = 12.5, distanceMil = 7.5
        )

        val retained = ConfirmedChartRules.capture(
            state = external(record), subject = subject(), prices = prices, market = market,
            bands = bands, footer = footer, profileId = profile, screenGeneration = generation
        )!!

        val whatThePlannerWouldSay = ChargingPlanner.calculate(prices, externalInputs(record))
        assertNotEquals(
            "the retained chart is not what the planner would say now",
            whatThePlannerWouldSay?.periods?.let { ChartOverlay.planned(it).bands(prices, se4.zoneId) },
            retained.bands
        )
        assertEquals(footer, retained.footer)
        assertNull("no reported schedule is involved", retained.remotePlan)
        assertEquals(prices, retained.prices)
        assertEquals("SE4", retained.areaId)
    }

    @Test
    fun mismatchedProfileScreenAreaRevisionOrPriceIdentityRefusesTheSnapshot() {
        val record = record(revision = 6)
        val prices = prices()
        val retained = ConfirmedChartRules.capture(
            state = external(record), subject = subject(), prices = prices, market = market(record),
            bands = emptyList(), footer = null, profileId = profile, screenGeneration = generation
        )!!

        assertTrue("its own subject", ConfirmedChartRules.matches(retained, profile, generation, record, subject()))
        assertTrue(
            "and no held answer at all is still its own subject",
            ConfirmedChartRules.matches(retained, profile, generation, record, subject = null)
        )
        for ((what, ok) in listOf(
            "another profile" to ConfirmedChartRules.matches(retained, "profile-b", generation, record, subject()),
            "another screen generation" to ConfirmedChartRules.matches(retained, profile, generation + 1, record, subject()),
            "another area" to ConfirmedChartRules.matches(retained, profile, generation, record(revision = 6, areaId = "NO1"), subject("NO1")),
            "another revision" to ConfirmedChartRules.matches(retained, profile, generation, record(revision = 7), subject()),
            "another price identity" to ConfirmedChartRules.matches(retained, profile, generation, record, subject(profileId = "profile-b")),
            "no record at all" to ConfirmedChartRules.matches(retained, profile, generation, null, subject()),
            "no snapshot at all" to ConfirmedChartRules.matches(null, profile, generation, record, subject())
        )) {
            assertFalse("$what must refuse the snapshot", ok)
        }
    }

    @Test
    fun onlyAConfirmedCoherentPassRetainsAnything() {
        val record = record(revision = 6)
        val prices = prices()
        val market = market(record)

        fun capture(
            state: VisibleAuthority? = external(record),
            request: PriceRequestKey? = subject(),
            priceResult: PriceResult? = prices,
            chart: ChartMarket? = market
        ) = ConfirmedChartRules.capture(
            state = state, subject = request, prices = priceResult, market = chart,
            bands = emptyList(), footer = null, profileId = profile, screenGeneration = generation
        )

        assertTrue("the coherent confirmed pass", capture() != null)
        assertNull("a cached record", capture(state = VisibleAuthority.ReadOnlyOffline(record)))
        assertNull("an unconfirmed record", capture(state = VisibleAuthority.AutoRemote(record, null, 6, AuthorityAvailability.CACHED)))
        assertNull("a pending answer", capture(state = null))
        assertNull("prices that have not arrived", capture(priceResult = null))
        assertNull("no chart drawn", capture(chart = null))
        assertNull("no request identity", capture(request = null))
        assertNull("another screen's request", capture(request = subject(screen = generation + 1)))
        assertNull("another area's request", capture(request = subject(areaId = "NO1")))
    }

}
