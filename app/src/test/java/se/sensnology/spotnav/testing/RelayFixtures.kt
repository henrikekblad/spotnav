package se.sensnology.spotnav.testing

import org.json.JSONObject
import se.sensnology.spotnav.prices.AreaPublication
import se.sensnology.spotnav.prices.AreaSource
import se.sensnology.spotnav.prices.DayFetch
import se.sensnology.spotnav.prices.DaySource
import se.sensnology.spotnav.prices.PriceFailure
import se.sensnology.spotnav.prices.PriceMarket
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Fixtures for the relay-era price tests: the wire documents, and the areas they describe, built
 * here rather than read from the app's own assets so a test can say exactly what it is testing.
 */
object RelayFixtures {
    fun area(
        id: String,
        currency: String,
        majorUnit: String,
        minorUnit: String,
        tz: String = "Europe/Stockholm",
        countries: List<String> = listOf(id.take(2)),
        vatPercent: Double? = 25.0,
        suggestedTax: Double? = null,
        suggestedGridFee: Double? = null,
        marketTz: String = tz,
        publication: AreaPublication = AreaPublication.DEFAULT
    ) = PriceMarket(
        id = id, countries = countries, name = "$id name", tz = tz, currency = currency,
        majorUnit = majorUnit, minorUnit = minorUnit,
        vatPercent = vatPercent, suggestedTax = suggestedTax, suggestedGridFee = suggestedGridFee,
        marketTz = marketTz, publication = publication
    )

    val se4 = area("SE4", "SEK", "kr", "öre", vatPercent = 25.0, suggestedTax = 36.0, suggestedGridFee = 30.0)
    val no1 = area("NO1", "NOK", "kr", "øre", tz = "Europe/Oslo", vatPercent = 25.0, suggestedTax = 7.13)
    val no4 = area("NO4", "NOK", "kr", "øre", tz = "Europe/Oslo", vatPercent = 0.0, suggestedTax = 7.13)
    val fi = area("FI", "EUR", "€", "cent", tz = "Europe/Helsinki", vatPercent = 25.5, suggestedTax = 2.325)
    val dk1 = area("DK1", "DKK", "kr", "øre", tz = "Europe/Copenhagen", vatPercent = 25.0, suggestedTax = 0.8)

    /** Octopus Agile: shown in London, dated on the Paris calendar, published about 16:00 UK time. */
    val gbC = area(
        "GB-C", "GBP", "£", "p", tz = "Europe/London", countries = listOf("GB"), vatPercent = null,
        marketTz = "Europe/Paris", publication = AreaPublication(LocalTime.of(16, 0), "Europe/London")
    )

    /** Spain's regulated PVPC, published about 20:15 Madrid time. */
    val esPvpc = area(
        "ES-PVPC", "EUR", "€", "cent", tz = "Europe/Madrid", countries = listOf("ES"), vatPercent = 21.0,
        publication = AreaPublication(LocalTime.of(20, 15), "Europe/Madrid")
    )

    /**
     * A contract v2 area list from [areas], written in a fixed key order so a test can edit it as text:
     * their `market_tz` when it differs, `publication` for an area whose time is not the default (the
     * default is left out, as an area that states none), and a `source`.
     */
    fun areasBody(areas: List<PriceMarket>, generated: String = "2026-10-05T20:22:35+02:00"): String = buildString {
        append("{\"v\":2,\"generated\":\"").append(generated).append("\",\"areas\":[")
        areas.forEachIndexed { index, a ->
            if (index > 0) append(',')
            append("{\"id\":\"").append(a.id).append("\",\"eic\":\"10YTEST---------\",\"countries\":[")
            a.countries.forEachIndexed { i, c -> if (i > 0) append(','); append('"').append(c).append('"') }
            append("],\"name\":\"").append(a.name).append("\",\"tz\":\"").append(a.tz).append('"')
            if (a.marketTz != a.tz) append(",\"market_tz\":\"").append(a.marketTz).append('"')
            append(",\"currency\":\"").append(a.currency)
            append("\",\"major_unit\":\"").append(a.majorUnit)
            append("\",\"minor_unit\":\"").append(a.minorUnit).append('"')
            a.vatPercent?.let { append(",\"vat_percent\":").append(it) }
            a.suggestedTax?.let { append(",\"suggested_tax\":").append(it) }
            a.suggestedGridFee?.let { append(",\"suggested_grid_fee\":").append(it) }
            if (a.publication != AreaPublication.DEFAULT) {
                append(",\"publication\":{\"time\":\"").append(a.publication.time).append("\",\"tz\":\"")
                    .append(a.publication.tz).append("\"}")
            }
            val source = a.source ?: AreaSource("${a.id} source", "https://example.org/${a.id}")
            append(",\"source\":").append(JSONObject().put("name", source.name).put("url", source.url))
            append('}')
        }
        append("]}")
    }

    /**
     * One day document: positional prices, `start` carrying the area's own offset, and an `fx`
     * object for a non-EUR area.
     */
    fun dayBody(
        area: PriceMarket,
        date: String,
        prices: List<Double>,
        start: String,
        res: Int = 15,
        unit: String = "EUR/kWh",
        tz: String = area.tz,
        dateField: String = date,
        idField: String = area.id,
        fxRate: Double? = 10.0,
        fxCurrency: String = area.currency
    ): String = buildString {
        append("{\"v\":1,\"area\":\"").append(idField).append("\",\"date\":\"").append(dateField)
        append("\",\"unit\":\"").append(unit).append("\",\"tz\":\"").append(tz)
        append("\",\"start\":\"").append(start).append("\",\"res\":").append(res)
        append(",\"prices\":[")
        prices.forEachIndexed { i, p -> if (i > 0) append(','); append(p) }
        append(']')
        fxRate?.let { append(",\"fx\":{\"").append(fxCurrency).append("\":").append(it).append('}') }
        append(",\"fx_date\":\"").append(date).append("\",\"src\":\"test\"}")
    }

    /** Quarter-hour prices for one whole day, so a count is meaningful. */
    fun prices(count: Int, base: Double = 0.10): List<Double> =
        (0 until count).map { base + it * 0.0001 }
}

/**
 * A day source that answers with whatever a test handed it and records every day it was asked for --
 * which is how "tomorrow was never requested" is asserted rather than assumed. A day is worth asking
 * for when it is in [listed] (every day in [days] when that is `null`); [failDays] fails every request.
 */
internal class FakeDaySource(
    private val days: Map<String, String> = emptyMap(),
    private val listed: Set<String>? = null,
    private val failDays: Boolean = false
) : DaySource {
    /** Every `AREA:date` the repository asked whether it is worth a request. */
    val questions = mutableListOf<String>()

    /** Every `AREA:date` actually requested. */
    val dayRequests = mutableListOf<String>()

    override fun worthAsking(area: PriceMarket, marketDay: LocalDate, now: Instant, force: Boolean, held: Boolean): Boolean {
        val key = "${area.id}:$marketDay"
        questions.add(key)
        return key in (listed ?: days.keys)
    }

    override fun fetch(area: PriceMarket, marketDay: LocalDate): DayFetch {
        val key = "${area.id}:$marketDay"
        dayRequests.add(key)
        if (failDays) return DayFetch.Failed(PriceFailure.NETWORK)
        return days[key]?.let(DayFetch::Body) ?: DayFetch.NotPublished
    }
}

/** A fixed local date, so no test depends on when it runs. */
val TEST_TODAY: LocalDate = LocalDate.parse("2026-09-20")
