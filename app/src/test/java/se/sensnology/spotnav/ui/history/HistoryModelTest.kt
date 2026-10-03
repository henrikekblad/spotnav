package se.sensnology.spotnav.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SessionsOutcome
import se.sensnology.spotnav.ha.client.SessionsRead
import se.sensnology.spotnav.ha.sessions.SessionDay
import se.sensnology.spotnav.ha.sessions.SessionTotals
import se.sensnology.spotnav.testing.HaFixtures
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class HistoryModelTest {
    private val sep = YearMonth.of(2026, 9)

    @Test fun theArrowsStayInsideTheMonthsHomeAssistantAnswersFor() {
        assertEquals(YearMonth.of(2026, 8), HistoryMonths.previous(sep, sep))
        assertNull("nothing after the current month", HistoryMonths.next(sep, sep))
        assertEquals(sep, HistoryMonths.next(YearMonth.of(2026, 8), sep))
        // 24 months back is the earliest, across a year boundary.
        val earliest = YearMonth.of(2024, 9)
        assertEquals(earliest, HistoryMonths.earliest(sep))
        assertNull(HistoryMonths.previous(earliest, sep))
        assertEquals(earliest, HistoryMonths.previous(YearMonth.of(2024, 10), sep))
        assertEquals(YearMonth.of(2025, 12), HistoryMonths.previous(YearMonth.of(2026, 1), sep))
    }

    @Test fun thePickerOffersTheMonthsWithDataTheCurrentMonthAndTheOneShown() {
        val available = listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 5))
        assertEquals(
            listOf(sep, YearMonth.of(2026, 8), YearMonth.of(2026, 7), YearMonth.of(2026, 5)),
            HistoryMonths.choices(sep, available, YearMonth.of(2026, 7))
        )
        // Nothing outside the window, and nothing twice.
        assertEquals(
            listOf(sep, YearMonth.of(2026, 8)),
            HistoryMonths.choices(sep, listOf(YearMonth.of(2026, 8), YearMonth.of(2020, 1), YearMonth.of(2026, 10), sep), sep)
        )
    }

    private fun day(n: Int, kwh: Double, price: Double?) =
        SessionDay(LocalDate.of(2026, 9, n), kwh, price?.let { it * kwh / 100 }, price, null, if (kwh > 0) 1 else 0)

    @Test fun theBarsAreScaledToTheBiggestDayAndColouredByPriceInTheMonth() {
        val bars = DayBars.of(listOf(day(1, 0.0, null), day(2, 10.0, 20.0), day(3, 20.0, 60.0), day(4, 5.0, 40.0), day(5, 8.0, null)))
        assertEquals(listOf(0.0, 0.5, 1.0, 0.25, 0.4), bars.map { it.height })
        // Cheapest 0, dearest 1, in between linear; no charge or no price has no colour fraction.
        assertEquals(listOf(null, 0.0, 1.0, 0.5, null), bars.map { it.price })
    }

    @Test fun aMonthWithOnePriceOrNoChargeDoesNotDivideByZero() {
        val same = DayBars.of(listOf(day(1, 4.0, 30.0), day(2, 8.0, 30.0)))
        assertEquals(listOf(0.5, 0.5), same.map { it.price })
        val none = DayBars.of(listOf(day(1, 0.0, null), day(2, 0.0, null)))
        assertEquals(listOf(0.0, 0.0), none.map { it.height })
        assertEquals(listOf<Double?>(null, null), none.map { it.price })
        assertTrue(DayBars.of(emptyList()).isEmpty())
    }

    @Test fun theColourBlendsFromCheapToDear() {
        val cheap = 0xFF43C887.toInt()
        val dear = 0xFFFF625F.toInt()
        assertEquals(cheap, DayBars.colour(0.0, cheap, dear))
        assertEquals(dear, DayBars.colour(1.0, cheap, dear))
        assertEquals(0xFFA1 shl 16 or (0x95 shl 8) or 0x73 or (0xFF shl 24), DayBars.colour(0.5, cheap, dear))
        assertEquals(cheap, DayBars.colour(-3.0, cheap, dear))
    }

    @Test fun aTouchFindsTheBarUnderIt() {
        assertEquals(0, DayBars.indexAt(0f, 300f, 30))
        assertEquals(15, DayBars.indexAt(150f, 300f, 30))
        assertEquals(29, DayBars.indexAt(300f, 300f, 30))
        assertNull(DayBars.indexAt(-1f, 300f, 30))
        assertNull(DayBars.indexAt(301f, 300f, 30))
        assertNull(DayBars.indexAt(10f, 300f, 0))
    }

    @Test fun theFixturesMonthMakesAChartWithABarForEveryDay() {
        val body = HaFixtures.json("sessions/get_sessions.json").put("ok", true).toString()
        val month = (SessionsRead.month(200, body) as SessionsOutcome.Loaded).value
        val bars = DayBars.of(month.days)
        assertEquals(30, bars.size)
        assertEquals(1.0, bars.maxOf { it.height }, 0.0)
        // The 2 September charge is the cheap solar one and the 14th the dear one.
        assertEquals(0.0, bars.first { it.day.date.dayOfMonth == 2 }.price!!, 0.0)
        assertEquals(1.0, bars.first { it.day.date.dayOfMonth == 14 }.price!!, 0.0)
        // The estimated day has no price, so no colour.
        assertNull(bars.first { it.day.date.dayOfMonth == 20 }.price)
    }

    private fun totals(major: String?, minor: String?) = SessionTotals(
        "2026-09", 1, 1.0, 1.0, "SEK", major, minor, 1.0, null, null, false
    )

    @Test fun theFiguresAreWrittenInTheScreensLocaleAndTheMarketsUnits() {
        val sv = HistoryFigures(Locale.forLanguageTag("sv"), totals("kr", "öre"))
        assertEquals("65,7 kWh", sv.energy(65.7))
        assertEquals("24,95 kr", sv.cost(24.95))
        assertEquals("44,4 öre/kWh", sv.price(44.395))
        assertEquals("49 %", sv.share(0.487))
        assertEquals("≈ 6,35 kr", sv.saving(6.35))
        assertEquals("≈ −1,30 kr", sv.saving(-1.3))
        val en = HistoryFigures(Locale.UK, totals("kr", "öre"))
        assertEquals("24.95 kr", en.cost(24.95))
        // No amount, or no unit to name it in, is no text -- never a bare number.
        assertNull(en.cost(null))
        assertNull(en.price(null))
        assertNull(en.share(null))
        assertNull(HistoryFigures(Locale.UK, totals(null, null).copy(currency = null)).cost(4.0))
        assertEquals("4.00 SEK", HistoryFigures(Locale.UK, totals(null, null)).cost(4.0))
    }
}
