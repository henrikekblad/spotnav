package se.sensnology.spotnav.chart

import java.time.OffsetDateTime
import kotlin.math.max

/** Which primitive one mark is drawn as. */
internal enum class ChartMarkKind { POINT, SEGMENT }

/**
 * One mark's drawn geometry: what primitive it is, and how big.
 *
 * Deliberately current-agnostic: nothing here knows the current interval, so the current mark cannot
 * grow over its neighbours.
 */
internal data class ChartMark(
    val kind: ChartMarkKind,
    /** A point's radius, and a segment's nominal width. */
    val radius: Float,
    /** A segment's stroke width. */
    val thickness: Float,
    /** A segment's half length: how far it reaches either side of its anchor. */
    val halfLength: Float
) {
    val diameter: Float get() = radius * 2f
}

/**
 * One primitive a day's marks produce: what to draw, where, and in what state.
 *
 * Produced as a list so a test can compare a day under different `now` values. [alpha] is the one
 * state that depends on the current interval, and is deliberately not part of [mark].
 */
internal data class ChartMarkDraw(
    val mark: ChartMark,
    val start: OffsetDateTime,
    /** The anchor minute of the day this primitive belongs at (see `ChartNow.markMinute`). */
    val minute: Float,
    /** The drawn price, for the y the caller maps this to. */
    val value: Double,
    val colour: Int,
    val alpha: Int
)

internal object ChartMarks {
    fun todayRadius(height: Int, scale: Float): Float = max(4f * scale, height * .0062f)

    fun tomorrowRadius(height: Int, scale: Float): Float = max(3.2f * scale, height * .0052f)

    /**
     * The one geometry for one mark of [market]: a circle for a quarter-hour point, a stroke through
     * the middle of its hour for an hourly interval. Never depends on currentness.
     */
    fun geometry(market: ChartMarket, radius: Float, hourWidth: Float): ChartMark =
        if (market.hourly) {
            ChartMark(
                kind = ChartMarkKind.SEGMENT,
                radius = radius,
                thickness = radius * 1.35f,
                halfLength = hourWidth * .39f
            )
        } else {
            ChartMark(kind = ChartMarkKind.POINT, radius = radius, thickness = radius * 1.35f, halfLength = 0f)
        }

    /**
     * Every primitive one day's marks produce, for one frame: geometry from [geometry], colour from the
     * day's mean, and only the alpha from the current interval's position.
     */
    fun plan(
        market: ChartMarket,
        values: List<Pair<OffsetDateTime, Double>>,
        radius: Float,
        hourWidth: Float,
        nowMinute: Float?,
        mean: Double,
        cheap: Int,
        expensive: Int
    ): List<ChartMarkDraw> {
        val mark = geometry(market, radius, hourWidth)
        return values.map { (start, value) ->
            val minute = ChartNow.markMinute(market, start)
            ChartMarkDraw(
                mark = mark,
                start = start,
                minute = minute,
                value = value,
                colour = if (value <= mean) cheap else expensive,
                alpha = if (nowMinute == null || minute <= nowMinute) MARK_ALPHA else FUTURE_ALPHA
            )
        }
    }

    /** A mark up to and including the current interval, and the softer one after it. */
    const val MARK_ALPHA: Int = 245
    const val FUTURE_ALPHA: Int = 205
}
