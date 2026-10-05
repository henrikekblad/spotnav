package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.prices.AreaPublication
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime

/** When the widget looks for tomorrow's prices: around each configured area's own publication, then every 30 minutes. */
class PriceUpdateSchedulerTest {
    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    private val entsoe = AreaPublication.DEFAULT
    private val agile = AreaPublication(LocalTime.of(16, 0), "Europe/London")
    private val pvpc = AreaPublication(LocalTime.of(20, 15), "Europe/Madrid")

    private fun next(now: String, vararg publications: AreaPublication, available: Boolean = false) =
        PriceUpdateScheduler.nextCheck(at(now), publications.toList(), available)

    @Test
    fun noAreaNoAlarm() {
        assertNull(PriceUpdateScheduler.nextCheck(at("2026-09-22T09:00:00Z"), emptyList(), false))
    }

    @Test
    fun theDefaultAreaIsTriedAtThirteenBrusselsAndAFewTimesAfter() {
        assertEquals(at("2026-09-22T13:00:00+02:00"), next("2026-09-22T09:00:00+02:00", entsoe))
        assertEquals(at("2026-09-22T13:10:00+02:00"), next("2026-09-22T13:00:00+02:00", entsoe))
        assertEquals(at("2026-09-22T13:20:00+02:00"), next("2026-09-22T13:12:00+02:00", entsoe))
        assertEquals(at("2026-09-22T13:35:00+02:00"), next("2026-09-22T13:20:00+02:00", entsoe))
        // After the attempts, every 30 minutes until tomorrow is in.
        assertEquals(at("2026-09-22T14:10:00+02:00"), next("2026-09-22T13:40:30+02:00", entsoe))
        // Finland's clock is an hour ahead, but the auction is Brussels': 13:00 Brussels, not 13:00 Helsinki.
        assertEquals(at("2026-09-22T14:00:00+03:00"), next("2026-09-22T10:00:00+03:00", entsoe))
    }

    @Test
    fun greatBritainIsTriedAroundSixteenUkTime() {
        assertEquals(at("2026-09-22T16:00:00+01:00"), next("2026-09-22T14:00:00+01:00", agile))
        assertEquals(at("2026-09-22T16:35:00+01:00"), next("2026-09-22T16:25:00+01:00", agile))
        assertEquals(at("2026-09-22T17:10:00+01:00"), next("2026-09-22T16:40:00+01:00", agile))
    }

    @Test
    fun spainPvpcIsTriedAroundTwentyFifteenMadridTime() {
        assertEquals(at("2026-09-22T20:15:00+02:00"), next("2026-09-22T14:00:00+02:00", pvpc))
        assertEquals(at("2026-09-22T20:25:00+02:00"), next("2026-09-22T20:15:00+02:00", pvpc))
    }

    @Test
    fun severalAreasTakeTheEarliestAttemptOfAny() {
        // Before 13:00 Brussels: the ENTSO-E attempt comes first.
        assertEquals(at("2026-09-22T13:00:00+02:00"), next("2026-09-22T09:00:00+02:00", entsoe, agile, pvpc))
        // After the ENTSO-E attempts the 30-minute retry runs, but Agile's own 16:00 is not missed.
        assertEquals(at("2026-09-22T16:00:00+01:00"), next("2026-09-22T16:45:00+02:00", entsoe, agile, pvpc))
        assertEquals(at("2026-09-22T20:15:00+02:00"), next("2026-09-22T19:50:00+02:00", entsoe, agile, pvpc))
        // A repeated publication is the same clock.
        assertEquals(next("2026-09-22T09:00:00+02:00", agile), next("2026-09-22T09:00:00+02:00", agile, agile))
    }

    @Test
    fun withTomorrowInHandTheNextCheckIsTomorrowsPublication() {
        assertEquals(at("2026-09-23T13:00:00+02:00"), next("2026-09-22T14:00:00+02:00", entsoe, available = true))
        assertEquals(at("2026-09-23T16:00:00+01:00"), next("2026-09-22T17:00:00+01:00", agile, available = true))
        assertEquals(at("2026-09-23T13:00:00+02:00"), next("2026-09-22T21:00:00+02:00", entsoe, pvpc, available = true))
    }

    @Test
    fun theAttemptsKeepTheirWallClockAcrossTheClockChanges() {
        // Autumn: the UK and Spain leave summer time on 25 October 2026.
        assertEquals(at("2026-10-25T16:00:00Z"), next("2026-10-24T17:00:00+01:00", agile, available = true))
        assertEquals(at("2026-10-25T16:00:00Z"), next("2026-10-25T09:00:00Z", agile))
        assertEquals(at("2026-10-25T19:15:00Z"), next("2026-10-24T21:00:00+02:00", pvpc, available = true))
        assertEquals(at("2026-10-25T12:00:00Z"), next("2026-10-25T01:30:00Z", entsoe))
        // Spring: both enter summer time on 29 March 2026.
        assertEquals(at("2026-03-29T15:00:00Z"), next("2026-03-28T17:00:00Z", agile, available = true))
        assertEquals(at("2026-03-29T18:15:00Z"), next("2026-03-29T00:30:00Z", pvpc))
        assertEquals(at("2026-03-29T18:25:00Z"), next("2026-03-29T18:15:00Z", pvpc))
    }
}
