package se.sensnology.spotnav.chart

import java.time.Instant
import kotlin.math.max

/** One hour label under the chart: the tick it belongs to and what it reads. */
internal data class ChartAxisLabel(val tick: ChartTick, val text: String)

/**
 * The chart's hour labels, as in the Home Assistant card's `timeAxisLabels`: two-digit hours without
 * minutes every three hours ("00 03 ... 21 24" on Day, the right edge reading "24"), a midnight inside
 * the From now range named by its day ("12 Mon", "mån 12") instead of "00". Where any two would touch at
 * the drawn width, the six-hour ticks are labelled instead (same texts); on a plot too narrow even for
 * those, such as a small widget, [ChartTickFit] thins them further. Android-free.
 *
 * A label's width is estimated as the card does (characters x [CHAR_EM] em, at least [GAP_EM] em between
 * labels), or measured when that is wider, so a wide day cue never overlaps its neighbours.
 */
internal object ChartAxisLabels {
    /** A label's estimated width per character, in em: two digits, or a short weekday and date. */
    const val CHAR_EM = 0.6f

    /** The least room between two labels, in em. */
    const val GAP_EM = 0.3f

    /** What [tick] reads: its day ([dayCue]) at a midnight inside the range, else its two-digit hour. */
    fun text(tick: ChartTick, dayCue: (Instant) -> String): String =
        if (tick.dayStart && tick.instant != null) dayCue(tick.instant) else tick.hour.toString().padStart(2, '0')

    /** The card's estimate of a label's width at [fontPx]. */
    fun estimatedWidth(text: String, fontPx: Float): Float = text.length * CHAR_EM * fontPx

    /**
     * The labels [axis] shows on a plot whose minutes [xAt] turns into pixels, at [fontPx]. [measure] is
     * the drawn width of a text, when known; the estimate stands in for it otherwise.
     */
    fun of(
        axis: ChartTimeAxis,
        xAt: (Float) -> Float,
        fontPx: Float,
        dayCue: (Instant) -> String,
        measure: (String) -> Float = { 0f }
    ): List<ChartAxisLabel> {
        val gap = GAP_EM * fontPx
        fun width(tick: ChartTick): Float {
            val text = text(tick, dayCue)
            return max(estimatedWidth(text, fontPx), measure(text))
        }
        val dense = axis.labelTicks()
        val chosen = if (ChartTickFit.fits(dense, axis.spanMinutes, xAt, ::width, gap)) dense
            else ChartTickFit.fit(axis.ticks(), axis.spanMinutes, xAt, ::width, gap)
        return chosen.map { ChartAxisLabel(it, text(it, dayCue)) }
    }
}
