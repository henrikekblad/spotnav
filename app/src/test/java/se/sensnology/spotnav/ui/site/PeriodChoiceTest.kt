package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.CardStringsXml
import java.time.LocalDate
import java.util.Locale

/** The period picker's rules, as the Home Assistant card's (`frontend/test/house-tabs.test.ts`, "the period picker"). */
class PeriodChoiceTest {
    private val today = LocalDate.parse("2026-11-13")
    private val day = PeriodChoice(HistoryPeriod.DAY)

    private fun on(period: HistoryPeriod, date: String?) = PeriodChoice(period, date?.let(LocalDate::parse))

    @Test fun aStepIsOnePeriodBackOrForwardNeverPastToday() {
        assertEquals(on(HistoryPeriod.DAY, "2026-11-12"), day.step(today, -1))
        assertEquals(day, on(HistoryPeriod.DAY, "2026-11-12").step(today, 1))
        assertEquals(day, on(HistoryPeriod.DAY, "2026-11-12").step(today, 5))
        assertEquals(on(HistoryPeriod.MONTH, "2026-10-01"), on(HistoryPeriod.MONTH, null).step(today, -1))
        assertEquals(on(HistoryPeriod.YEAR, "2025-01-01"), on(HistoryPeriod.YEAR, null).step(today, -1))
        assertEquals(on(HistoryPeriod.MONTH, "2025-12-01"), on(HistoryPeriod.MONTH, "2026-01-01").step(today, -1))
        // Across a month's end and a leap day.
        assertEquals(on(HistoryPeriod.DAY, "2026-10-31"), on(HistoryPeriod.DAY, "2026-11-01").step(today, -1))
        assertEquals(on(HistoryPeriod.DAY, "2024-02-29"), on(HistoryPeriod.DAY, "2024-03-01").step(today, -1))
    }

    @Test fun anotherKindKeepsTheMoment() {
        assertEquals(on(HistoryPeriod.MONTH, "2026-10-01"), on(HistoryPeriod.DAY, "2026-10-05").kind(HistoryPeriod.MONTH, today))
        // The shown period holds today: today's own, so it stays today's tomorrow.
        assertEquals(on(HistoryPeriod.MONTH, null), on(HistoryPeriod.DAY, "2026-11-05").kind(HistoryPeriod.MONTH, today))
        assertEquals(on(HistoryPeriod.YEAR, null), day.kind(HistoryPeriod.YEAR, today))
        assertEquals(on(HistoryPeriod.DAY, "2025-01-01"), on(HistoryPeriod.YEAR, "2025-01-01").kind(HistoryPeriod.DAY, today))
    }

    @Test fun nothingAfterTodayIsOfferedAndNothingBeforeTenYearsBack() {
        assertFalse(day.canStepForward(today))
        assertTrue(day.canStepBack(today))
        assertTrue(on(HistoryPeriod.DAY, "2026-11-12").canStepForward(today))
        assertFalse(on(HistoryPeriod.MONTH, null).canStepForward(today))
        assertTrue(on(HistoryPeriod.MONTH, "2026-10-01").canStepForward(today))
        assertFalse(on(HistoryPeriod.YEAR, null).canStepForward(today))
        assertEquals(LocalDate.parse("2016-01-01"), Periods.earliest(HistoryPeriod.DAY, today))
        assertFalse(on(HistoryPeriod.DAY, "2016-01-01").canStepBack(today))
        assertTrue(on(HistoryPeriod.DAY, "2016-01-02").canStepBack(today))
        assertFalse(on(HistoryPeriod.YEAR, "2016-01-01").canStepBack(today))
        // A day picked by hand: today is today's own, a day in a month is that month.
        assertEquals(day, day.picked(today, today))
        assertEquals(on(HistoryPeriod.DAY, "2026-11-01"), day.picked(LocalDate.parse("2026-11-01"), today))
        assertEquals(on(HistoryPeriod.MONTH, "2026-03-01"), on(HistoryPeriod.MONTH, null).picked(LocalDate.parse("2026-03-17"), today))
        assertEquals(on(HistoryPeriod.MONTH, null), on(HistoryPeriod.MONTH, "2026-03-01").picked(LocalDate.parse("2026-11-01"), today))
    }

    @Test fun thePickerListsTheMonthsAndYearsNewestFirst() {
        val months = Periods.months(today, LocalDate.parse("2026-11-01"))
        assertEquals(36, months.size)
        assertEquals(LocalDate.parse("2026-11-01"), months.first())
        assertEquals(LocalDate.parse("2023-12-01"), months.last())
        // A month shown from further back is in the list too.
        val old = Periods.months(today, LocalDate.parse("2020-05-01"))
        assertEquals(37, old.size)
        assertEquals(LocalDate.parse("2020-05-01"), old.last())
        val years = Periods.years(today, LocalDate.parse("2026-01-01"))
        assertEquals((2026 downTo 2016).toList(), years.map { it.year })
    }

    @Test fun theLabelSaysThePeriodInTheReadersLanguage() {
        fun label(language: String, choice: PeriodChoice) = Periods.label(CardStringsXml.words(language), Locale.forLanguageTag(language), choice, today)
        assertEquals("I dag", label("sv", day))
        assertEquals("I går", label("sv", on(HistoryPeriod.DAY, "2026-11-12")))
        assertEquals("ons 11 nov", label("sv", on(HistoryPeriod.DAY, "2026-11-11")))
        assertEquals("Wed 11 Nov", label("en", on(HistoryPeriod.DAY, "2026-11-11")))
        assertEquals("nov 2026", label("sv", on(HistoryPeriod.MONTH, null)))
        assertEquals("2025", label("sv", on(HistoryPeriod.YEAR, "2025-01-01")))
        assertEquals("Today", label("en", day))
        assertEquals("november 2026", Periods.optionLabel(Locale.forLanguageTag("sv"), HistoryPeriod.MONTH, LocalDate.parse("2026-11-01")))
        for (language in CardStringsXml.LANGUAGES) {
            val words = CardStringsXml.words(language)
            for (period in HistoryPeriod.entries) {
                for (key in listOf("hist.period", "hist.previous", "hist.next", "hist.pick")) {
                    assertTrue("$language $key.${period.key}", words.say("$key.${period.key}").isNotBlank())
                }
            }
            assertEquals("$language: both parts told", 2, words.say("house.picker.through", "label" to "A", "time" to "17:00").split("A", "17:00").size - 1)
        }
    }
}
