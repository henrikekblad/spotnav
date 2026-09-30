package se.sensnology.spotnav.prices

import org.json.JSONObject
import se.sensnology.spotnav.app.strictPositiveInt
import se.sensnology.spotnav.app.strictText
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The relay's wire documents as *shapes* plus the validation each must pass before anything else in
 * the app believes it: `v1/areas.json`, `v1/index.json` and `v1/{area}/{YYYY}/{MM-DD}.json`.
 */
internal data class RelayArea(
    val id: String,
    val eic: String,
    val countries: List<String>,
    val name: String,
    val tz: String,
    val currency: String,
    val majorUnit: String,
    val minorUnit: String,
    /** `null` means the relay has no figure; `0.0` is a real zero (NO4's VAT). */
    val vatPercent: Double?,
    val suggestedTax: Double?,
    val suggestedGridFee: Double?
) {
    /** The zone, or `null` if this runtime cannot construct what the relay named. */
    val zoneId: ZoneId? get() = runCatching { ZoneId.of(tz) }.getOrNull()
}

internal data class RelayCatalogue(val v: Int, val generated: String, val areas: List<RelayArea>)

/** Why a candidate catalogue was refused. A reason, never a body or a stack. */
internal sealed interface CatalogueParse {
    data class Ok(val catalogue: RelayCatalogue) : CatalogueParse
    data class Invalid(val reason: String) : CatalogueParse
}

/** A field that is either a number or genuinely absent; `null` from a parser means invalid. */
private sealed interface Fiscal {
    data class Num(val value: Double) : Fiscal
    data object Absent : Fiscal
}

internal object RelayAreasParser {
    const val SUPPORTED_VERSION = 1
    const val MAX_ID_LENGTH = 12

    /** Parse and validate a whole catalogue document. */
    fun parse(body: String): CatalogueParse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return CatalogueParse.Invalid("not a JSON object")
        if (json.strictPositiveInt("v") != SUPPORTED_VERSION) {
            return CatalogueParse.Invalid("unsupported version")
        }
        val generated = json.strictText("generated")
            ?: return CatalogueParse.Invalid("generated missing or blank")
        val array = json.optJSONArray("areas")
            ?: return CatalogueParse.Invalid("areas missing or not an array")
        if (array.length() == 0) return CatalogueParse.Invalid("areas is empty")

        val areas = ArrayList<RelayArea>(array.length())
        val seen = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index)
                ?: return CatalogueParse.Invalid("area $index is not an object")
            val parsed = parseArea(entry) ?: return CatalogueParse.Invalid("area $index is invalid")
            if (!seen.add(parsed.id)) return CatalogueParse.Invalid("duplicate area id ${parsed.id}")
            areas.add(parsed)
        }
        return CatalogueParse.Ok(RelayCatalogue(SUPPORTED_VERSION, generated, areas))
    }

    /** One entry, or `null` when anything required is missing, blank, the wrong shape, or unusable. */
    private fun parseArea(entry: JSONObject): RelayArea? {
        val id = entry.strictText("id") ?: return null
        if (id.length > MAX_ID_LENGTH) return null
        if (id.any { it !in 'A'..'Z' && it !in '0'..'9' && it != '-' }) return null
        val eic = entry.strictText("eic") ?: return null
        val name = entry.strictText("name") ?: return null
        val tz = entry.strictText("tz") ?: return null
        val currency = entry.strictText("currency") ?: return null
        val majorUnit = entry.strictText("major_unit") ?: return null
        val minorUnit = entry.strictText("minor_unit") ?: return null
        // A zone only counts when this runtime can construct it: a catalogue naming a zone Android
        // does not know would otherwise fail later, on a screen, with nothing to explain why.
        if (runCatching { ZoneId.of(tz) }.isFailure) return null

        val countries = entry.optJSONArray("countries") ?: return null
        if (countries.length() == 0) return null
        val countryList = ArrayList<String>(countries.length())
        for (index in 0 until countries.length()) {
            val country = (countries.opt(index) as? String)?.trim()
            if (country.isNullOrEmpty()) return null
            countryList.add(country)
        }

        val vat = fiscal(entry, "vat_percent") ?: return null
        val tax = fiscal(entry, "suggested_tax") ?: return null
        val gridFee = fiscal(entry, "suggested_grid_fee") ?: return null

        return RelayArea(
            id = id, eic = eic, countries = countryList, name = name, tz = tz,
            currency = currency, majorUnit = majorUnit, minorUnit = minorUnit,
            vatPercent = (vat as? Fiscal.Num)?.value,
            suggestedTax = (tax as? Fiscal.Num)?.value,
            suggestedGridFee = (gridFee as? Fiscal.Num)?.value
        )
    }

    /**
     * A fiscal field: an omitted key is a real, expected state; anything *present* must be a finite
     * number, so a string, a boolean and an explicit JSON `null` are all invalid.
     */
    private fun fiscal(entry: JSONObject, key: String): Fiscal? {
        if (!entry.has(key)) return Fiscal.Absent
        if (entry.isNull(key)) return null
        val number = (entry.opt(key) as? Number)?.toDouble() ?: return null
        return if (number.isFinite()) Fiscal.Num(number) else null
    }
}

/** `v1/index.json`: which immutable day documents exist, per area. */
internal data class RelayIndex(
    val v: Int,
    val generated: String,
    val areasRev: String,
    val resDefault: Int?,
    val daysByArea: Map<String, Set<String>>,
    val resByArea: Map<String, Int>
) {
    fun lists(area: String, date: String): Boolean = daysByArea[area]?.contains(date) == true
}

internal object RelayIndexParser {
    const val SUPPORTED_VERSION = 1

    private val DAY_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    /** Parse an index, or `null` when it is not one this client can use. */
    fun parse(body: String): RelayIndex? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.strictPositiveInt("v") != SUPPORTED_VERSION) return null
        val areas = json.optJSONObject("areas") ?: return null
        val days = HashMap<String, Set<String>>()
        val resolutions = HashMap<String, Int>()
        for (id in areas.keys()) {
            val entry = areas.optJSONObject(id) ?: return null
            val array = entry.optJSONArray("days") ?: return null
            val listed = ArrayList<String>(array.length())
            for (index in 0 until array.length()) {
                val day = array.opt(index) as? String ?: return null
                if (!DAY_PATTERN.matches(day)) return null
                listed.add(day)
            }
            days[id] = listed.toSet()
            entry.strictPositiveInt("res")?.let { resolutions[id] = it }
        }
        return RelayIndex(
            v = SUPPORTED_VERSION,
            generated = json.strictText("generated").orEmpty(),
            areasRev = json.strictText("areas_rev").orEmpty(),
            resDefault = json.strictPositiveInt("res_default"),
            daysByArea = days,
            resByArea = resolutions
        )
    }
}

/** `v1/{area}/{YYYY}/{MM-DD}.json`: one immutable day of **positional** EUR prices. */
internal data class RelayDayDocument(
    val area: String,
    val date: String,
    val tz: String,
    val start: OffsetDateTime,
    val resMinutes: Int,
    val prices: List<Double>,
    /** EUR to the area's local major unit. Exactly 1.0 for a EUR area. */
    val fxRate: Double
)

internal sealed interface DayParse {
    data class Ok(val document: RelayDayDocument) : DayParse
    data class Invalid(val reason: String) : DayParse
}

internal object RelayDayParser {
    const val SUPPORTED_VERSION = 1
    const val UNIT = "EUR/kWh"

    /** What this client can lay out on a quarter-hour grid. */
    val SUPPORTED_RESOLUTIONS = setOf(15, 60)

    /** Parse one day document for [area] and [requestedDate]. */
    fun parse(body: String, areaId: String, areaTz: String, areaCurrency: String, requestedDate: String): DayParse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return DayParse.Invalid("not a JSON object")
        if (json.strictPositiveInt("v") != SUPPORTED_VERSION) {
            return DayParse.Invalid("unsupported version")
        }
        if (json.strictText("area") != areaId) {
            return DayParse.Invalid("area does not match the request")
        }
        if (json.strictText("date") != requestedDate) {
            return DayParse.Invalid("date does not match the request")
        }
        if (json.strictText("unit") != UNIT) {
            return DayParse.Invalid("unit is not $UNIT")
        }
        if (json.strictText("tz") != areaTz) {
            return DayParse.Invalid("timezone does not match the catalogue")
        }
        val res = json.strictPositiveInt("res") ?: return DayParse.Invalid("res missing or invalid")
        if (res !in SUPPORTED_RESOLUTIONS) {
            return DayParse.Invalid("unsupported resolution $res")
        }
        val start = json.strictText("start")
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?: return DayParse.Invalid("start is not an offset timestamp")

        val array = json.optJSONArray("prices") ?: return DayParse.Invalid("prices missing")
        if (array.length() == 0) return DayParse.Invalid("prices is empty")
        val prices = ArrayList<Double>(array.length())
        for (index in 0 until array.length()) {
            val value = (array.opt(index) as? Number)?.toDouble()
                ?: return DayParse.Invalid("price $index is not a number")
            if (!value.isFinite()) return DayParse.Invalid("price $index is not finite")
            prices.add(value)
        }

        // EUR needs no rate at all -- inventing one, or requiring a 1.0 to be published, would both
        // turn the identity currency into an arithmetic special case.
        val fxRate = if (areaCurrency == EUR_ISO) {
            1.0
        } else {
            val fx = json.optJSONObject("fx")
                ?: return DayParse.Invalid("fx missing for a non-EUR area")
            val rate = (fx.opt(areaCurrency) as? Number)?.toDouble()
                ?: return DayParse.Invalid("fx has no $areaCurrency")
            if (!rate.isFinite() || rate <= 0.0) {
                return DayParse.Invalid("fx rate for $areaCurrency is not a positive finite number")
            }
            rate
        }

        return DayParse.Ok(
            RelayDayDocument(
                area = areaId, date = requestedDate, tz = areaTz, start = start,
                resMinutes = res, prices = prices, fxRate = fxRate
            )
        )
    }

    private const val EUR_ISO = "EUR"
}

/** One day document's positional prices, laid out on the app's own quarter-hour grid. */
internal object RelayDayPoints {
    /** The grid every consumer of [PricePoint] already assumes. */
    const val STEP_MINUTES = 15L

    fun points(document: RelayDayDocument, zone: ZoneId): List<PricePoint> {
        val perRow = document.resMinutes / STEP_MINUTES.toInt()
        val out = ArrayList<PricePoint>(document.prices.size * perRow)
        val base = document.start.toInstant()
        document.prices.forEachIndexed { index, euro ->
            val rowStart = base.plus((index * document.resMinutes).toLong(), ChronoUnit.MINUTES)
            for (step in 0 until perRow) {
                val instant = rowStart.plus(step * STEP_MINUTES, ChronoUnit.MINUTES)
                out.add(PricePoint(instant.atZone(zone).toOffsetDateTime(), euro * document.fxRate))
            }
        }
        return out
    }
}
