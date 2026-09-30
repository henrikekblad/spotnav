package se.sensnology.spotnav.prices

import se.sensnology.spotnav.planning.ChargingPeriod
import java.time.Instant
import java.time.OffsetDateTime

/**
 * How much of one price cell the recommended plan charges in, as quarter-hour segments in
 * chronological order.
 */
class ChargeCoverage private constructor(private val segments: List<Boolean>) {
    /** How many quarter-hours this cell presents: 1 at 15 minutes, 4 at an hour. */
    val segmentCount: Int get() = segments.size

    /** How many of them the plan charges in. */
    val selectedCount: Int get() = segments.count { it }

    /** Nothing in this cell is charged in, so there is nothing to show. */
    val isEmpty: Boolean get() = selectedCount == 0

    /** Every quarter of this cell is charged in. */
    val isWhole: Boolean get() = selectedCount == segmentCount

    /** Whether the quarter-hour at [segment] is charged in; out of range is not. */
    fun isSelected(segment: Int): Boolean = segments.getOrElse(segment) { false }

    /** `1100` for the first two quarters of an hour — a readable form in failures. */
    override fun toString(): String = segments.joinToString("") { if (it) "1" else "0" }

    override fun equals(other: Any?): Boolean = other is ChargeCoverage && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        /** One quarter-hour, unmarked. Shared: it is a constant, not a per-call value. */
        val NONE = ChargeCoverage(listOf(false))

        const val QUARTER_MINUTES = 15L
        const val QUARTERS_PER_HOUR = 4

        /** A single quarter-hour cell. */
        fun quarter(selected: Boolean): ChargeCoverage = ChargeCoverage(listOf(selected))

        /** An hourly cell, quarter by quarter, in chronological order. */
        fun hour(vararg selected: Boolean): ChargeCoverage {
            require(selected.size == QUARTERS_PER_HOUR) { "an hour has $QUARTERS_PER_HOUR quarters" }
            return ChargeCoverage(selected.toList())
        }

        /**
         * The coverage of the interval `[start, start + minutes)` against the plan's half-open
         * charging [periods].
         */
        fun forInterval(start: OffsetDateTime, minutes: Long, periods: List<ChargingPeriod>): ChargeCoverage {
            val quarters = (minutes / QUARTER_MINUTES).toInt().coerceAtLeast(1)
            return ChargeCoverage((0 until quarters).map { index ->
                overlaps(
                    periods,
                    start.plusMinutes(index * QUARTER_MINUTES).toInstant(),
                    start.plusMinutes((index + 1) * QUARTER_MINUTES).toInstant()
                )
            })
        }

        /** Whether any period covers any of `[from, until)`. */
        fun overlaps(periods: List<ChargingPeriod>, from: Instant, until: Instant): Boolean =
            periods.any { it.start.toInstant() < until && from < it.end.toInstant() }
    }
}
