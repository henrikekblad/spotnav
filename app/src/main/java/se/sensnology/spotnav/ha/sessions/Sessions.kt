package se.sensnology.spotnav.ha.sessions

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

/** An answer the app cannot read as charge history. */
internal class SessionsDecodeException(message: String) : IllegalArgumentException(message)

/**
 * The totals of one period (a month, as the dashboard's `sessions_summary` and the answer's
 * `month_summary` state them). Money is in the major unit, prices in the minor unit per kWh; a
 * figure Home Assistant could not work out (no prices for the energy) is `null`, never zero.
 */
internal data class SessionTotals(
    /** `YYYY-MM`, or `null` when the answer does not say. */
    val period: String?,
    val sessions: Int,
    val energyKwh: Double,
    val cost: Double?,
    val currency: String?,
    val majorUnit: String?,
    val minorUnit: String?,
    val averagePriceMinorPerKwh: Double?,
    /** The share of the energy that came from solar, 0..1, or `null` when it is not known. */
    val solarShare: Double?,
    /** What the charging saved against the day's average price: an estimate, and signed. */
    val savings: Double?,
    /** Some of the energy was estimated rather than metered. */
    val estimated: Boolean
) {
    val isEmpty: Boolean get() = sessions == 0 && energyKwh <= 0.0
}

/** One day of a month's chart: every day of the month has one, a day with no charge at zero. */
internal data class SessionDay(
    val date: LocalDate,
    val energyKwh: Double,
    val cost: Double?,
    val averagePriceMinorPerKwh: Double?,
    val solarShare: Double?,
    val sessions: Int
)

/** One charge session, as the list under the chart shows it. */
internal data class ChargeSessionRow(
    val id: String,
    val start: OffsetDateTime,
    /** `null` for a session still open. */
    val end: OffsetDateTime?,
    val energyKwh: Double,
    val cost: Double?,
    val averagePriceMinorPerKwh: Double?,
    val solarShare: Double?,
    val savings: Double?,
    val startedBy: String?,
    val strategy: String?,
    val vehicle: String?,
    val estimated: Boolean
)

/** The answer for one month of the webhook's `sessions` action. */
internal data class SessionsMonth(
    /** The month the figures are for, `YYYY-MM`. */
    val month: YearMonth,
    val summary: SessionTotals,
    val days: List<SessionDay>,
    /** Newest first. */
    val sessions: List<ChargeSessionRow>,
    /** The months that have data, newest first. */
    val availableMonths: List<YearMonth>
)

/** The dashboard's `sessions_summary` block: this month's and last month's totals. */
internal data class SessionsSummary(val thisMonth: SessionTotals?, val lastMonth: SessionTotals?)

/** The month's sessions as a CSV text, and the name to save it under. */
internal data class SessionsCsv(val filename: String, val csv: String)

/** Reads the `sessions` answers; anything that is not the shape is refused, nothing is guessed. */
internal object SessionsCodec {
    /** The dashboard's `sessions_summary`, or `null` when it is absent or has no readable month. */
    fun parseSummary(raw: Any?): SessionsSummary? {
        val block = raw as? JSONObject ?: return null
        val thisMonth = totalsOrNull(block.opt("this_month"))
        val lastMonth = totalsOrNull(block.opt("last_month"))
        return if (thisMonth == null && lastMonth == null) null else SessionsSummary(thisMonth, lastMonth)
    }

    /** The answer for one month: the body of an `ok` answer of the action. */
    fun parseMonth(json: JSONObject): SessionsMonth {
        val month = month(json.opt("month")) ?: throw SessionsDecodeException("`month` is not a month")
        val summary = totals(json.opt("month_summary") as? JSONObject
            ?: throw SessionsDecodeException("`month_summary` is not an object"))
        val days = list(json, "month_days").map(::day).sortedBy { it.date }
        val sessions = list(json, "month_sessions").map(::session)
        val available = (json.opt("available_months") as? JSONArray
            ?: throw SessionsDecodeException("`available_months` is not a list"))
            .let { array -> (0 until array.length()).map { month(array.opt(it)) ?: throw SessionsDecodeException("an available month is not a month") } }
        return SessionsMonth(month, summary, days, sessions, available.sortedDescending())
    }

    /** The answer with `format: "csv"`. */
    fun parseCsv(json: JSONObject): SessionsCsv {
        val csv = json.opt("csv") as? String ?: throw SessionsDecodeException("`csv` is not text")
        val filename = (json.opt("filename") as? String)?.takeIf { it.isNotBlank() }
        return SessionsCsv(filename ?: "spotnav-sessions.csv", csv)
    }

    private fun month(raw: Any?): YearMonth? =
        (raw as? String)?.takeIf { MONTH.matches(it) }?.let { runCatching { YearMonth.parse(it) }.getOrNull() }

    private val MONTH = Regex("""\d{4}-(0[1-9]|1[0-2])""")

    private fun totalsOrNull(raw: Any?): SessionTotals? =
        (raw as? JSONObject)?.let { runCatching { totals(it) }.getOrNull() }

    private fun totals(json: JSONObject) = SessionTotals(
        period = text(json, "period"),
        sessions = whole(json.opt("sessions")) ?: 0,
        energyKwh = number(json, "energy_kwh") ?: 0.0,
        cost = number(json, "cost"),
        currency = text(json, "currency"),
        majorUnit = text(json, "major_unit"),
        minorUnit = text(json, "minor_unit"),
        averagePriceMinorPerKwh = number(json, "average_price_minor_per_kwh"),
        solarShare = number(json, "solar_share"),
        savings = number(json, "savings"),
        estimated = json.opt("estimated") == true
    )

    private fun day(json: JSONObject) = SessionDay(
        date = (json.opt("period") as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: throw SessionsDecodeException("a day has no date"),
        energyKwh = number(json, "energy_kwh") ?: 0.0,
        cost = number(json, "cost"),
        averagePriceMinorPerKwh = number(json, "average_price_minor_per_kwh"),
        solarShare = number(json, "solar_share"),
        sessions = whole(json.opt("sessions")) ?: 0
    )

    private fun session(json: JSONObject) = ChargeSessionRow(
        id = text(json, "id") ?: throw SessionsDecodeException("a session has no id"),
        start = instant(json.opt("start")) ?: throw SessionsDecodeException("a session has no start"),
        end = instant(json.opt("end")),
        energyKwh = number(json, "energy_kwh") ?: 0.0,
        cost = number(json, "cost"),
        averagePriceMinorPerKwh = number(json, "average_price_minor_per_kwh"),
        solarShare = number(json, "solar_share"),
        savings = number(json, "savings"),
        startedBy = text(json, "started_by"),
        strategy = text(json, "strategy"),
        vehicle = text(json, "vehicle"),
        estimated = json.opt("estimated") == true
    )

    private fun list(json: JSONObject, key: String): List<JSONObject> {
        val array = json.opt(key) as? JSONArray ?: throw SessionsDecodeException("`$key` is not a list")
        return (0 until array.length()).map { array.opt(it) as? JSONObject ?: throw SessionsDecodeException("`$key` holds a non-object") }
    }

    private fun instant(raw: Any?): OffsetDateTime? =
        (raw as? String)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    private fun text(json: JSONObject, key: String): String? = (json.opt(key) as? String)?.takeIf { it.isNotEmpty() }

    private fun number(json: JSONObject, key: String): Double? =
        (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun whole(raw: Any?): Int? = (raw as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it == Math.floor(it) && it >= 0 && it < Int.MAX_VALUE }?.toInt()
}
