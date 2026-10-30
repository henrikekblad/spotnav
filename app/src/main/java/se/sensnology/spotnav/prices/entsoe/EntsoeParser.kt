package se.sensnology.spotnav.prices.entsoe

import org.w3c.dom.Element
import se.sensnology.spotnav.prices.EntsoeRoute
import se.sensnology.spotnav.prices.SafeXml
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One price row: its start, and the price in EUR/kWh. */
internal data class EntsoeRow(val start: Instant, val eur: Double)

/** What one ENTSO-E body says about one zone's one market day. */
internal sealed interface EntsoeParse {
    /** The whole day, at one resolution; [published] is the document's own creation time when valid. */
    data class Day(val rows: List<EntsoeRow>, val published: Instant?) : EntsoeParse

    /** The platform has no document for the day yet (its no-data acknowledgement, or a strict leading prefix). */
    data object NoData : EntsoeParse

    /** Not a day this app may show. [reason] names fields and numbers, never the body's text. */
    data class Refused(val reason: String) : EntsoeParse
}

/**
 * A line-by-line port of the relay's `ParseEntsoe` (`spotnav-relay/internal/source/entsoe.go`): one
 * ENTSO-E response body into one local day's rows, refusing rather than repairing. The refusals are the
 * contract: a document that is not A44, no series for the zone, a currency that is not EUR, a unit that
 * is not MWh, a resolution that is neither PT60M nor PT15M (nor PT30M for a half-hourly route), an
 * unknown curve type, a period that is not a whole number of slots, an impossible slot, a price that is
 * not a finite number, sequences that disagree, or rows that do not add up to the day exactly. A day
 * whose periods come at two resolutions is read at the finer one.
 *
 * Prices are EUR/MWh in the document and EUR/kWh in what this returns; the division happens once, here.
 */
internal object EntsoeParser {
    private const val CURRENCY = "EUR"
    private const val PRICE_UNIT = "mwh"
    private const val PER_KWH = 1000.0
    private const val CURVE_STEPWISE = "A01"
    private const val CURVE_VARIABLE = "A03"

    /**
     * How much two series may differ at one instant and still be one price: a millionth of a EUR/kWh
     * (the relay's `entsoeAgreementEUR`). The platform writes six significant digits.
     */
    const val AGREEMENT_EUR = 1e-6

    private val FIFTEEN = Duration.ofMinutes(15)
    private val THIRTY = Duration.ofMinutes(30)
    private val HOUR = Duration.ofHours(1)

    private class Refusal(message: String) : Exception(message)

    private class Point(val position: Int, val amount: Double)
    private class Period(val start: String, val end: String, val resolution: String, val points: List<Point>)
    private class Series(
        val inDomain: String,
        val outDomain: String,
        val currency: String,
        val unit: String,
        val curveType: String,
        val sequence: String,
        val periods: List<Period>
    )

    /** The local day [date] in [zone] as a half-open interval of instants: 23, 24 or 25 hours. */
    fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

    /** One body, for the zone of [route] and the market day [date] in [zone]. */
    fun parse(body: String, route: EntsoeRoute, zone: ZoneId, date: LocalDate): EntsoeParse = try {
        parseOrThrow(body, route, zone, date)
    } catch (refusal: Refusal) {
        EntsoeParse.Refused(refusal.message.orEmpty())
    }

    private fun parseOrThrow(body: String, route: EntsoeRoute, zone: ZoneId, date: LocalDate): EntsoeParse {
        // A body with no status: the text-only rule, at the status a no-data answer normally has.
        EntsoeAck.parse(body)?.let { ack -> if (ack.kind(200) == AckKind.NO_DATA) return EntsoeParse.NoData }
        val root = SafeXml.root(body)?.takeIf { SafeXml.localName(it) == "Publication_MarketDocument" }
            ?: throw Refusal("response is not an A44 document")
        val type = SafeXml.childText(root, "type")?.trim().orEmpty()
        if (type != "A44") throw Refusal("document type \"$type\" is not A44")
        val allSeries = SafeXml.children(root, "TimeSeries").map(::series)
        if (allSeries.isEmpty()) throw Refusal("the document has no TimeSeries")

        val (start, end) = dayBounds(date, zone)
        val fine = mixedStep(allSeries, route, start, end)
        val bySequence = LinkedHashMap<String, MutableList<EntsoeRow>>()
        var others = 0
        allSeries.forEachIndexed { index, series ->
            if (series.inDomain != route.eic || series.outDomain != route.eic) {
                others++
                return@forEachIndexed
            }
            val currency = series.currency.trim()
            if (currency != CURRENCY) throw Refusal("time series $index is priced in \"$currency\", not \"$CURRENCY\"")
            if (series.unit.trim().lowercase() != PRICE_UNIT) {
                throw Refusal("time series $index measures \"${series.unit}\", not MWh")
            }
            val rows = seriesRows(series, series.curveType.trim(), start, end, index, route.halfHourly, fine)
            bySequence.getOrPut(series.sequence.trim()) { ArrayList() }.addAll(rows)
        }
        if (bySequence.isEmpty()) throw Refusal("the document carries no series for ${route.eic} ($others for other zones)")
        val order = bySequence.keys.sorted()

        if (isUnpublishedPrefix(bySequence, start, end, route.halfHourly)) return EntsoeParse.NoData

        val policy = route.sequence?.trim().orEmpty()
        val rows = if (policy.isNotEmpty()) {
            sequenceByPolicy(bySequence, order, policy, route.expectedSequences, start, end, route, date)
        } else {
            chooseSequence(bySequence, order, start, end, route, date)
        }
        val published = runCatching { parseTime(SafeXml.childText(root, "createdDateTime").orEmpty()) }.getOrNull()
        return EntsoeParse.Day(rows, published)
    }

    // Reading the document

    private fun series(element: Element): Series = Series(
        inDomain = SafeXml.childText(element, "in_Domain.mRID").orEmpty(),
        outDomain = SafeXml.childText(element, "out_Domain.mRID").orEmpty(),
        currency = SafeXml.childText(element, "currency_Unit.name").orEmpty(),
        unit = SafeXml.childText(element, "price_Measure_Unit.name").orEmpty(),
        curveType = SafeXml.childText(element, "curveType").orEmpty(),
        sequence = SafeXml.childText(element, "classificationSequence_AttributeInstanceComponent.position").orEmpty(),
        periods = SafeXml.children(element, "Period").map(::period)
    )

    private fun period(element: Element): Period {
        val interval = SafeXml.child(element, "timeInterval")
        return Period(
            start = interval?.let { SafeXml.childText(it, "start") }.orEmpty(),
            end = interval?.let { SafeXml.childText(it, "end") }.orEmpty(),
            resolution = SafeXml.childText(element, "resolution").orEmpty(),
            points = SafeXml.children(element, "Point").map(::point)
        )
    }

    private val DECIMAL = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")
    private val NOT_FINITE = Regex("""[+-]?(nan|inf|infinity)""", RegexOption.IGNORE_CASE)

    /**
     * One point. A position that is not a whole number, or an amount that is missing or not a number, is
     * not a document this reads; an amount that spells a non-finite number is named as one.
     */
    private fun point(element: Element): Point {
        val positionText = SafeXml.childText(element, "position")?.trim().orEmpty()
        val position = if (positionText.isEmpty()) 0 else positionText.toIntOrNull()
            ?: throw Refusal("response is not an A44 document: a position is not a whole number")
        val amountText = SafeXml.childText(element, "price.amount")?.trim().orEmpty()
        val amount = when {
            DECIMAL.matches(amountText) -> amountText.toDouble()
            NOT_FINITE.matches(amountText) -> Double.NaN
            else -> throw Refusal("response is not an A44 document: a price is missing or not a number")
        }
        return Point(position, amount)
    }

    // One series' rows

    private fun seriesRows(
        series: Series,
        curve: String,
        dayStart: Instant,
        dayEnd: Instant,
        index: Int,
        halfHourly: Boolean,
        fine: Duration?
    ): List<EntsoeRow> {
        if (series.periods.isEmpty()) throw Refusal("time series $index has no Period")
        val rows = ArrayList<EntsoeRow>()
        series.periods.forEachIndexed { p, period ->
            val periodStart = time(period.start, "time series $index period $p start")
            val periodEnd = time(period.end, "time series $index period $p end")
            // Another day's period in the same document is the platform's own shape, not an error.
            if (!periodStart.isBefore(dayEnd) || !periodEnd.isAfter(dayStart)) return@forEachIndexed
            val step = resolution(period.resolution, halfHourly, "time series $index period $p: ")
            // Expanded whole before clipping: an A03 block that starts before midnight carries into the day.
            var expanded = periodRows(period, curve, step, index, p)
            if (fine != null && step != fine) {
                if (step.toMillis() % fine.toMillis() != 0L || periodStart.toEpochMilli() % step.toMillis() != 0L) {
                    throw Refusal(
                        "time series $index period $p is ${period.resolution.trim()} and starts at ${stamp(periodStart)}, " +
                            "which cannot be split into ${minutes(fine)} slots"
                    )
                }
                val parts = (step.toMillis() / fine.toMillis()).toInt()
                expanded = expanded.flatMap { row -> (0 until parts).map { EntsoeRow(row.start.plus(fine.multipliedBy(it.toLong())), row.eur) } }
            }
            for (row in expanded) {
                if (row.start.isBefore(dayStart) || !row.start.isBefore(dayEnd)) continue
                rows.add(row)
            }
        }
        return rows
    }

    /**
     * One period's rows by curve type. A01 states every slot, so an absent position is a contradiction;
     * A03 states a value where it changes and carries it to the next stated position (the last one to
     * the period's end), so it must state its first slot.
     */
    private fun periodRows(period: Period, curve: String, step: Duration, series: Int, index: Int): List<EntsoeRow> {
        val periodStart = time(period.start, "time series $series period $index start")
        val periodEnd = time(period.end, "time series $series period $index end")
        val length = Duration.between(periodStart, periodEnd)
        if (length.isNegative || length.isZero) {
            throw Refusal("time series $series period $index ends at or before it starts (${length.toMinutes()}m)")
        }
        if (length.toMillis() % step.toMillis() != 0L) {
            throw Refusal(
                "time series $series period $index covers ${length.toMinutes()}m, which is not a whole number of ${minutes(step)} slots"
            )
        }
        val slots = (length.toMillis() / step.toMillis()).toInt()
        if (period.points.isEmpty()) throw Refusal("time series $series period $index has no Point")

        val stated = HashMap<Int, Double>(period.points.size)
        for (point in period.points) {
            if (point.position < 1) {
                throw Refusal("time series $series period $index has position ${point.position}, which is not a slot number")
            }
            if (point.position > slots) {
                throw Refusal("time series $series period $index has position ${point.position}, beyond its own $slots slots")
            }
            if (point.position in stated) throw Refusal("time series $series period $index repeats position ${point.position}")
            if (!point.amount.isFinite()) {
                throw Refusal("time series $series period $index position ${point.position} has a price that is not a finite number")
            }
            stated[point.position] = point.amount
        }
        val positions = stated.keys.sorted()

        when (curve) {
            CURVE_STEPWISE -> {
                if (positions.size != slots) {
                    throw Refusal("time series $series period $index is curveType A01 and states ${positions.size} of its $slots slots")
                }
                positions.forEachIndexed { i, position ->
                    if (position != i + 1) {
                        throw Refusal("time series $series period $index is curveType A01 and jumps from slot $i to $position")
                    }
                }
            }
            CURVE_VARIABLE -> if (positions.first() != 1) {
                throw Refusal(
                    "time series $series period $index is curveType A03 and its first block is at slot ${positions.first()}, " +
                        "so the period's start has no value"
                )
            }
            else -> throw Refusal("time series $series period $index has curveType \"$curve\", which this app does not interpret")
        }

        val rows = ArrayList<EntsoeRow>(slots)
        positions.forEachIndexed { i, position ->
            val next = if (i + 1 < positions.size) positions[i + 1] else slots + 1
            for (slot in position until next) {
                // EUR/MWh to EUR/kWh: the one conversion, in the one place.
                rows.add(EntsoeRow(periodStart.plus(step.multipliedBy((slot - 1).toLong())), stated.getValue(position) / PER_KWH))
            }
        }
        return rows
    }

    /**
     * The finest resolution of a local day published at more than one (an EET day across the 2025-10-01
     * switch to quarter-hours), `null` for a day at one. Periods at different resolutions must not overlap.
     */
    private fun mixedStep(allSeries: List<Series>, route: EntsoeRoute, dayStart: Instant, dayEnd: Instant): Duration? {
        class Span(val start: Instant, val end: Instant, val step: Duration, val series: Int, val period: Int)
        val bySequence = LinkedHashMap<String, MutableList<Span>>()
        val steps = HashSet<Duration>()
        allSeries.forEachIndexed { i, series ->
            if (series.inDomain != route.eic || series.outDomain != route.eic) return@forEachIndexed
            series.periods.forEachIndexed { j, period ->
                var start = runCatching { parseTime(period.start) }.getOrNull() ?: return@forEachIndexed
                var end = runCatching { parseTime(period.end) }.getOrNull() ?: return@forEachIndexed
                if (!start.isBefore(dayEnd) || !end.isAfter(dayStart)) return@forEachIndexed
                val step = runCatching { resolution(period.resolution, route.halfHourly, "") }.getOrNull() ?: return@forEachIndexed
                steps.add(step)
                if (start.isBefore(dayStart)) start = dayStart
                if (end.isAfter(dayEnd)) end = dayEnd
                bySequence.getOrPut(series.sequence.trim()) { ArrayList() }.add(Span(start, end, step, i, j))
            }
        }
        if (steps.size < 2) return null
        val fine = steps.minOrNull()!!
        for (spans in bySequence.values) {
            for (a in spans.indices) {
                for (b in a + 1 until spans.size) {
                    val x = spans[a]
                    val y = spans[b]
                    if (x.step == y.step || !x.start.isBefore(y.end) || !y.start.isBefore(x.end)) continue
                    val later = if (x.start.isAfter(y.start)) x.start else y.start
                    throw Refusal(
                        "time series ${x.series} period ${x.period} (${minutes(x.step)}) and time series ${y.series} " +
                            "period ${y.period} (${minutes(y.step)}) both cover ${stamp(later)}: the day has an overlapping period"
                    )
                }
            }
        }
        return fine
    }

    // What makes a day

    /**
     * The one incomplete shape that means "keep asking": one sequence, empty or a well-formed strict
     * prefix starting exactly at the requested day.
     */
    private fun isUnpublishedPrefix(groups: Map<String, List<EntsoeRow>>, start: Instant, end: Instant, halfHourly: Boolean): Boolean {
        if (groups.size != 1) return false
        for (rows in groups.values) {
            val merged = runCatching { mergeRows(rows) }.getOrNull() ?: return false
            if (merged.isEmpty()) continue
            if (merged[0].start != start || !merged.last().start.isBefore(end)) return false
            if (merged.size == 1) continue
            val step = Duration.between(merged[0].start, merged[1].start)
            if (!isStep(step, halfHourly)) return false
            merged.forEachIndexed { i, row -> if (row.start != start.plus(step.multipliedBy(i.toLong()))) return false }
            if (!merged.last().start.plus(step).isBefore(end)) return false
        }
        return true
    }

    /** Without a sequence policy: one complete sequence, or every sequence complete and all agreeing. */
    private fun chooseSequence(
        groups: Map<String, List<EntsoeRow>>,
        order: List<String>,
        start: Instant,
        end: Instant,
        route: EntsoeRoute,
        date: LocalDate
    ): List<EntsoeRow> {
        val complete = ArrayList<Pair<String, List<EntsoeRow>>>()
        val problems = ArrayList<String>()
        for (sequence in order) {
            try {
                val merged = mergeRows(groups.getValue(sequence))
                coverage(merged, start, end, route.halfHourly)
                complete.add(label(sequence) to merged)
            } catch (refusal: Refusal) {
                problems.add("${label(sequence)} (${refusal.message})")
            }
        }
        return when {
            complete.isEmpty() ->
                throw Refusal("no price sequence for ${route.eic} covers $date: ${problems.joinToString("; ")}")
            complete.size != order.size -> throw Refusal(
                "not every sequence for ${route.eic} covers $date (${problems.joinToString("; ")}), and without a sequence " +
                    "policy this app cannot show which sequence is the product"
            )
            complete.size == 1 -> complete[0].second
            else -> {
                val first = complete[0]
                for (other in complete.drop(1)) {
                    try {
                        sequencesAgree(first.second, other.second)
                    } catch (refusal: Refusal) {
                        throw Refusal(
                            "sequences ${first.first} and ${other.first} for ${route.eic} are both complete and disagree " +
                                "(${refusal.message}), and without a sequence policy this app cannot choose between them"
                        )
                    }
                }
                first.second
            }
        }
    }

    /** With a policy: that sequence and no other, and no label the zone does not expect. */
    private fun sequenceByPolicy(
        groups: Map<String, List<EntsoeRow>>,
        order: List<String>,
        policy: String,
        expected: List<String>,
        start: Instant,
        end: Instant,
        route: EntsoeRoute,
        date: LocalDate
    ): List<EntsoeRow> {
        for (label in order) {
            if (label != policy && !isExpected(expected, label)) {
                throw Refusal(
                    "the document for ${route.eic} carries sequence ${label(label)}, which this zone does not expect " +
                        "(it publishes $policy and expects ${expectedLabels(policy, expected).joinToString(", ") { label(it) }}); " +
                        "refusing the day rather than ignoring a label the platform does not document"
                )
            }
        }
        val rows = groups[policy] ?: throw Refusal(
            "the document carries no sequence $policy for ${route.eic} (it has ${order.joinToString(", ") { label(it) }}), " +
                "and the zone uses only that sequence"
        )
        val merged = try {
            mergeRows(rows)
        } catch (refusal: Refusal) {
            throw Refusal("sequence $policy for ${route.eic}: ${refusal.message}")
        }
        try {
            coverage(merged, start, end, route.halfHourly)
        } catch (refusal: Refusal) {
            throw Refusal("sequence $policy for ${route.eic} does not cover $date: ${refusal.message}")
        }
        return merged
    }

    private fun sequencesAgree(a: List<EntsoeRow>, b: List<EntsoeRow>) {
        if (a.size != b.size) throw Refusal("one has ${a.size} slots and the other ${b.size}")
        for (i in a.indices) {
            if (a[i].start != b[i].start) throw Refusal("slot $i is ${stamp(a[i].start)} in one and ${stamp(b[i].start)} in the other")
            if (kotlin.math.abs(a[i].eur - b[i].eur) > AGREEMENT_EUR) {
                throw Refusal("slot $i is ${mwh(a[i].eur)} EUR/MWh in one and ${mwh(b[i].eur)} in the other")
            }
        }
    }

    private fun label(sequence: String): String = sequence.trim().ifEmpty { "none" }

    private fun isExpected(expected: List<String>, label: String): Boolean = expected.any { it.trim() == label }

    private fun expectedLabels(policy: String, expected: List<String>): List<String> =
        if (isExpected(expected, policy)) expected else listOf(policy) + expected

    /** Rows ordered by instant, with the ones that agree collapsed; two that disagree refuse. */
    private fun mergeRows(rows: List<EntsoeRow>): List<EntsoeRow> {
        val sorted = rows.sortedBy { it.start }
        val merged = ArrayList<EntsoeRow>(sorted.size)
        for (row in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.start == row.start) {
                if (kotlin.math.abs(last.eur - row.eur) > AGREEMENT_EUR) {
                    throw Refusal("two values for ${stamp(row.start)} disagree: ${mwh(last.eur)} and ${mwh(row.eur)} EUR/MWh")
                }
                continue
            }
            merged.add(row)
        }
        return merged
    }

    /** The rows are the requested day and nothing else: from its start to its end, contiguous, one resolution. */
    private fun coverage(rows: List<EntsoeRow>, dayStart: Instant, dayEnd: Instant, halfHourly: Boolean) {
        if (rows.size < 2) throw Refusal("a day needs at least two rows to state a resolution, got ${rows.size}")
        if (rows[0].start != dayStart) {
            throw Refusal("the rows start at ${stamp(rows[0].start)}, not at the requested ${stamp(dayStart)}")
        }
        val step = Duration.between(rows[0].start, rows[1].start)
        if (!isStep(step, halfHourly)) {
            throw Refusal(
                if (halfHourly) "resolution ${minutes(step)} is not 15, 30 or 60 minutes"
                else "resolution ${minutes(step)} is not 15 or 60 minutes"
            )
        }
        rows.forEachIndexed { i, row ->
            val want = rows[0].start.plus(step.multipliedBy(i.toLong()))
            if (row.start != want) {
                throw Refusal(
                    "row $i starts at ${stamp(row.start)}, expected ${stamp(want)}: the day has a gap, a repeat or an overlapping period"
                )
            }
        }
        val end = rows.last().start.plus(step)
        if (end != dayEnd) throw Refusal("the rows end at ${stamp(end)}, not at the requested ${stamp(dayEnd)}")
    }

    private fun resolution(value: String, halfHourly: Boolean, context: String): Duration = when (value.trim()) {
        "PT60M" -> HOUR
        "PT15M" -> FIFTEEN
        "PT30M" -> if (halfHourly) THIRTY else throw Refusal(
            "${context}resolution \"PT30M\" is not this zone's: only a half-hourly market's documents may be PT30M"
        )
        else -> throw Refusal("${context}resolution \"${value.trim()}\" is neither PT60M nor PT15M")
    }

    private fun isStep(step: Duration, halfHourly: Boolean): Boolean =
        step == FIFTEEN || step == HOUR || (halfHourly && step == THIRTY)

    // Time

    private val MINUTES_Z = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z""")
    private val RFC3339 = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")
    private val MINUTES_Z_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'")

    /** The platform's timestamps: UTC with a `Z`, with or without seconds (or an RFC 3339 offset with seconds). */
    private fun parseTime(raw: String): Instant {
        val value = raw.trim()
        if (value.isEmpty()) throw Refusal("no timestamp")
        val parsed = when {
            MINUTES_Z.matches(value) -> runCatching { LocalDateTime.parse(value, MINUTES_Z_FORMAT).toInstant(ZoneOffset.UTC) }.getOrNull()
            RFC3339.matches(value) -> runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            else -> null
        }
        return parsed ?: throw Refusal("\"$value\" is not a UTC timestamp")
    }

    private fun time(raw: String, context: String): Instant = try {
        parseTime(raw)
    } catch (refusal: Refusal) {
        throw Refusal("$context: ${refusal.message}")
    }

    private fun stamp(instant: Instant): String = instant.toString()

    private fun minutes(step: Duration): String = "${step.toMinutes()}m"

    private fun mwh(eur: Double): String = String.format(java.util.Locale.ROOT, "%.2f", eur * PER_KWH)
}
