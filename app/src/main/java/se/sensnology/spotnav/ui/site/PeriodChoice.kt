package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ui.common.CardWords
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The kind of period a history tab shows. [key] is Home Assistant's word for it. */
internal enum class HistoryPeriod(val key: String) { DAY("day"), MONTH("month"), YEAR("year") }

/**
 * The period the house and heat pages' history tabs show: its kind and a day inside it, `null` for the one running
 * now (so it stays today's across midnight). The Home Assistant card's own rules (`frontend/src/house-tabs.ts`,
 * `period-history.ts`): one step back or forward, never after today; another kind keeps the moment.
 */
internal data class PeriodChoice(val period: HistoryPeriod, val date: LocalDate? = null) {
    /** The first day of the period shown ([today] is the site's local date). */
    fun shown(today: LocalDate): LocalDate = Periods.start(period, date ?: today)

    /** [steps] periods from the shown one, never after today's (`null`: today's own). */
    fun step(today: LocalDate, steps: Int): PeriodChoice {
        val target = Periods.shift(period, shown(today), steps)
        return PeriodChoice(period, target.takeIf { it < Periods.start(period, today) })
    }

    /** Another kind: the same moment in it (today's when the shown period holds today). */
    fun kind(next: HistoryPeriod, today: LocalDate): PeriodChoice {
        val held = date ?: return PeriodChoice(next, null)
        val start = Periods.start(next, held)
        return PeriodChoice(next, start.takeIf { it < Periods.start(next, today) })
    }

    /** The period that holds [day], as a choice: today's own period is `null`. */
    fun picked(day: LocalDate, today: LocalDate): PeriodChoice {
        val start = Periods.start(period, day)
        return PeriodChoice(period, start.takeIf { it < Periods.start(period, today) })
    }

    fun canStepBack(today: LocalDate): Boolean = Periods.shift(period, shown(today), -1) >= Periods.earliest(period, today)

    fun canStepForward(today: LocalDate): Boolean = Periods.shift(period, shown(today), 1) <= today
}

internal object Periods {
    /** How far back the pickers offer (Home Assistant answers ten years back). */
    const val PICKER_MONTHS = 36
    const val PICKER_YEARS = 10

    /** The first day of the period that holds [date]. */
    fun start(period: HistoryPeriod, date: LocalDate): LocalDate = when (period) {
        HistoryPeriod.DAY -> date
        HistoryPeriod.MONTH -> date.withDayOfMonth(1)
        HistoryPeriod.YEAR -> date.withDayOfYear(1)
    }

    /** The first day of the period [steps] periods from the one holding [date]. */
    fun shift(period: HistoryPeriod, date: LocalDate, steps: Int): LocalDate = when (period) {
        HistoryPeriod.DAY -> date.plusDays(steps.toLong())
        HistoryPeriod.MONTH -> date.withDayOfMonth(1).plusMonths(steps.toLong())
        HistoryPeriod.YEAR -> date.withDayOfYear(1).plusYears(steps.toLong())
    }

    /** The first period of a kind that can be shown. */
    fun earliest(period: HistoryPeriod, today: LocalDate): LocalDate =
        start(period, shift(HistoryPeriod.YEAR, today, -PICKER_YEARS))

    /** The months the month picker offers, newest first: back [PICKER_MONTHS] from today's, and the one shown. */
    fun months(today: LocalDate, shown: LocalDate): List<LocalDate> {
        val first = start(HistoryPeriod.MONTH, today)
        val months = (0 until PICKER_MONTHS).map { shift(HistoryPeriod.MONTH, first, -it) }.toMutableSet()
        months.add(start(HistoryPeriod.MONTH, shown))
        return months.filter { it <= first }.sortedDescending()
    }

    fun years(today: LocalDate, shown: LocalDate): List<LocalDate> {
        val first = start(HistoryPeriod.YEAR, today)
        val years = (0..PICKER_YEARS).map { shift(HistoryPeriod.YEAR, first, -it) }.toMutableSet()
        years.add(start(HistoryPeriod.YEAR, shown))
        return years.sortedDescending()
    }

    /**
     * The period in words, the picker's label: "I dag", "I går", a date ("ons 11 nov"), a month ("nov 2026"), a
     * year; in the reader's language.
     */
    fun label(words: CardWords, locale: Locale, choice: PeriodChoice, today: LocalDate): String {
        val shown = choice.shown(today)
        return when (choice.period) {
            HistoryPeriod.DAY -> when (shown) {
                today -> words.say("house.picker.today")
                today.minusDays(1) -> words.say("house.picker.yesterday")
                else -> clean(DateTimeFormatter.ofPattern("EEE d MMM", locale).format(shown))
            }
            HistoryPeriod.MONTH -> clean(DateTimeFormatter.ofPattern("LLL yyyy", locale).format(shown))
            HistoryPeriod.YEAR -> shown.year.toString()
        }
    }

    /** A period as a list names it: a whole month ("november 2026"), a year. */
    fun optionLabel(locale: Locale, period: HistoryPeriod, first: LocalDate): String = when (period) {
        HistoryPeriod.DAY -> clean(DateTimeFormatter.ofPattern("EEE d MMM", locale).format(first))
        HistoryPeriod.MONTH -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(first)
        HistoryPeriod.YEAR -> first.year.toString()
    }

    /** Short names without the full stops some languages' abbreviations carry ("nov." is "nov"). */
    private fun clean(text: String): String = text.replace(".", "")
}
