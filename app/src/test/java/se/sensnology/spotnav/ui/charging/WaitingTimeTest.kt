package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.planning.PriceWaiting
import java.time.OffsetDateTime

class WaitingTimeTest {
    private val now = OffsetDateTime.parse("2026-10-05T15:00:00+01:00")

    @Test fun aTimeStillAheadIsNamed() {
        assertFalse(WaitingTime.passed(PriceWaiting(now.plusMinutes(105), forLaterDay = true), now))
    }

    @Test fun aTimeReachedOrPassedIsNot() {
        assertTrue(WaitingTime.passed(PriceWaiting(now, forLaterDay = true), now))
        assertTrue(WaitingTime.passed(PriceWaiting(now.minusHours(2), forLaterDay = false), now))
    }
}
