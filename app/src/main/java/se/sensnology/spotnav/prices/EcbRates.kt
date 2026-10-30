package se.sensnology.spotnav.prices

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.AndroidRelayLog
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One day's ECB reference rates as published: units of each currency per EUR. */
internal data class EcbTable(val day: LocalDate, val values: Map<String, Double>)

/** The rate that converts one day, and the date of the table it came from. */
internal data class FxRate(val rate: Double, val date: LocalDate)

/**
 * The ECB's reference-rate XML, read as the relay (`internal/ecb`) and Home Assistant
 * (`pricing/fx_rates.py`) read it. A wrong entry refuses the whole body: a table with one wrong rate
 * would convert some day silently wrong.
 */
internal object EcbParser {
    /** The rates a daily table must carry, or it is truncated and refused (the relay's `ecb.Needed`). */
    val NEEDED = listOf("SEK", "NOK", "DKK")

    /** Every dated table in an ECB envelope, in document order, or `null` when the body is not one. */
    fun tables(body: String): List<EcbTable>? {
        val root = SafeXml.root(body) ?: return null
        val tables = ArrayList<EcbTable>()
        for (outer in SafeXml.children(root, "Cube")) {
            for (cube in SafeXml.children(outer, "Cube")) {
                val stamp = cube.getAttribute("time")
                val entries = SafeXml.children(cube, "Cube")
                if (stamp.isEmpty() || entries.isEmpty()) continue
                val day = runCatching { LocalDate.parse(stamp) }.getOrNull() ?: return null
                val values = LinkedHashMap<String, Double>()
                for (entry in entries) {
                    val currency = entry.getAttribute("currency").trim()
                    if (currency.isEmpty()) continue
                    values[currency] = rate(entry.getAttribute("rate")) ?: return null
                }
                if (values.isNotEmpty()) tables.add(EcbTable(day, values))
            }
        }
        return tables.ifEmpty { null }
    }

    /** The daily file's one table, refused without the [NEEDED] rates. */
    fun daily(body: String): EcbTable? {
        val table = tables(body)?.first() ?: return null
        return table.takeIf { found -> NEEDED.all { it in found.values } }
    }

    /** A rate as the ECB writes it: a positive, finite number. */
    fun rate(text: String?): Double? =
        text?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * The ECB's euro reference rates, held by the app for the days it builds itself (standalone ENTSO-E
 * prices arrive in EUR). The same rule as the relay and Home Assistant: a day is converted with the
 * newest table dated on or before it, at most [MAX_AGE_DAYS] before it; no such table is no rate,
 * and a non-EUR day without a rate is not built.
 *
 * Terms (checked by the integration, 2026-10-30): the rates are published for information, free to
 * use when cited accurately; a day document names its rate's source and date (`fx_src`, `fx_date`).
 */
internal class EcbRates(
    private val store: KeyValueStore,
    private val http: HttpGet = PlainHttp,
    private val log: RelayLog = RelayLog.NONE
) {
    /**
     * The rate that converts [currency] on [day], asking the ECB first when [shouldFetch] says so. A
     * failed request changes nothing held.
     */
    fun rateFor(currency: String, day: LocalDate, now: Instant): FxRate? = synchronized(LOCK) {
        var tables = held()
        if (shouldFetch(tables, currency, day, now, lastAttempt())) {
            store.putString(KEY_ATTEMPT_AT, now.toEpochMilli().toString())
            fetchDaily()?.let { table ->
                tables = adopt(tables, table)
                store.putString(KEY_TABLES, encode(tables))
            }
        }
        rateIn(tables, currency, day)
    }

    /** The held tables, newest first. A stored entry that does not read as a table is dropped. */
    fun held(): List<EcbTable> {
        val body = store.getString(KEY_TABLES) ?: return emptyList()
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<EcbTable>()
        for (index in 0 until array.length()) {
            decode(array.optJSONObject(index))?.let(out::add)
        }
        return out.sortedByDescending { it.day }
    }

    private fun lastAttempt(): Long? = store.getString(KEY_ATTEMPT_AT)?.toLongOrNull()

    private fun fetchDaily(): EcbTable? {
        val answer = http.get(DAILY_URL, "application/xml", TIMEOUT_MS, MAX_BYTES)
        if (answer !is HttpAnswer.Response) {
            log.log(LogLevel.WARN, "ECB rates: no answer (${(answer as HttpAnswer.NoAnswer).kind})")
            return null
        }
        if (answer.status != 200) {
            log.log(LogLevel.WARN, "ECB rates: HTTP ${answer.status}")
            return null
        }
        val table = EcbParser.daily(answer.body)
        if (table == null) log.log(LogLevel.WARN, "ECB rates: not a complete reference table")
        else log.log(LogLevel.INFO, "ECB rates: table of ${table.day}")
        return table
    }

    companion object {
        const val DAILY_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        internal const val PREFS = "ecb_rates"
        internal const val KEY_TABLES = "tables"
        internal const val KEY_ATTEMPT_AT = "attempt_at"

        /** How far before a day its table may be and still convert it (the relay's `MaxDatedAge`). */
        const val MAX_AGE_DAYS = 7L

        /** Tables kept: the app converts only today and tomorrow, so ten covers any gap with room. */
        const val MAX_TABLES = 10

        /** The ECB publishes "at around 16:00 CET every working day"; the app asks twenty minutes later. */
        val ECB_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val PUBLICATION: LocalTime = LocalTime.of(16, 20)

        /** A held table that is merely behind is asked about at most hourly. */
        const val STALE_RETRY_MS = 60L * 60L * 1000L

        /**
         * With no usable table at all, a few minutes: one load asks for today and tomorrow, and a dead
         * network must not become a request per question.
         */
        const val MISSING_RETRY_MS = 5L * 60L * 1000L

        private const val TIMEOUT_MS = 20_000
        private const val MAX_BYTES = 1024 * 1024

        /** One fetch at a time, process-wide. */
        private val LOCK = Any()

        /** The table that converts [day]: the newest dated on or before it, no more than [MAX_AGE_DAYS] before. */
        fun tableFor(tables: List<EcbTable>, day: LocalDate): EcbTable? {
            val limit = day.minusDays(MAX_AGE_DAYS)
            return tables.filter { !it.day.isAfter(day) && !it.day.isBefore(limit) }.maxByOrNull { it.day }
        }

        /** [currency]'s rate for [day] from [tables], or `null`. */
        fun rateIn(tables: List<EcbTable>, currency: String, day: LocalDate): FxRate? {
            val table = tableFor(tables, day) ?: return null
            return table.values[currency]?.let { FxRate(it, table.day) }
        }

        /**
         * The newest table that should exist at [now]: the latest weekday whose publication has passed.
         * TARGET holidays are not known here; on one the held table simply stays behind.
         */
        fun expectedTable(now: Instant): LocalDate {
            var day = now.atZone(ECB_ZONE).toLocalDate()
            while (!(isWeekday(day) && !publication(day).isAfter(now))) day = day.minusDays(1)
            return day
        }

        private fun publication(day: LocalDate): Instant = ZonedDateTime.of(day, PUBLICATION, ECB_ZONE).toInstant()

        private fun isWeekday(day: LocalDate): Boolean =
            day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY

        /**
         * Whether to ask the ECB before converting [currency] on [day]: when no held table converts it
         * (at most every [MISSING_RETRY_MS]), or when the newest held table is older than the one that
         * should exist by now (at most every [STALE_RETRY_MS]).
         */
        fun shouldFetch(tables: List<EcbTable>, currency: String, day: LocalDate, now: Instant, lastAttemptMs: Long?): Boolean {
            val since = lastAttemptMs?.let { now.toEpochMilli() - it }
            if (rateIn(tables, currency, day) == null) return since == null || since < 0 || since >= MISSING_RETRY_MS
            val newest = tables.maxOf { it.day }
            return newest.isBefore(expectedTable(now)) && (since == null || since < 0 || since >= STALE_RETRY_MS)
        }

        /** [tables] with [table] added (a newer read of a date replaces the held one), newest [MAX_TABLES] kept. */
        fun adopt(tables: List<EcbTable>, table: EcbTable): List<EcbTable> =
            (tables.filter { it.day != table.day } + table).sortedByDescending { it.day }.take(MAX_TABLES)

        private fun encode(tables: List<EcbTable>): String = JSONArray().apply {
            for (table in tables) {
                put(JSONObject().put("date", table.day.toString()).put("values", JSONObject(table.values)))
            }
        }.toString()

        private fun decode(json: JSONObject?): EcbTable? {
            json ?: return null
            val day = runCatching { LocalDate.parse(json.optString("date")) }.getOrNull() ?: return null
            val raw = json.optJSONObject("values") ?: return null
            val values = LinkedHashMap<String, Double>()
            for (currency in raw.keys()) {
                val value = (raw.opt(currency) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
                values[currency] = value
            }
            return if (values.isEmpty()) null else EcbTable(day, values)
        }

        /** The app's holder, over its own preferences file. */
        fun forContext(context: Context): EcbRates =
            EcbRates(SharedPreferencesKeyValueStore(context, PREFS), PlainHttp, AndroidRelayLog)
    }
}
