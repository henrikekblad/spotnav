package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** The bands a graph shades: explicit data, with the geometry already settled. */
class ChartOverlayTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")

    /** One market day's price document: quarter-hour points, which is enough shape to have a column. */
    private fun day(date: LocalDate, zone: ZoneId = stockholm): List<PricePoint> =
        (0 until 24).flatMap { hour ->
            (0 until 4).map { quarter ->
                PricePoint(date.atTime(hour, quarter * 15).atZone(zone).toOffsetDateTime(), 0.5)
            }
        }

    private fun result(vararg dates: LocalDate): PriceResult =
        PriceResult(today = dates.firstOrNull()?.let { day(it) } ?: emptyList(), tomorrow = dates.getOrNull(1)?.let { day(it) } ?: emptyList(), fetchedAt = 0L)

    private fun period(from: String, to: String): ChargingPeriod =
        ChargingPeriod(OffsetDateTime.parse(from), OffsetDateTime.parse(to))

    private fun plan(period: ChargingPeriod): ChartOverlay = ChartOverlay.planned(listOf(period))


    @Test
    fun noInstalledScheduleMeansNoOverlay() {
        assertEquals(ChartOverlay.NONE, ChartOverlay.installed(null))
        assertEquals(
            "periods without `installed` are a stale payload, not a schedule",
            ChartOverlay.NONE,
            ChartOverlay.installed(
                RemotePlan(
                    installed = false,
                    periods = listOf(period("2026-09-12T18:00+02:00", "2026-09-12T19:00+02:00")),
                    amps = 16,
                    chargingEnabled = true
                )
            )
        )
    }

    @Test
    fun theInstalledPeriodsAreExactlyWhatIsShaded() {
        val installed = period("2026-09-12T18:00+02:00", "2026-09-12T19:00+02:00")
        val overlay = ChartOverlay.installed(
            RemotePlan(installed = true, periods = listOf(installed), amps = 16, chargingEnabled = false)
        )
        assertEquals(ChartOverlay(listOf(installed)), overlay)
        assertEquals(
            listOf(ChartBand(LocalDate.of(2026, 9, 12), 18f * 60, 19f * 60)),
            overlay.bands(result(LocalDate.of(2026, 9, 12)), stockholm)
        )
    }

    @Test
    fun aNonPositivePeriodShadesNothing() {
        assertEquals(ChartOverlay.NONE, ChartOverlay.planned(listOf(period("2026-09-12T18:00+02:00", "2026-09-12T18:00+02:00"))))
        assertEquals(
            ChartOverlay.NONE,
            ChartOverlay.installed(
                RemotePlan(
                    installed = true,
                    periods = listOf(period("2026-09-12T19:00+02:00", "2026-09-12T18:00+02:00")),
                    amps = null,
                    chargingEnabled = false
                )
            )
        )
        assertTrue(
            plan(period("2026-09-12T19:00+02:00", "2026-09-12T18:00+02:00"))
                .bands(result(LocalDate.of(2026, 9, 12)), stockholm)
                .isEmpty()
        )
    }


    @Test
    fun aPeriodOutsideTheRepresentedDatesShadesNothing() {
        val otherDay = plan(period("2026-09-11T02:00+02:00", "2026-09-11T03:00+02:00"))
        assertEquals(
            "the same wall-clock hour on another date is another day",
            emptyList<ChartBand>(),
            otherDay.bands(result(LocalDate.of(2026, 9, 12)), stockholm)
        )
        assertEquals(emptyList<ChartBand>(), plan(period("2026-09-20T02:00+02:00", "2026-09-20T03:00+02:00")).bands(result(LocalDate.of(2026, 9, 12)), stockholm))
        // A day with no price document at all represents no column either.
        assertEquals(emptyList<ChartBand>(), otherDay.bands(result(), stockholm))
    }

    @Test
    fun aMissingDateBetweenTwoRepresentedDatesGetsNoBand() {
        val spanning = plan(period("2026-09-12T22:00+02:00", "2026-09-14T02:00+02:00"))
        val bands = spanning.bands(result(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 14)), stockholm)

        assertEquals(
            "the missing day is not a column: a band for the 12th and one for the 14th, and none for the 13th",
            listOf(
                ChartBand(LocalDate.of(2026, 9, 12), 22f * 60, 24f * 60),
                ChartBand(LocalDate.of(2026, 9, 14), 0f, 2f * 60)
            ),
            bands
        )
        assertTrue("and nothing claims the 13th", bands.none { it.date == LocalDate.of(2026, 9, 13) })
    }

    @Test
    fun aRepeatedDateIsOneColumnAndOneBand() {
        // Today and tomorrow both carrying points for one date is still one column, shaded once.
        val oneDay = period("2026-09-12T18:00+02:00", "2026-09-12T19:00+02:00")
        val duplicated = PriceResult(
            today = day(LocalDate.of(2026, 9, 12)),
            tomorrow = day(LocalDate.of(2026, 9, 12)),
            fetchedAt = 0L
        )
        assertEquals(
            listOf(ChartBand(LocalDate.of(2026, 9, 12), 18f * 60, 19f * 60)),
            ChartOverlay.planned(listOf(oneDay)).bands(duplicated, stockholm)
        )
    }

    @Test
    fun aMidnightCrossingIsSplitIntoTwoBands() {
        val bands = plan(period("2026-09-12T23:00+02:00", "2026-09-13T01:30+02:00"))
            .bands(result(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13)), stockholm)

        assertEquals(
            listOf(
                ChartBand(LocalDate.of(2026, 9, 12), 23f * 60, 24f * 60),
                ChartBand(LocalDate.of(2026, 9, 13), 0f, 1.5f * 60)
            ),
            bands
        )
        assertInvariants(bands)
    }


    @Test
    fun aSpringForwardCrossingSkipsTheHourThatDoesNotExist() {
        // 2026-03-29 is 23 hours long in Stockholm: 02:00 CET becomes 03:00 CEST.
        val day = LocalDate.of(2026, 3, 29)
        val transition = Instant.parse("2026-03-29T01:00:00Z")
        val bands = plan(period("2026-03-29T01:30+01:00", "2026-03-29T04:00+02:00")).bands(result(day), stockholm)

        assertEquals("split exactly at the transition, and nothing else", 2, bands.size)
        assertEquals("up to the hour that never happened", ChartBand(day, 1.5f * 60, 2f * 60), bands[0])
        assertEquals("and again from the instant it was skipped to", ChartBand(day, 3f * 60, 4f * 60), bands[1])
        assertEquals(
            "the hour between them does not exist",
            transition,
            Instant.parse("2026-03-29T01:00:00Z")
        )
        assertInvariants(bands)
    }

    @Test
    fun aFallBackCrossingIsTwoBandsAndNeitherRunsBackwards() {
        val day = LocalDate.of(2026, 10, 25)
        val bands = plan(period("2026-10-25T02:50+02:00", "2026-10-25T02:10+01:00")).bands(result(day), stockholm)

        assertEquals("split at the transition, not collapsed into a backwards band", 2, bands.size)
        assertEquals(ChartBand(day, 2f * 60, 2f * 60 + 10f), bands[0])
        assertEquals(ChartBand(day, 2f * 60 + 50f, 3f * 60), bands[1])
        bands.forEach { band ->
            assertTrue("a band never has its local end before its local start", band.toMinute > band.fromMinute)
        }
        // Both real occurrences are represented, each as its own correct wall-clock range.
        assertTrue(bands.all { it.date == day })
        assertInvariants(bands)
    }

    @Test
    fun aTransitionExactlyAtAStartOrEndIsNotABandOfItsOwn() {
        val day = LocalDate.of(2026, 10, 25)
        // Starts exactly at the transition: the first occurrence contributes nothing at all.
        val startsAtTransition = plan(period("2026-10-25T03:00+02:00", "2026-10-25T04:00+01:00")).bands(result(day), stockholm)
        assertEquals(
            listOf(ChartBand(day, 2f * 60, 4f * 60)),
            startsAtTransition
        )
        // Ends exactly at the transition: the second occurrence contributes nothing.
        val endsAtTransition = plan(period("2026-10-25T00:30+02:00", "2026-10-25T03:00+02:00")).bands(result(day), stockholm)
        assertEquals(listOf(ChartBand(day, 0.5f * 60, 3f * 60)), endsAtTransition)
        assertInvariants(startsAtTransition + endsAtTransition)
    }

    @Test
    fun aTwentyThreeHourDayIsBoundedByItsOwnMidnights() {
        val day = LocalDate.of(2026, 3, 29)
        val wholeDay = plan(period("2026-03-29T00:00+01:00", "2026-03-30T00:00+02:00"))
        val bands = wholeDay.bands(result(day), stockholm)

        assertEquals(2, bands.size)
        assertEquals(ChartBand(day, 0f, 2f * 60), bands[0])
        assertEquals(ChartBand(day, 3f * 60, 24f * 60), bands[1])
        assertEquals(
            "23 real hours, drawn as the wall clock labels them",
            23,
            Duration.between(OffsetDateTime.parse("2026-03-29T00:00+01:00"), OffsetDateTime.parse("2026-03-30T00:00+02:00")).toHours()
        )
        assertInvariants(bands)
    }

    @Test
    fun aTwentyFiveHourDayIsBoundedByItsOwnMidnights() {
        val day = LocalDate.of(2026, 10, 25)
        val wholeDay = plan(period("2026-10-25T00:00+02:00", "2026-10-26T00:00+01:00"))
        val bands = wholeDay.bands(result(day), stockholm)

        assertEquals(1, bands.size)
        assertEquals(ChartBand(day, 0f, 24f * 60), bands[0])
        assertEquals(
            "25 real hours",
            25,
            Duration.between(OffsetDateTime.parse("2026-10-25T00:00+02:00"), OffsetDateTime.parse("2026-10-26T00:00+01:00")).toHours()
        )
        assertInvariants(bands)
    }

    @Test
    fun aScheduleCoveringBothOccurrencesOfTheRepeatedHourIsOneUnionBand() {
        val day = LocalDate.of(2026, 10, 25)
        // One period over each occurrence of the repeated hour: 00:30-03:00 CEST and 02:00-04:00 CET.
        val bothOccurrences = ChartOverlay.planned(
            listOf(
                period("2026-10-25T00:30+02:00", "2026-10-25T03:00+02:00"),
                period("2026-10-25T02:00+01:00", "2026-10-25T04:00+01:00")
            )
        )
        assertEquals(
            listOf(ChartBand(day, 0.5f * 60, 4f * 60)),
            bothOccurrences.bands(result(day), stockholm)
        )
    }

    @Test
    fun overlappingAndDuplicatePeriodsPaintOneBand() {
        val day = LocalDate.of(2026, 9, 12)
        val duplicated = period("2026-09-12T18:00+02:00", "2026-09-12T19:00+02:00")
        val overlapping = period("2026-09-12T18:30+02:00", "2026-09-12T19:30+02:00")
        val inside = period("2026-09-12T18:10+02:00", "2026-09-12T18:20+02:00")
        assertEquals(
            listOf(ChartBand(day, 18f * 60, 19.5f * 60)),
            ChartOverlay.planned(listOf(duplicated, duplicated, overlapping, inside)).bands(result(day), stockholm)
        )
    }

    @Test
    fun touchingBandsMergeAndSeparatedOnesDoNot() {
        val day = LocalDate.of(2026, 9, 12)
        val touching = listOf(
            period("2026-09-12T10:00+02:00", "2026-09-12T11:00+02:00"),
            period("2026-09-12T13:00+02:00", "2026-09-12T14:00+02:00"),
            period("2026-09-12T11:00+02:00", "2026-09-12T13:00+02:00")
        )
        assertEquals(
            "adjacent stretches are one band, and the gap after it is not bridged",
            listOf(
                ChartBand(day, 10f * 60, 14f * 60),
                ChartBand(day, 15f * 60, 16f * 60)
            ),
            ChartOverlay.planned(touching + period("2026-09-12T15:00+02:00", "2026-09-12T16:00+02:00"))
                .bands(result(day), stockholm)
        )
        assertEquals(
            "two separated stretches stay two bands",
            listOf(
                ChartBand(day, 6f * 60, 7f * 60),
                ChartBand(day, 20f * 60, 21f * 60)
            ),
            ChartOverlay.planned(
                listOf(
                    period("2026-09-12T20:00+02:00", "2026-09-12T21:00+02:00"),
                    period("2026-09-12T06:00+02:00", "2026-09-12T07:00+02:00")
                )
            ).bands(result(day), stockholm)
        )
    }

    @Test
    fun disjointFallBackRangesStaySeparate() {
        val day = LocalDate.of(2026, 10, 25)
        // One occurrence on each side of the transition, with a real gap between them in wall clock.
        val disjoint = ChartOverlay.planned(
            listOf(
                period("2026-10-25T02:30+01:00", "2026-10-25T02:40+01:00"),
                period("2026-10-25T02:50+02:00", "2026-10-25T03:00+02:00")
            )
        )
        assertEquals(
            listOf(
                ChartBand(day, 2f * 60 + 30f, 2f * 60 + 40f),
                ChartBand(day, 2f * 60 + 50f, 3f * 60)
            ),
            disjoint.bands(result(day), stockholm)
        )
    }


    private fun assertInvariants(bands: List<ChartBand>) {
        val represented = setOf(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 14))
            .ifEmpty { emptySet() }
        bands.forEach { band ->
            assertTrue("$band must have a positive wall-clock width", band.toMinute > band.fromMinute)
            assertTrue("$band must start inside its day", band.fromMinute >= 0f)
            assertTrue("$band must end inside its day", band.toMinute <= 1440f)
            assertTrue("$band must be drawable", band.isDrawable)
            assertTrue(
                "$band belongs to a represented date or one of the DST test days",
                band.date in represented || band.date == LocalDate.of(2026, 3, 29) || band.date == LocalDate.of(2026, 10, 25)
            )
        }
    }
}
