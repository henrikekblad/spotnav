package se.sensnology.spotnav.ui.common

import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chart.ChartOccurrence
import se.sensnology.spotnav.chart.ChartReadout
import java.time.format.DateTimeFormatter

/**
 * One readout's lines: one per value, so a day that repeats a wall clock shows both of its values
 * instead of one of them.
 */
internal fun ViewScope.readoutLines(selection: ChartReadout, unit: String): List<String> {
    val time = selection.time.format(DateTimeFormatter.ofPattern("HH:mm"))
    fun lines(day: Int, occurrences: List<ChartOccurrence>, repeats: Boolean) = occurrences.map { occurrence ->
        val price = String.format(AppLanguageSettings.numberLocale(context), "%.2f %s", occurrence.price, unit)
        if (repeats) t(R.string.chart_readout_line_offset, t(day), time, occurrence.offset.id, price)
        else t(R.string.chart_readout_line, t(day), time, price)
    }
    return lines(R.string.today, selection.today, selection.todayRepeats) +
        lines(R.string.tomorrow, selection.tomorrow, selection.tomorrowRepeats)
}

/** The chart's own words: what it is, what activating it does, and any selection. */
internal fun ViewScope.chartDescription(lines: List<String>): String =
    (listOf(t(R.string.chart_description), t(R.string.chart_activation_hint)) + lines).joinToString(". ")
