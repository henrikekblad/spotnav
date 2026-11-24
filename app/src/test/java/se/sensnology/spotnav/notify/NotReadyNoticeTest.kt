package se.sensnology.spotnav.notify

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.ha.house.NotReady
import se.sensnology.spotnav.ha.house.PlanRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.time.ZoneId

/** When the phone's own "Not ready in time" notice for a site is posted, kept or taken off. */
class NotReadyNoticeTest {
    private val zone = ZoneId.of("Europe/Stockholm")

    private fun block(): NotReady = (PlanRead.decode(HaFixtures.json("plan/not_ready.json")) as SiteRead.Read).value.notReady!!

    private val day = Instant.parse("2026-11-16T15:00:00Z")

    @Test fun aBlockThatAsksIsPostedOnceForItsDeadlines() {
        val key = NotReadyNotice.key(block())
        assertEquals("charger_garage@2026-11-17T06:00:00Z,spa@2026-11-17T18:00:00Z", key)
        assertEquals(NotReadyNotice.Step.Post(key), NotReadyNotice.step(block(), null, day, zone))
        assertEquals(NotReadyNotice.Step.Keep, NotReadyNotice.step(block(), key, day, zone))
        // A new deadline joining tells it again.
        assertEquals(NotReadyNotice.Step.Post(key), NotReadyNotice.step(block(), "charger_garage@2026-11-17T06:00:00Z", day, zone))
    }

    @Test fun neverAtNightAndTakenOffOnceNothingAsks() {
        assertEquals(NotReadyNotice.Step.Wait, NotReadyNotice.step(block(), null, Instant.parse("2026-11-16T21:30:00Z"), zone))
        assertEquals(NotReadyNotice.Step.Wait, NotReadyNotice.step(block(), null, Instant.parse("2026-11-17T05:59:00Z"), zone))
        assertEquals(NotReadyNotice.Step.Post(NotReadyNotice.key(block())), NotReadyNotice.step(block(), null, Instant.parse("2026-11-17T06:00:00Z"), zone))
        assertEquals(NotReadyNotice.Step.Retire, NotReadyNotice.step(null, "x", day, zone))
        assertEquals(NotReadyNotice.Step.Keep, NotReadyNotice.step(null, null, day, zone))
        val answered = block().let { b -> b.copy(items = b.items.map { it.copy(chosen = NotReady.TAKES_WHAT_FITS) }) }
        assertEquals(NotReadyNotice.Step.Retire, NotReadyNotice.step(answered, "x", day, zone))
    }
}
