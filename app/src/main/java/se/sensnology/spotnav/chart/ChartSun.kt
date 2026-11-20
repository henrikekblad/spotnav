package se.sensnology.spotnav.chart

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.ha.dashboard.DashboardSun
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One span of sun on the chart's own time axis (an hour, or a measured quarter hour): where it lies in the axis's
 * minutes (see [ChartTimeAxis]), its mean power in kW, and whether it is measured production (up to now) rather than
 * the forecast. It is drawn at its [minute], the middle of the span.
 */
internal data class SunPoint(val fromMinute: Float, val toMinute: Float, val kw: Double, val measured: Boolean) {
    val minute: Float get() = (fromMinute + toMinute) / 2f
}

/**
 * The sun in the price chart, as the Home Assistant card's `chart-sun.ts` makes it: the dashboard's `sun` block
 * (measured before now, the plan's corrected forecast after) as points on the chart's time axis. The sun has its own
 * kW scale, never the price's: the site's peak production (or, with none known, the day's own tallest hour) reaches
 * [ChartSuns.HEIGHT_SHARE] of the plot, so a dull day looks small and the price marks and their axis are untouched.
 */
internal data class ChartSun(
    /** Ascending by time, only what the chart's axis shows. */
    val points: List<SunPoint>,
    /** The scale's top in kW: the site's peak, or the tallest point where it is higher or no peak is known; at least a small floor. */
    val maxKw: Double,
    /** The site's peak production in kW when the block states one: the chart names it. */
    val peakKw: Double?
) {
    /** Whether any of it is the forecast. */
    val hasForecast: Boolean get() = points.any { !it.measured }

    /** The span the sun has under an axis [minute] (a price mark's), or `null`. */
    fun at(minute: Float): SunPoint? = points.firstOrNull { it.fromMinute <= minute && minute < it.toMinute }
}

/** Pure: no clock of its own, no Android. */
internal object ChartSuns {
    /** How much of the plot's height the tallest sun reaches. */
    const val HEIGHT_SHARE = 0.8f

    /** The scale's floor in kW: a day of 0.1 kW is drawn small, not as the whole plot. */
    private const val MIN_MAX_KW = 0.5

    private const val HOUR_MINUTES = 60L
    private const val QUARTER_MINUTES = 15L
    private const val HOUR_SECONDS = 3600L

    /**
     * The part of [block] the chart shows on [axis]. On the wall-clock axis (today and tomorrow overlaid) only
     * [today]'s sun, so tomorrow's never lands on today's hours; on the real-time range everything in view. The
     * measured quarters replace the measured hours they cover, and the forecast starts where the last quarter ends.
     * `null` without a block or when nothing of it is on the axis.
     */
    fun series(block: DashboardSun?, axis: ChartTimeAxis, zone: ZoneId, today: LocalDate): ChartSun? {
        block ?: return null
        val points = ArrayList<SunPoint>()
        fun add(start: Instant, minutes: Long, kw: Double, measured: Boolean) {
            val end = start.plus(Duration.ofMinutes(minutes))
            when (axis) {
                is ChartTimeAxis.Range -> {
                    if (!end.isAfter(axis.start) || !start.isBefore(axis.end)) return
                    points += SunPoint(axis.minuteOf(start), axis.minuteOf(end), kw, measured)
                }
                ChartTimeAxis.WallClock -> {
                    val local = start.atZone(zone)
                    if (local.toLocalDate() != today) return
                    val from = local.hour * 60f + local.minute
                    points += SunPoint(from, from + minutes, kw, measured)
                }
            }
        }
        val covered = block.quarters.map { it.start.epochSecond / HOUR_SECONDS }.toSet()
        val measuredUntil = block.quarters.maxOfOrNull { it.start.plus(Duration.ofMinutes(QUARTER_MINUTES)) }
        for (hour in block.hours) {
            val replaced = if (hour.measured) hour.start.epochSecond / HOUR_SECONDS in covered
                else measuredUntil != null && hour.start.isBefore(measuredUntil)
            if (!replaced) add(hour.start, HOUR_MINUTES, hour.kw, hour.measured)
        }
        for (quarter in block.quarters) add(quarter.start, QUARTER_MINUTES, quarter.kw, true)
        if (points.isEmpty()) return null
        points.sortBy { it.fromMinute }
        val peak = block.peakKw?.takeIf { it > 0.0 }
        // A record hour above the stated peak still fits.
        return ChartSun(points, maxOf(MIN_MAX_KW, peak ?: 0.0, points.maxOf { it.kw }), peak)
    }

    /** Runs of spans that follow each other (an hour apart at most); a missing hour ends a run, so a gap is a gap. */
    fun runs(points: List<SunPoint>): List<List<SunPoint>> {
        val runs = ArrayList<MutableList<SunPoint>>()
        for (point in points) {
            val last = runs.lastOrNull()?.lastOrNull()
            if (last != null && point.fromMinute - last.fromMinute <= HOUR_MINUTES) runs.last() += point
            else runs += mutableListOf(point)
        }
        return runs
    }

    /**
     * The runs to draw: [runs] without the dark hours. A span at 0 kW is drawn only beside one with sun, so the line
     * starts from and ends on the baseline at dawn and dusk; a night of zeros draws nothing.
     */
    fun litRuns(points: List<SunPoint>): List<List<SunPoint>> {
        val out = ArrayList<List<SunPoint>>()
        for (run in runs(points)) {
            var current = ArrayList<SunPoint>()
            run.forEachIndexed { index, point ->
                val lit = point.kw > 0.0 || (run.getOrNull(index - 1)?.kw ?: 0.0) > 0.0 || (run.getOrNull(index + 1)?.kw ?: 0.0) > 0.0
                if (lit) current += point
                else if (current.isNotEmpty()) { out += current; current = ArrayList() }
            }
            if (current.isNotEmpty()) out += current
        }
        return out
    }
}

/**
 * This phone's choice to draw the sun in the price chart (the card's Display choice "Sun"), kept beside the chart's
 * other display choices and never sent to Home Assistant. On until turned off.
 */
internal class ChartSunPreference(private val store: KeyValueStore) {
    fun shown(): Boolean = store.getString(KEY) != OFF

    fun save(shown: Boolean) {
        store.putString(KEY, if (shown) ON else OFF)
    }

    companion object {
        private const val PREFS = "app_appearance"
        const val KEY = "chart_sun"
        private const val ON = "on"
        private const val OFF = "off"

        fun of(context: Context): ChartSunPreference = ChartSunPreference(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
