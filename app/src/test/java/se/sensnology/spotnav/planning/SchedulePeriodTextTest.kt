package se.sensnology.spotnav.planning

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.chart.ChartBand
import se.sensnology.spotnav.chart.ChartOverlay
import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.testing.RelayFixtures
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** The one schedule-period presentation rule, proved on the market clock rather than argued. */
class SchedulePeriodTextTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val newYork = ZoneId.of("America/New_York")

    @Before fun seedTheCatalogue() {
        PriceMarkets.replace(listOf(RelayFixtures.se4, RelayFixtures.no1))
    }

    @After fun clearTheCatalogue() {
        PriceMarkets.replace(emptyList())
    }

    private fun period(from: String, to: String): ChargingPeriod =
        ChargingPeriod(OffsetDateTime.parse(from), OffsetDateTime.parse(to))

    private fun line(period: ChargingPeriod, zoneId: ZoneId): String =
        SchedulePeriodText.line(period, zoneId, Locale.ENGLISH) { start, end -> "$start–$end" }

    /** One day of quarter hours from [date] in the market's own clock, tied to the given prices. */
    private fun prices(date: LocalDate, zoneId: ZoneId): PriceResult {
        val midnight = date.atStartOfDay(zoneId).toOffsetDateTime()
        return PriceResult(
            (0 until 96).map { index -> PricePoint(midnight.plusMinutes(index * 15L), 0.4 + (index % 4) * 0.3) },
            emptyList(),
            0L
        )
    }

    // The
    // live defect, and the graph it disagreed with.

    @Test
    fun aUtcPeriodReadsAsTheMarketsClockAndShadesTheSameInstants() {
        val period = period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z")

        val window = SchedulePeriodText.window(period, stockholm)
        assertEquals(LocalTime.of(12, 0), window.start.time)
        assertEquals(LocalTime.of(15, 45), window.end.time)
        assertEquals("the market's own clock, not the wire's offset", "Sun 12:00–Sun 15:45", line(period, stockholm))
        // The interval is the instants, untouched: a conversion that moved them would be a
        // different schedule however right its label looked.
        assertEquals(period.start.toInstant(), window.start.instant)
        assertEquals(period.end.toInstant(), window.end.instant)

        // And the band the graph draws is that same wall-clock stretch, from the *same* instants.
        val bands = ChartOverlay.installed(
            RemotePlan(installed = true, periods = listOf(period), amps = 16, chargingEnabled = false)
        ).bands(prices(LocalDate.of(2026, 9, 27), stockholm), stockholm)
        assertEquals(listOf(ChartBand(LocalDate.of(2026, 9, 27), 12f * 60, 15.75f * 60)), bands)
    }

    @Test
    fun theSameInstantsReadInAnotherMarketFollowThatMarket() {
        val period = period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z")

        assertEquals("Sun 06:00–Sun 09:45", line(period, newYork))
        assertEquals(
            "the same period, two clocks, one instant pair",
            SchedulePeriodText.window(period, stockholm).start.instant,
            SchedulePeriodText.window(period, newYork).start.instant
        )
    }

    // A
    // period that crosses local midnight labels both dates.

    @Test
    fun aPeriodAcrossMidnightLabelsBothLocalDates() {
        val period = period("2026-09-27T21:00:00Z", "2026-09-28T04:00:00Z")
        val window = SchedulePeriodText.window(period, stockholm)

        assertEquals(LocalDate.of(2026, 9, 27), window.start.date)
        assertEquals(LocalDate.of(2026, 9, 28), window.end.date)
        assertTrue("the two dates are both stated", window.crossesLocalMidnight)
        assertEquals("Sun 27 Sep 23:00–Mon 28 Sep 06:00", line(period, stockholm))
        // A period that stays inside its own day says the weekday only, as the card always has.
        assertEquals("Sun 12:00–Sun 15:45", line(period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z"), stockholm))
    }

    // Both
    // offset transitions, with the elapsed instants preserved.

    @Test
    fun theSpringForwardHourIsSkippedAndTheElapsedInstantsAreKept() {
        val period = period("2026-03-29T00:30:00Z", "2026-03-29T02:30:00Z")
        val window = SchedulePeriodText.window(period, stockholm)

        assertEquals(LocalTime.of(1, 30), window.start.time)
        assertEquals(LocalTime.of(4, 30), window.end.time)
        assertEquals(
            "two hours elapsed, while the wall clock advanced three",
            7200L,
            window.end.instant.epochSecond - window.start.instant.epochSecond
        )
        assertEquals("Sun 01:30–Sun 04:30", line(period, stockholm))
    }

    @Test
    fun theFallBackHourLabelsTheInstantsItAppliesToAndKeepsTheElapsedInstants() {
        val first = period("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z")
        val second = period("2026-10-25T01:30:00Z", "2026-10-25T02:30:00Z")

        assertEquals("the repeated hour: an hour elapsed at one wall clock", "Sun 02:30–Sun 02:30", line(first, stockholm))
        assertEquals("Sun 02:30–Sun 03:30", line(second, stockholm))
        // The same label, two different instants: the geometry collapses them, the instants do not.
        assertEquals(3600L, first.end.toInstant().epochSecond - first.start.toInstant().epochSecond)
        assertEquals(3600L, second.end.toInstant().epochSecond - second.start.toInstant().epochSecond)
        assertEquals(
            "the second period names the repeated hour, one hour later",
            3600L,
            second.start.toInstant().epochSecond - first.start.toInstant().epochSecond
        )
    }

    // The
    // device's zone is not an input at all.

    @Test
    fun changingTheDeviceTimeZoneLeavesTheMarketsScheduleTextUnchanged() {
        val period = period("2026-09-27T10:00:00Z", "2026-09-27T13:45:00Z")
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("the market, still", "Sun 12:00–Sun 15:45", line(period, MarketZone.of("SE4")))
            assertEquals(stockholm, MarketZone.of("SE4"))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            assertEquals("the market, again", "Sun 12:00–Sun 15:45", line(period, MarketZone.of("SE4")))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun anAreaTheCatalogueDoesNotKnowFallsBackToTheDeviceClock() {
        assertEquals(ZoneId.systemDefault(), MarketZone.of("XX9"))
        assertEquals(ZoneId.systemDefault(), MarketZone.of(null))
    }

    @Test
    fun anInstantIsNotReinterpretedFromTheWireLocalFields() {
        // The same wall clock, two different instants: a formatter that printed the local fields,
        // or reinterpreted them with `withZoneSameLocal`, would answer `12:00–13:00` for both.
        val utc = period("2026-09-27T12:00:00Z", "2026-09-27T13:00:00Z")
        val local = period("2026-09-27T12:00:00+02:00", "2026-09-27T13:00:00+02:00")

        assertEquals("Sun 14:00–Sun 15:00", line(utc, stockholm))
        assertEquals("Sun 12:00–Sun 13:00", line(local, stockholm))
        assertEquals(
            "and the instants differ by the offset between them",
            7200L,
            utc.start.toInstant().epochSecond - local.start.toInstant().epochSecond
        )
        assertEquals(Instant.parse("2026-09-27T12:00:00Z"), SchedulePeriodText.window(utc, stockholm).start.instant)
    }
}
