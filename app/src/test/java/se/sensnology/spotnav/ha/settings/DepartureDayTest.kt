package se.sensnology.spotnav.ha.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class DepartureDayTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private fun days(iso: String, zone: ZoneId = stockholm) = DepartureDays.of(zone, Instant.parse(iso))!!

    @Test fun theDaysRunFromTodayInTheAreasZoneForAWeek() {
        // 22:30 UTC is already the 23rd in Stockholm (+02:00).
        val days = days("2026-09-22T22:30:00Z")
        assertEquals(LocalDate.of(2026, 9, 23), days.today)
        assertEquals(LocalDate.of(2026, 9, 30), days.max)
        assertTrue(days.isPast(LocalDate.of(2026, 9, 22)))
        assertFalse(days.isPast(LocalDate.of(2026, 9, 23)))
        assertNull(DepartureDays.of(null, Instant.parse("2026-09-22T22:30:00Z")))
    }

    @Test fun aDateStartsAtTheNextOccurrenceOfTheTime() {
        // 10:00 in Stockholm: 12:00 is still ahead today, 08:00 is not, and 10:00 itself is not.
        val days = days("2026-09-22T08:00:00Z")
        assertEquals(LocalDate.of(2026, 9, 22), days.nextOccurrence(LocalTime.of(12, 0)))
        assertEquals(LocalDate.of(2026, 9, 23), days.nextOccurrence(LocalTime.of(8, 0)))
        assertEquals(LocalDate.of(2026, 9, 23), days.nextOccurrence(LocalTime.of(10, 0)))
    }

    @Test fun theMadridClockGivesPortugalItsOwnDay() {
        // 23:30 in Lisbon on the 22nd is 00:30 on the 23rd in Madrid.
        val days = days("2026-09-22T22:30:00Z", ZoneId.of("Europe/Madrid"))
        assertEquals(LocalDate.of(2026, 9, 23), days.today)
    }

    @Test fun aDatedDepartureNamesTodayTomorrowOrTheDate() {
        val today = LocalDate.of(2026, 9, 23)
        fun text(date: LocalDate?, locale: Locale = Locale.ENGLISH) =
            DepartureText.text(date, "08:00", today, locale, "today", "tomorrow")
        assertEquals("08:00", text(null))
        assertEquals("today 08:00", text(today))
        assertEquals("tomorrow 08:00", text(today.plusDays(1)))
        assertTrue(text(LocalDate.of(2026, 9, 27)).startsWith("Sun 27 Sep"))
        assertTrue(text(LocalDate.of(2026, 9, 27)).endsWith(" 08:00"))
        // A day that has gone by is not shown: planning ignores it.
        assertEquals("08:00", text(today.minusDays(1)))
        assertEquals("idag 08:00", DepartureText.text(today, "08:00", today, Locale.forLanguageTag("sv"), "idag", "imorgon"))
        // With no known today the date is just spelled.
        assertTrue(DepartureText.text(today, "08:00", null, Locale.ENGLISH, "today", "tomorrow").startsWith("Wed 23 Sep"))
    }
}
