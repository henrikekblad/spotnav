package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.StatusTone
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant

class WidgetStatusLineTest {
    private val captured = Instant.parse("2026-09-22T06:00:00Z").toEpochMilli()
    private fun stored(capturedAt: Long = captured) = StoredStatus.of(
        Dashboard.parse(HaFixtures.json("dashboard/target_soc_estimated.json")), capturedAt
    )

    @Test fun aFreshStatusIsShownAsIs() {
        val line = WidgetStatusLine.compose(stored(), "sv", Instant.ofEpochMilli(captured + 10 * 60_000))!!
        assertEquals("Planerat från 10:15 · 34,5 kWh · 82,92 kr · 17,3 mil", line.text)
        assertEquals(StatusTone.NORMAL, line.tone)
    }

    @Test fun aStatusOlderThanThirtyMinutesSaysHowOldItIs() {
        val atThreshold = WidgetStatusLine.compose(stored(), "en", Instant.ofEpochMilli(captured + 30 * 60_000))!!
        assertFalse(atThreshold.text.contains("updated"))
        val stale = WidgetStatusLine.compose(stored(), "en", Instant.ofEpochMilli(captured + 45 * 60_000))!!
        assertTrue(stale.text, stale.text.endsWith(" · updated 45 min ago"))
        val hours = WidgetStatusLine.compose(stored(), "sv", Instant.ofEpochMilli(captured + 3 * 3_600_000))!!
        assertTrue(hours.text, hours.text.endsWith(" · uppdaterad för 3 h sedan"))
    }

    @Test fun nothingStoredMeansNoLine() {
        assertNull(WidgetStatusLine.compose(null, "en", Instant.now()))
    }

    @Test fun aRefreshIsDueWhenNothingIsHeldWhenOldOrForced() {
        assertTrue(WidgetStatusLine.refreshDue(null, captured, force = false))
        assertFalse(WidgetStatusLine.refreshDue(stored(), captured + 60_000, force = false))
        assertTrue(WidgetStatusLine.refreshDue(stored(), captured + 6 * 60_000, force = false))
        assertTrue(WidgetStatusLine.refreshDue(stored(), captured + 60_000, force = true))
        // A clock that went backwards must not freeze a status for ever.
        assertTrue(WidgetStatusLine.refreshDue(stored(), captured - 1, force = false))
    }

    @Test fun aLongLineLosesWholePartsFromTheEndThenEllipsizes() {
        val measure = { text: String -> text.length.toFloat() }
        assertEquals("a · b · c", WidgetStatusLine.fit("a · b · c", 20f, measure))
        assertEquals("a · b", WidgetStatusLine.fit("a · b · c", 6f, measure))
        assertEquals("abc…", WidgetStatusLine.fit("abcdefghij · b", 4f, measure))
    }
}
