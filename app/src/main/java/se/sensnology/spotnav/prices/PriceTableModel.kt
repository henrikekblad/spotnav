package se.sensnology.spotnav.prices

import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.ChargingPlan
import se.sensnology.spotnav.planning.PlanningInputs
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * One populated price cell: the interval it covers, its price, and how much of that interval the
 * plan charges in.
 */
data class PriceTableCell(
    val start: OffsetDateTime,
    val minutes: Long,
    val price: Double,
    val coverage: ChargeCoverage
)

/** A row's identity: one wall-clock position in a day, and *which* occurrence of it that day has. */
data class WallClockPosition(val time: LocalTime, val occurrence: Int)

/** One table row: the same wall-clock position in each day's column. */
data class PriceTableRow(
    val position: WallClockPosition,
    val today: PriceTableCell?,
    val tomorrow: PriceTableCell?,
    val current: Boolean
)

data class PriceTableModel(
    val rows: List<PriceTableRow>,
    val todayAverage: Double?,
    val tomorrowAverage: Double?,
    val todayRange: Pair<Double, Double>,
    val tomorrowRange: Pair<Double, Double>
) {
    val currentIndex: Int get() = rows.indexOfFirst { it.current }
}

object PriceTableModels {
    /** A market-only table for a paired charger whose plan Home Assistant owns. */
    internal fun create(
        result: PriceResult,
        market: ChartMarket,
        now: OffsetDateTime
    ): PriceTableModel = create(result, market, now, emptyList())

    /** The table, with the plan's charging coverage attached to every populated price cell. */
    fun create(
        result: PriceResult,
        inputs: PlanningInputs,
        now: OffsetDateTime,
        plan: ChargingPlan? = null
    ): PriceTableModel = create(result, ChartMarket.of(inputs), now, plan?.periods.orEmpty())

    private fun create(
        result: PriceResult,
        market: ChartMarket,
        now: OffsetDateTime,
        periods: List<ChargingPeriod>
    ): PriceTableModel {
        val minutes = market.intervalMinutes.toLong()
        fun cells(points: List<PricePoint>): List<PriceTableCell> =
            PriceAggregation.aggregate(points, market).map { (start, price) ->
                PriceTableCell(
                    start = start,
                    minutes = minutes,
                    price = market.apply(price),
                    coverage = ChargeCoverage.forInterval(start, minutes, periods)
                )
            }
        val today = cells(result.today)
        val tomorrow = cells(result.tomorrow)
        val todayByPosition = byWallClock(today)
        val tomorrowByPosition = byWallClock(tomorrow)
        // Chronological display order, decided by the identity itself: wall clock first, then which
        // occurrence -- the order those labels read in on an ordinary day and on the day a
        // clock-fall repeats an hour.
        val positions = (todayByPosition.keys + tomorrowByPosition.keys).distinct()
            .sortedWith(compareBy({ it.time }, { it.occurrence }))
        val ordered = positions.map { position ->
            PriceTableRow(position, todayByPosition[position], tomorrowByPosition[position], current = false)
        }
        // The current row is the one holding the last today cell at or before now, found by
        // *instant*: the display order is the wall clock's, and on a fall-back day "the last row
        // displayed" is not "the last row that has happened" -- the second 02:00 sits earlier than
        // the first 02:45.
        val currentPosition = todayByPosition.entries
            .filter { it.value.start <= now }
            .maxByOrNull { it.value.start.toInstant() }
            ?.key
        val rows = ordered.map { row -> if (row.position == currentPosition) row.copy(current = true) else row }
        fun average(cells: List<PriceTableCell>) = cells.map { it.price }.average().takeUnless(Double::isNaN)
        fun range(cells: List<PriceTableCell>) =
            (cells.minOfOrNull { it.price } ?: 0.0) to (cells.maxOfOrNull { it.price } ?: 0.0)
        return PriceTableModel(rows, average(today), average(tomorrow), range(today), range(tomorrow))
    }

    /** One day's cells, keyed by the wall-clock position each sits at. */
    private fun byWallClock(cells: List<PriceTableCell>): Map<WallClockPosition, PriceTableCell> {
        val occurrences = mutableMapOf<LocalTime, Int>()
        return cells.sortedBy { it.start.toInstant() }.associateBy { cell ->
            val time = cell.start.toLocalTime()
            WallClockPosition(time, occurrences.merge(time, 1, Int::plus)!!)
        }
    }
}

object PriceAggregation {
    /** The series as one drawn mark per interval, for the market a graph is drawn in. */
    internal fun aggregate(points: List<PricePoint>, market: ChartMarket): List<Pair<OffsetDateTime, Double>> {
        if (market.aggregationMinutes == 15) return points.map { it.start to it.pricePerKwh }
        return points.groupBy { it.start.toLocalDate() to it.start.hour }
            .values.map { group -> group.first().start.withMinute(0) to group.map { it.pricePerKwh }.average() }
            .sortedBy { it.first }
    }

    /** The same aggregation for a caller that still holds calculation inputs (the compatibility path). */
    fun aggregate(points: List<PricePoint>, inputs: PlanningInputs): List<Pair<OffsetDateTime, Double>> =
        aggregate(points, ChartMarket.of(inputs))
}
