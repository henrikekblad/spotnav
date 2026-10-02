package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class DayBoundaryTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")

    @Test fun beforeMidnightTheBoundaryIsThatMidnight() {
        val now = Instant.parse("2026-09-22T21:59:00Z") // 23:59 +02:00
        assertEquals(Instant.parse("2026-09-22T22:00:00Z"), DayBoundary.nextMidnight(now, stockholm))
    }

    @Test fun atMidnightTheBoundaryIsTheNextOne() {
        val now = Instant.parse("2026-09-22T22:00:00Z")
        assertEquals(Instant.parse("2026-09-23T22:00:00Z"), DayBoundary.nextMidnight(now, stockholm))
    }

    @Test fun theShortSpringDayEndsAfterTwentyThreeHours() {
        // 2026-03-29: clocks go forward at 02:00, so the day runs 23 hours from 23:00Z on the 28th.
        val start = Instant.parse("2026-03-28T23:00:00Z")
        val end = DayBoundary.nextMidnight(start, stockholm)
        assertEquals(Duration.ofHours(23), Duration.between(start, end))
        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), end)
    }

    @Test fun theLongAutumnDayEndsAfterTwentyFiveHours() {
        // 2026-10-25: clocks go back at 03:00, so the day runs 25 hours from 22:00Z on the 24th.
        val start = Instant.parse("2026-10-24T22:00:00Z")
        val end = DayBoundary.nextMidnight(start, stockholm)
        assertEquals(Duration.ofHours(25), Duration.between(start, end))
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), end)
    }

    @Test fun theEarliestZoneWins() {
        val now = Instant.parse("2026-09-22T21:30:00Z")
        val lisbon = ZoneId.of("Europe/Lisbon")
        assertEquals(
            Instant.parse("2026-09-22T22:00:00Z"),
            DayBoundary.nextMidnight(now, listOf(lisbon, stockholm))
        )
        assertNull(DayBoundary.nextMidnight(now, emptyList<ZoneId>()))
    }
}
