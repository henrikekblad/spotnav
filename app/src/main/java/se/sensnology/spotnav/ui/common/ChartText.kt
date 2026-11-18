package se.sensnology.spotnav.ui.common

import se.sensnology.spotnav.R
import se.sensnology.spotnav.chart.ChartOccurrence
import se.sensnology.spotnav.chart.ChartReadout
import se.sensnology.spotnav.chart.SunPoint
import se.sensnology.spotnav.prices.PriceFormat
import java.time.format.DateTimeFormatter

/**
 * One readout's lines: one per value, so a day that repeats a wall clock shows both of its values
 * instead of one of them. Each price per kWh in the unit [prices] shows.
 */
internal fun ViewScope.readoutLines(selection: ChartReadout, prices: PriceFormat, sun: SunPoint? = null): List<String> {
    val time = selection.time.format(DateTimeFormatter.ofPattern("HH:mm"))
    fun lines(day: Int, occurrences: List<ChartOccurrence>, repeats: Boolean) = occurrences.map { occurrence ->
        val price = prices.perKwh(occurrence.price)
        if (repeats) t(R.string.chart_readout_line_offset, t(day), time, occurrence.offset.id, price)
        else t(R.string.chart_readout_line, t(day), time, price)
    }
    val all = lines(R.string.today, selection.today, selection.todayRepeats) +
        lines(R.string.tomorrow, selection.tomorrow, selection.tomorrowRepeats)
    // The sun's figure for the selected hour, appended to the readout as the card does. It belongs to the first line:
    // today's on the day axis (see `PlanChartView.sunAtSelection`), the one interval on the from-now axis.
    if (sun == null || all.isEmpty()) return all
    val figure = java.text.DecimalFormat(
        "0.#", java.text.DecimalFormatSymbols.getInstance(se.sensnology.spotnav.app.AppLanguageSettings.numberLocale(context))
    ).format(sun.kw)
    val said = t(if (sun.measured) R.string.chart_readout_sun else R.string.chart_readout_sun_forecast, figure)
    return listOf("${all.first()} · $said") + all.drop(1)
}

/** The chart's own words: what it is, what activating it does, and any selection. */
internal fun ViewScope.chartDescription(lines: List<String>): String =
    (listOf(t(R.string.chart_description), t(R.string.chart_activation_hint)) + lines).joinToString(". ")
