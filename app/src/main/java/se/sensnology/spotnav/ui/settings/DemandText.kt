package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.dashboard.DemandBlock
import se.sensnology.spotnav.ha.dashboard.DemandComponent
import se.sensnology.spotnav.ha.dashboard.DemandMonth
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The demand charge and the import target in words, as the card's `demand-charge.ts` and `grid-tariff.ts` put them:
 * each part on one line ("Högsta timmen, Alla dagar 06–23, hela året"), its prices and what the site measured this
 * month beneath, why nothing is measured, and the running hour against the import target. [numbers] is the reader's
 * number locale, [dates] the locale dates are named in, [zone] the area's clock (`null`: no clock times).
 */
internal class DemandText(
    private val words: CardWords,
    private val numbers: Locale,
    private val dates: Locale,
    private val zone: ZoneId?
) {
    /** One row of the price section: a part ([index]) or the row that adds one (`null`), with lines beneath. */
    data class Row(val index: Int?, val label: String, val value: String, val lines: List<String>)

    /** A power in kW to one decimal ("7,0 kW"). */
    fun kw(kw: Double): String = "${fixed(kw, 1)} kW"

    fun fixed(value: Double, digits: Int): String = format(value, digits, digits)

    fun number(value: Double, digits: Int): String = format(value, 0, digits)

    private fun format(value: Double, min: Int, max: Int): String = NumberFormat.getNumberInstance(numbers).apply {
        minimumFractionDigits = min
        maximumFractionDigits = max
        roundingMode = RoundingMode.HALF_UP
    }.format(value)

    /** The hours' words, as the grid tariff's high-load hours are worded. */
    private val hours = GridTariffText(words, numbers)

    /** The days' words: every day, weekdays, Monday to Saturday. */
    fun daysText(days: String): String = hours.daysText(days)

    /** The months: "nov–mar", one month on its own, or "hela året" when they take in all twelve. */
    fun monthsText(window: HighLoadWindow): String = hours.monthsText(window)

    /** The hours on one line: "Vardagar 07–19, nov–mar", "Alla dagar 22–06, hela året". */
    fun windowText(window: HighLoadWindow): String = hours.windowText(window)

    fun topText(topN: Int): String = words.say("demand.top.$topN")

    /** What the part counts, in one line: "Högsta timmen, Alla dagar 06–23, hela året" or "…, Alla timmar". */
    fun componentText(component: DemandComponent): String = words.say(
        "demand.value",
        "shape" to topText(component.topN),
        "hours" to (component.window?.let(::windowText) ?: words.say("demand.allHours"))
    )

    /** The prices as "45 / 23 kr/kW (vinter / sommar)", or `null` when neither is given. */
    fun priceText(component: DemandComponent, unit: String?): String? {
        if (component.winter == null && component.summer == null) return null
        fun figure(value: Double?) = value?.let { number(it, 2) } ?: "–"
        return words.say("demand.prices", "winter" to figure(component.winter), "summer" to figure(component.summer), "unit" to (unit ?: ""))
            .trim().replace("  ", " ")
    }

    /** A day as the card names it: "tis 22 sep". */
    fun dateText(date: LocalDate): String = DateTimeFormatter.ofPattern("EEE d MMM", dates).format(date)

    /** A clock time on the area's clock, or "" without one. */
    fun clockText(iso: String): String {
        val at = runCatching { OffsetDateTime.parse(iso) }.getOrNull() ?: return ""
        val shown = zone?.let { at.atZoneSameInstant(it) } ?: return ""
        return DateTimeFormatter.ofPattern("HH:mm").format(shown)
    }

    /** What one part bills so far this month: the mean, then its peaks; "Ingen topp än den här månaden" before one. */
    fun monthText(month: DemandMonth): String {
        val bar = month.barKw ?: return words.say("demand.month.none")
        val peaks = month.peaks.joinToString(", ") { peak ->
            val day = runCatching { dateText(LocalDate.parse(peak.date)) }.getOrDefault("")
            "${listOf(day, clockText(peak.start)).filter { it.isNotEmpty() }.joinToString(" ")} · ${kw(peak.kw)}"
        }
        return words.say("demand.month", "kw" to kw(bar), "peaks" to peaks)
    }

    /** Why the site cannot measure, worded. */
    fun reasonText(reason: String?): String = words.say(REASON_KEYS[reason] ?: "demand.reason.unknown")

    /**
     * The demand charge's rows: "Effektavgift: Ingen" while there is no part; else one row per part (its prices and
     * this month's mean and peaks beneath) and, below three, a row that adds one. An older Home Assistant: none.
     */
    fun rows(demand: DemandBlock?): List<Row> {
        if (demand == null) return emptyList()
        val title = words.say("demand.title")
        if (demand.components.isEmpty()) return listOf(Row(null, title, words.say("settings.value.none"), emptyList()))
        val rows = demand.components.mapIndexed { index, component ->
            val lines = listOfNotNull(
                priceText(component, demand.priceUnit),
                demand.perComponent.getOrNull(index)?.let(::monthText)
            ).toMutableList()
            if (!demand.available && index == demand.components.lastIndex) lines += reasonText(demand.reason)
            Row(
                index,
                if (demand.components.size == 1) title else words.say("demand.part", "n" to (index + 1).toString()),
                componentText(component),
                lines
            )
        }
        return if (demand.components.size < DemandComponent.MAX) {
            rows + Row(null, words.say("demand.addPart"), words.say("demand.add"), emptyList())
        } else rows
    }

    /** The import target's value: "6,0 kW", or "Av" without one. */
    fun targetText(demand: DemandBlock?): String =
        demand?.importTargetKw?.let(::kw) ?: words.say("settings.value.off")

    /**
     * The running hour against the import target: "Den här timmen: 2,8 av 6,0 kW". `null` without a target; without a
     * reading yet, or where the site cannot measure, it says so.
     */
    fun hourLine(demand: DemandBlock?): String? {
        val target = demand?.importTargetKw ?: return null
        if (!demand.available) return reasonText(demand.reason)
        val hour = demand.hour ?: return words.say("site.importTarget.noHour")
        return words.say("site.importTarget.hour", "kw" to fixed(hour.kwSoFar, 1), "target" to kw(target))
    }

    companion object {
        private val REASON_KEYS = mapOf(
            "no_site" to "demand.reason.no_site",
            "needs_total_grid_power" to "demand.reason.needs_total_grid_power"
        )

        /** The locale a day is named in: English day-first ("Tue 22 Sep"), as the card writes it. */
        fun dateLocale(language: Locale): Locale = if (language.language == "en") Locale.UK else language

        /**
         * A price as typed: empty is unknown (`null`), a figure not below zero, else [Double.NaN] (the card's
         * `parsePrice`).
         */
        fun parsePrice(text: String): Double? {
            val trimmed = text.trim().replace(',', '.')
            if (trimmed.isEmpty()) return null
            val value = trimmed.toDoubleOrNull()
            return if (value != null && value.isFinite() && value >= 0) value else Double.NaN
        }
    }
}

/** The demand charge's words for this screen: its language and number locale, and the area's clock of [dashboard]. */
internal fun ViewScope.demandText(dashboard: Dashboard?): DemandText = DemandText(
    cardWords(),
    AppLanguageSettings.numberLocale(activity),
    DemandText.dateLocale(AppLanguageSettings.locale(activity)),
    dashboard?.market?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
)
