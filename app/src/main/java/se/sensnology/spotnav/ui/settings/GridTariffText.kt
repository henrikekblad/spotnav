package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.dashboard.GridTariffFee
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.ui.common.CardWords
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * The grid tariff's rows beside the grid fee, as the card's `grid-tariff.ts` words them: the percentage of spot, the fee
 * in high-load hours and, while that fee is on or hours are stored, the hours ("Vardagar 07–19, nov–mar"). [numbers]
 * is the reader's number locale.
 */
internal class GridTariffText(private val words: CardWords, private val numbers: Locale) {
    enum class Key { PERCENT, HIGH, WINDOW }

    /** One row: what it is, its label and value, and whether it is locked because the price includes the grid fee. */
    data class Row(val key: Key, val label: String, val value: String, val included: Boolean)

    private fun hour(value: Int) = value.toString().padStart(2, '0')

    private fun month(number: Int) = words.say("gridFee.window.month.$number")

    /** The days' words: every day, weekdays, Monday to Saturday. */
    fun daysText(days: String): String = words.say(DAY_KEYS[days] ?: DAY_KEYS.getValue("all"))

    /** The months: "nov–mar", one month on its own, or "hela året" when they take in all twelve. */
    fun monthsText(window: HighLoadWindow): String = when {
        window.allYear -> words.say("gridFee.window.allYear")
        window.fromMonth == window.toMonth -> month(window.fromMonth)
        else -> "${month(window.fromMonth)}–${month(window.toMonth)}"
    }

    /** The hours on one line: "Vardagar 07–19, nov–mar", "Mån–lör 22–06, hela året". */
    fun windowText(window: HighLoadWindow): String = words.say(
        "gridFee.window.line",
        "days" to daysText(window.days),
        "hours" to "${hour(window.fromHour)}–${hour(window.toHour)}",
        "months" to monthsText(window)
    )

    /** A figure to two decimals at most, as the card's `formatNumber` writes it. */
    fun figure(value: Double): String = NumberFormat.getNumberInstance(numbers).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }.format(value)

    /** A fee's value: included in the price, off, or its figure with its unit. */
    fun feeText(fee: GridTariffFee): String = when {
        fee.policy == "included" -> words.say("market.included")
        fee.policy == "off" || fee.effectiveValue == null -> words.say("settings.value.off")
        else -> listOfNotNull(figure(fee.effectiveValue), fee.unit).joinToString(" ")
    }

    /**
     * The rows: the percentage of spot and the high-load fee always, the high-load hours only while that fee is on or
     * hours are stored (and never while the price already includes the grid fee). No block: no rows.
     */
    fun rows(block: DashboardGridTariff?): List<Row> {
        if (block == null) return emptyList()
        val included = block.included
        val rows = mutableListOf(
            Row(Key.PERCENT, words.say("gridFee.percent"), feeText(block.transferPercent), included),
            Row(Key.HIGH, words.say("gridFee.high"), feeText(block.transferHigh), included)
        )
        val window = block.highLoadWindow
        if (!included && (block.transferHigh.policy == "manual" || window != null)) {
            rows += Row(Key.WINDOW, words.say("gridFee.window"), window?.let(::windowText) ?: words.say("settings.value.unset"), false)
        }
        return rows
    }

    companion object {
        val DAY_KEYS: Map<String, String> = mapOf(
            "all" to "gridFee.window.everyDay",
            "weekdays" to "gridFee.window.weekdays",
            "mon_sat" to "gridFee.window.monSat"
        )
    }
}
