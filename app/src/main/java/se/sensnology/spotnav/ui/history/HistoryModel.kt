package se.sensnology.spotnav.ui.history

import se.sensnology.spotnav.ha.sessions.SessionDay
import se.sensnology.spotnav.ha.sessions.SessionTotals
import java.time.YearMonth
import java.util.Locale

/**
 * Which months the History view moves between. Home Assistant answers for the current month and the
 * 24 before it, so those are the bounds, and the current month is the one its own answer names --
 * never this phone's clock.
 */
internal object HistoryMonths {
    const val MONTHS_BACK = 24L

    fun earliest(current: YearMonth): YearMonth = current.minusMonths(MONTHS_BACK)

    /** The month the ‹ arrow goes to, or `null` at the earliest month Home Assistant answers for. */
    fun previous(shown: YearMonth, current: YearMonth): YearMonth? =
        shown.minusMonths(1).takeIf { it >= earliest(current) }

    /** The month the › arrow goes to, or `null` at the current month. */
    fun next(shown: YearMonth, current: YearMonth): YearMonth? =
        shown.plusMonths(1).takeIf { it <= current }

    /**
     * The picker's months, newest first: the months with data, the current month (so a month
     * without data is one tap away) and the month shown, kept inside the window.
     */
    fun choices(current: YearMonth, available: List<YearMonth>, shown: YearMonth): List<YearMonth> =
        (available + current + shown)
            .filter { it <= current && it >= earliest(current) }
            .distinct()
            .sortedDescending()
}

/** One bar of the month's chart. */
internal data class DayBar(
    val day: SessionDay,
    /** The bar's height, 0..1 of the month's biggest day. */
    val height: Double,
    /** Where the day's average price sits in the month, 0 (cheapest) to 1 (dearest); `null` without a price. */
    val price: Double?
)

internal object DayBars {
    /**
     * The month's bars, one per day. A day with no charge has height zero; a day with no price (or no
     * charge) has no colour fraction. When every priced day costs the same they sit in the middle.
     */
    fun of(days: List<SessionDay>): List<DayBar> {
        val biggest = days.maxOfOrNull { it.energyKwh }?.takeIf { it > 0.0 } ?: 0.0
        val prices = days.filter { it.energyKwh > 0.0 }.mapNotNull { it.averagePriceMinorPerKwh }
        val low = prices.minOrNull()
        val high = prices.maxOrNull()
        return days.map { day ->
            val price = day.averagePriceMinorPerKwh?.takeIf { day.energyKwh > 0.0 && low != null && high != null }?.let {
                if (high!! > low!!) ((it - low) / (high - low)).coerceIn(0.0, 1.0) else 0.5
            }
            DayBar(day, if (biggest > 0.0) (day.energyKwh / biggest).coerceIn(0.0, 1.0) else 0.0, price)
        }
    }

    /** The colour between [cheap] and [expensive] for a price fraction (a straight blend per channel). */
    fun colour(price: Double, cheap: Int, expensive: Int): Int {
        fun channel(shift: Int): Int {
            val from = (cheap shr shift) and 0xFF
            val to = (expensive shr shift) and 0xFF
            return (from + (to - from) * price.coerceIn(0.0, 1.0)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** The bar index under [x] in a chart [width] wide with [count] bars, or `null` outside them. */
    fun indexAt(x: Float, width: Float, count: Int): Int? {
        if (count <= 0 || width <= 0f || x < 0f || x > width) return null
        return (x / width * count).toInt().coerceIn(0, count - 1)
    }
}

/** The figures as the History view writes them: the screen's number locale, the market's own units. */
internal class HistoryFigures(private val locale: Locale, totals: SessionTotals) {
    private val major = totals.majorUnit ?: totals.currency
    private val minor = totals.minorUnit

    fun energy(kwh: Double): String = String.format(locale, "%.1f kWh", kwh)

    /** An amount in the major unit, `null` when there is none or no unit to name it in. */
    fun cost(amount: Double?): String? =
        if (amount == null || major == null) null else String.format(locale, "%.2f %s", amount, major)

    /** A signed estimate: the minus sign is the real one. */
    fun saving(amount: Double?): String? {
        if (amount == null || major == null) return null
        val sign = if (amount < 0.0) "−" else ""
        return "≈ " + sign + String.format(locale, "%.2f %s", Math.abs(amount), major)
    }

    fun price(perKwh: Double?): String? =
        if (perKwh == null || minor == null) null else String.format(locale, "%.1f %s/kWh", perKwh, minor)

    fun share(fraction: Double?): String? =
        fraction?.let { String.format(locale, "%d %%", Math.round(it * 100)) }
}
