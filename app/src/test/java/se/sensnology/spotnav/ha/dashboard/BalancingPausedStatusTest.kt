package se.sensnology.spotnav.ha.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.WebhookReads
import java.time.Instant
import java.time.ZoneId

/** Load balancing paused the plan's charge inside its window: why, and when it is tried again, as the card says it. */
class BalancingPausedStatusTest {
    private val now = Instant.parse("2026-10-09T02:20:00Z")

    private fun say(language: String, retryAt: String?, cause: String) = HaStatusText.line(
        StatusLine("balancing_paused", mapOf("retry_at" to retryAt, "cause" to cause)),
        StatusFormat(language, ZoneId.of("Europe/Stockholm"), "SEK", "kr"),
        now
    )

    @Test fun theAppAsksForTheLineInEveryRequest() {
        assertTrue(WebhookReads.FIELDS.contains("balancing_paused"))
    }

    @Test fun itSaysWhyAndWhenInEveryLanguage() {
        val at = "2026-10-09T02:30:22+00:00"
        assertEquals("Pausad – huset använder hela säkringen; försöker igen kl. 04:30", say("sv", at, "house_consumption"))
        assertEquals(
            "Pausad – hemmabatteriet laddar från nätet och fyller huvudsäkringen; försöker igen kl. 04:30",
            say("sv", at, "battery_shares_fuse")
        )
        assertEquals("Paused – the house is using the whole fuse; trying again when there is room", say("en", null, "house_consumption"))
        for (language in HaStatusWording.LANGUAGES) {
            val house = say(language, at, "house_consumption")
            val battery = say(language, at, "battery_shares_fuse")
            val room = say(language, null, "house_consumption")
            assertTrue("$language: $house", Regex("04[:.]30").containsMatchIn(house) && !house.contains('{'))
            assertNotEquals(language, house, battery)
            assertFalse("$language: $room", Regex("04[:.]30").containsMatchIn(room) || room.contains('{'))
        }
    }
}
