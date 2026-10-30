package se.sensnology.spotnav.prices

import se.sensnology.spotnav.prices.entsoe.EntsoeParse
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.truncate

/** What building one day document came to. */
internal sealed interface DayBuild {
    /** A `"v":1` day document, for [RelayDayParser] and the day cache like a relay body. */
    data class Built(val json: String) : DayBuild

    /** A non-EUR area with no ECB rate close enough: nothing is built and nothing is cached. */
    data object NoRate : DayBuild

    data class Invalid(val reason: String) : DayBuild
}

/**
 * One parsed ENTSO-E day as the relay's own day document (`spotnav-relay/internal/format` `BuildDay`,
 * and Home Assistant's `build_day_document`): `v` 1, the area, the market day, `tz` (the display zone)
 * and `market_tz` only when the market calendar differs, `start` in the market zone, the resolution,
 * EUR/kWh prices rounded to five decimals, the ECB rate (`fx`, `fx_date`, `fx_src`), `src`, `published`
 * and `retrieved`. Compact JSON in the relay's field order and number spelling, so the text is the same
 * bytes the relay serves for the same capture.
 */
internal object DayDocumentBuilder {
    const val SOURCE = "entsoe.eu"
    private const val FX_SOURCE = "ECB"
    private const val EUR = "EUR"
    private const val PRICE_DECIMALS = 5

    private val RFC3339: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    fun build(area: PriceMarket, marketDay: LocalDate, day: EntsoeParse.Day, fx: FxRate?, retrieved: Instant): DayBuild {
        val rows = day.rows
        if (rows.size < 2) return DayBuild.Invalid("a day needs at least two rows to state a resolution, got ${rows.size}")
        val start = rows[0].start
        val step = Duration.between(start, rows[1].start)
        val res = step.toMinutes().toInt()
        if (res <= 0 || 60 % res != 0 || step != Duration.ofMinutes(res.toLong())) {
            return DayBuild.Invalid("resolution ${step.toMinutes()}m does not divide an hour")
        }
        rows.forEachIndexed { i, row ->
            if (row.start != start.plus(step.multipliedBy(i.toLong()))) return DayBuild.Invalid("row $i is not on the day's grid")
        }
        val rate = if (area.currency == EUR) null else fx ?: return DayBuild.NoRate
        val market = area.marketZoneId

        val json = StringBuilder()
        json.append("{\"v\":1")
        json.append(",\"area\":").append(text(area.id))
        json.append(",\"date\":").append(text(marketDay.toString()))
        json.append(",\"tz\":").append(text(area.tz))
        if (area.marketTz != area.tz) json.append(",\"market_tz\":").append(text(area.marketTz))
        json.append(",\"start\":").append(text(stamp(start, market)))
        json.append(",\"res\":").append(res)
        json.append(",\"unit\":").append(text(RelayDayParser.UNIT))
        json.append(",\"prices\":[")
        rows.forEachIndexed { i, row ->
            if (i > 0) json.append(',')
            json.append(number(round(row.eur, PRICE_DECIMALS)))
        }
        json.append("]")
        if (rate == null) {
            json.append(",\"fx\":{}")
        } else {
            json.append(",\"fx\":{").append(text(area.currency)).append(':').append(number(rate.rate)).append('}')
            json.append(",\"fx_date\":").append(text(rate.date.toString()))
            json.append(",\"fx_src\":").append(text(FX_SOURCE))
        }
        json.append(",\"src\":").append(text(SOURCE))
        json.append(",\"published\":").append(day.published?.let { text(stamp(it, market)) } ?: "null")
        json.append(",\"retrieved\":").append(text(stamp(retrieved, market)))
        json.append('}')
        return DayBuild.Built(json.toString())
    }

    private fun stamp(instant: Instant, zone: ZoneId): String =
        RFC3339.format(instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).atZone(zone))

    /** A JSON string of the plain ASCII this document carries (ids, zones, dates). */
    private fun text(value: String): String {
        require(value.all { it in ' '..'~' && it != '"' && it != '\\' }) { "unexpected character in a day document field" }
        return "\"$value\""
    }

    /** Go's `math.Round` (half away from zero, sign of zero kept), to [decimals] places, as the relay rounds. */
    fun round(value: Double, decimals: Int): Double {
        val scale = 10.0.pow(decimals)
        val scaled = value * scale
        val whole = truncate(scaled)
        val rounded = if (abs(scaled - whole) >= 0.5) whole + sign(scaled) else whole
        return rounded / scale
    }

    /**
     * A number as Go's `encoding/json` writes one in this document: the fewest decimals that read back as
     * the same double, never an exponent, and `-0` for a negative zero.
     */
    fun number(value: Double): String {
        require(value.isFinite()) { "a day document holds finite numbers only" }
        if (value == 0.0) return if (1.0 / value < 0) "-0" else "0"
        val exact = BigDecimal(value)
        for (scale in 0..20) {
            val candidate = exact.setScale(scale, RoundingMode.HALF_EVEN)
            if (candidate.toDouble() == value) return candidate.stripTrailingZeros().toPlainString()
        }
        return exact.stripTrailingZeros().toPlainString()
    }
}
