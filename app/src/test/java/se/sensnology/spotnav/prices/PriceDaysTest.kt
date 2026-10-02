package se.sensnology.spotnav.prices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class PriceDaysTest {
    private fun result(vararg starts: String) = PriceResult(
        today = starts.map { PricePoint(OffsetDateTime.parse(it), 1.0) },
        tomorrow = emptyList(),
        fetchedAt = 0L
    )

    @Test fun aResultShowsTheDateOfItsTodayRows() {
        val held = result("2026-09-22T00:00:00+02:00")
        assertTrue(PriceDays.showsDate(held, LocalDate.parse("2026-09-22")))
        assertFalse(PriceDays.showsDate(held, LocalDate.parse("2026-09-23")))
    }

    @Test fun aResultWithNoTodayRowsShowsNoDate() {
        assertFalse(PriceDays.showsDate(result(), LocalDate.parse("2026-09-22")))
    }
}
