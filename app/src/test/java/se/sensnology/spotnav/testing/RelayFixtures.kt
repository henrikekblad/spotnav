package se.sensnology.spotnav.testing

import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.HttpRelayTransport
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.RelayTransport
import java.time.LocalDate

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
        suggestedGridFee: Double? = null
    ) = PriceMarket(
        id = id, countries = countries, name = "$id name", tz = tz, currency = currency,
        majorUnit = majorUnit, minorUnit = minorUnit,
        vatPercent = vatPercent, suggestedTax = suggestedTax, suggestedGridFee = suggestedGridFee
    )

    val se4 = area("SE4", "SEK", "kr", "öre", vatPercent = 25.0, suggestedTax = 36.0, suggestedGridFee = 30.0)
    val no1 = area("NO1", "NOK", "kr", "øre", tz = "Europe/Oslo", vatPercent = 25.0, suggestedTax = 7.13)
    val no4 = area("NO4", "NOK", "kr", "øre", tz = "Europe/Oslo", vatPercent = 0.0, suggestedTax = 7.13)
    val fi = area("FI", "EUR", "€", "cent", tz = "Europe/Helsinki", vatPercent = 25.5, suggestedTax = 2.325)
    val dk1 = area("DK1", "DKK", "kr", "øre", tz = "Europe/Copenhagen", vatPercent = 25.0, suggestedTax = 0.8)

    /** A catalogue document in the relay's own shape, from [areas]. */
    fun areasBody(areas: List<PriceMarket>, generated: String = "2026-09-20T13:10:42+02:00"): String =
        buildString {
            append("{\"v\":1,\"generated\":\"").append(generated).append("\",\"areas\":[")
            areas.forEachIndexed { index, a ->
                if (index > 0) append(',')
                append("{\"id\":\"").append(a.id).append("\",\"eic\":\"10YTEST---------\",\"countries\":[")
                a.countries.forEachIndexed { i, c -> if (i > 0) append(','); append('"').append(c).append('"') }
                append("],\"name\":\"").append(a.name).append("\",\"tz\":\"").append(a.tz)
                append("\",\"currency\":\"").append(a.currency)
                append("\",\"major_unit\":\"").append(a.majorUnit)
                append("\",\"minor_unit\":\"").append(a.minorUnit).append('"')
                a.vatPercent?.let { append(",\"vat_percent\":").append(it) }
                a.suggestedTax?.let { append(",\"suggested_tax\":").append(it) }
                a.suggestedGridFee?.let { append(",\"suggested_grid_fee\":").append(it) }
                append('}')
            }
            append("]}")
        }

    /** An index listing [days] for [areaIds], with an overridable version. */
    fun indexBody(
        areaIds: List<String>,
        days: List<String>,
        v: Int = 1,
        res: Int? = null,
        areasRev: String = "26b6b50bef69"
    ): String = buildString {
        append("{\"v\":").append(v).append(",\"generated\":\"2026-09-20T13:10:42+02:00\"")
        append(",\"res_default\":60,\"areas_rev\":\"").append(areasRev).append("\",\"areas\":{")
        areaIds.forEachIndexed { index, id ->
            if (index > 0) append(',')
            append('"').append(id).append("\":{\"days\":[")
            days.forEachIndexed { i, d -> if (i > 0) append(','); append('"').append(d).append('"') }
            append(']')
            res?.let { append(",\"res\":").append(it) }
            append('}')
        }
        append("}}")
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
 * A transport that answers with whatever a test handed it and records every URL it was asked for --
 * which is how "tomorrow was never requested" is asserted rather than assumed.
 */
class FakeRelayTransport(
    private val areas: String? = null,
    private val index: String? = null,
    private val days: Map<String, String> = emptyMap(),
    private val failDays: Boolean = false
) : RelayTransport {
    val areasRequests = mutableListOf<String>()
    val indexRequests = mutableListOf<String>()
    val dayRequests = mutableListOf<String>()

    override fun areas(): String? {
        areasRequests.add(AreaCatalogue.AREAS_URL)
        return areas
    }

    override fun index(): String? {
        indexRequests.add(HttpRelayTransport.INDEX_URL)
        return index
    }

    override fun day(areaId: String, dateKey: String): String? {
        dayRequests.add(HttpRelayTransport.dayUrl(areaId, dateKey))
        if (failDays) return null
        return days["$areaId:$dateKey"]
    }
}

/** A fixed local date, so no test depends on when it runs. */
val TEST_TODAY: LocalDate = LocalDate.parse("2026-09-20")
