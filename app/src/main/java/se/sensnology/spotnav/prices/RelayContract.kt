package se.sensnology.spotnav.prices

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.strictPositiveInt
import se.sensnology.spotnav.app.strictText
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The relay's wire documents as *shapes* plus the validation each must pass before anything else in
 * the app believes it: the area list and the index (`v2/…`, or `v1/…` from a relay that predates
 * contract v2) and `v1/{area}/{YYYY}/{MM-DD}.json`, which is the same document under both.
 */

/** The parts of a bill a published price may already contain (contract v2's `included`). */
enum class IncludedPart(val wire: String) {
    VAT("vat"),
    TAX("tax"),
    GRID_FEE("grid_fee");

    companion object {
        fun of(wire: Any?): IncludedPart? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where an area's prices come from, shown beside the area choice as attribution. */
data class AreaSource(val name: String, val url: String)

/**
 * When an area's prices for tomorrow are expected (contract v2's `publication`): a wall-clock [time]
 * in the zone [tz]. ENTSO-E areas publish about 13:00 Brussels, Octopus Agile about 16:00 UK time and
 * Spain's PVPC about 20:15 Madrid time. An area that states none is expected at [DEFAULT].
 */
data class AreaPublication(val time: LocalTime, val tz: String) {
    val zoneId: ZoneId get() = ZoneId.of(tz)

    /**
     * The expected instant on [day], built in [tz] before it becomes an instant, so a clock change
     * keeps the wall-clock time.
     */
    fun on(day: LocalDate): Instant = ZonedDateTime.of(day, time, zoneId).toInstant()

    companion object {
        /** The ENTSO-E day-ahead publication, and what a client assumes when an area states none. */
        val DEFAULT = AreaPublication(LocalTime.of(13, 0), "Europe/Brussels")
    }
}

internal data class RelayArea(
    val id: String,
    /** The bidding zone's EIC; `null` for an area that has none (a Great Britain region, v2 only). */
    val eic: String?,
    val countries: List<String>,
    val name: String,
    /** The zone a person reads this area's times in. */
    val tz: String,
    val currency: String,
    val majorUnit: String,
    val minorUnit: String,
    /** `null` means the relay has no figure; `0.0` is a real zero (NO4's VAT). */
    val vatPercent: Double?,
    val suggestedTax: Double?,
    val suggestedGridFee: Double?,
    /** The zone whose calendar day one day file covers; equal to [tz] unless a v2 list says otherwise. */
    val marketTz: String = tz,
    /** What the published price already contains; those parts are locked, never added. */
    val included: Set<IncludedPart> = emptySet(),
    /** The v2 list's attribution; `null` from a v1 list, which states none. */
    val source: AreaSource? = null,
    /** When tomorrow's prices are expected; the default from a v1 list or an area that states none. */
    val publication: AreaPublication = AreaPublication.DEFAULT
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

/** The two published contracts of the area list and the index. Day files are one document in both. */
internal object RelayContractVersion {
    const val V1 = 1
    const val V2 = 2
}

/** The resolutions this client can plan with: whole minutes that divide an hour, on the quarter-hour grid. */
internal val RELAY_RESOLUTIONS: Set<Int> = setOf(15, 30, 60)

/** An area id as the contract allows it: `[A-Z0-9-]{1,32}`. */
internal fun isValidAreaId(id: String): Boolean =
    id.isNotEmpty() && id.length <= RelayAreasParser.MAX_ID_LENGTH &&
        id.all { it in 'A'..'Z' || it in '0'..'9' || it == '-' }

internal object RelayAreasParser {
    const val SUPPORTED_VERSION = RelayContractVersion.V1
    const val MAX_ID_LENGTH = 32

    /**
     * Parse and validate a whole catalogue document of [version] (`/v1/areas.json` or `/v2/areas.json`):
     * a document must say the version it was asked for.
     */
    fun parse(body: String, version: Int = RelayContractVersion.V1): CatalogueParse {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return CatalogueParse.Invalid("not a JSON object")
        if (json.strictPositiveInt("v") != version) {
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
            // One bad entry is skipped, never the whole catalogue: the rest stays usable.
            val parsed = array.optJSONObject(index)?.let { parseArea(it, version) } ?: continue
            if (!seen.add(parsed.id)) return CatalogueParse.Invalid("duplicate area id ${parsed.id}")
            areas.add(parsed)
        }
        if (areas.isEmpty()) return CatalogueParse.Invalid("no valid area")
        return CatalogueParse.Ok(RelayCatalogue(version, generated, areas))
    }

    /** One entry, or `null` when anything required is missing, blank, the wrong shape, or unusable. */
    private fun parseArea(entry: JSONObject, version: Int): RelayArea? {
        val id = entry.strictText("id") ?: return null
        if (!isValidAreaId(id)) return null
        // Required in v1; optional in v2, where a Great Britain region has none. Present means a
        // real code, in either.
        val eic = entry.strictText("eic")
        if (eic == null && (version == RelayContractVersion.V1 || entry.has("eic"))) return null
        val name = entry.strictText("name") ?: return null
        val tz = entry.strictText("tz") ?: return null
        val currency = entry.strictText("currency") ?: return null
        val majorUnit = entry.strictText("major_unit") ?: return null
        val minorUnit = entry.strictText("minor_unit") ?: return null
        // A zone only counts when this runtime can construct it: a catalogue naming a zone Android
        // does not know would otherwise fail later, on a screen, with nothing to explain why.
        if (runCatching { ZoneId.of(tz) }.isFailure) return null

        // The v2 properties. A v1 list states none of them, and its zone is both calendars.
        var marketTz = tz
        var included = emptySet<IncludedPart>()
        var source: AreaSource? = null
        var publication = AreaPublication.DEFAULT
        if (version == RelayContractVersion.V2) {
            if (entry.has("market_tz")) {
                marketTz = entry.strictText("market_tz") ?: return null
                if (runCatching { ZoneId.of(marketTz) }.isFailure) return null
            }
            included = included(entry) ?: return null
            source = source(entry.opt("source")) ?: return null
            publication = publication(entry.opt("publication"))
        }

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
            suggestedGridFee = (gridFee as? Fiscal.Num)?.value,
            marketTz = marketTz,
            included = included,
            source = source,
            publication = publication
        )
    }

    private val PUBLICATION_TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")

    /**
     * `publication`: `{ "time": "HH:MM", "tz": "<IANA zone>" }`. Unlike the other v2 properties, one
     * this client cannot read never skips the area: it only says when to look, and the default
     * (13:00 Brussels) is still a safe answer, because a plan treats an overdue publication as one that
     * may arrive at any moment. Extra keys inside it are ignored.
     */
    private fun publication(raw: Any?): AreaPublication {
        val json = raw as? JSONObject ?: return AreaPublication.DEFAULT
        val time = (json.opt("time") as? String)?.takeIf { PUBLICATION_TIME.matches(it) }
            ?: return AreaPublication.DEFAULT
        val tz = json.strictText("tz") ?: return AreaPublication.DEFAULT
        if (runCatching { ZoneId.of(tz) }.isFailure) return AreaPublication.DEFAULT
        return AreaPublication(LocalTime.parse(time), tz)
    }

    /**
     * `included`: absent is none; anything present must be a list of known names, each once. An
     * unknown name skips the area rather than being ignored -- a client that does not know what the
     * price already holds would add it a second time.
     */
    private fun included(entry: JSONObject): Set<IncludedPart>? {
        if (!entry.has("included")) return emptySet()
        val array = entry.opt("included") as? JSONArray ?: return null
        val parts = LinkedHashSet<IncludedPart>()
        for (index in 0 until array.length()) {
            val part = IncludedPart.of(array.opt(index)) ?: return null
            if (!parts.add(part)) return null
        }
        return parts
    }

    /** `source`: a name to show and an http(s) address, or `null`. */
    private fun source(raw: Any?): AreaSource? {
        val json = raw as? JSONObject ?: return null
        val name = json.strictText("name") ?: return null
        val url = json.strictText("url") ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty()) return null
        return AreaSource(name, url)
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

/** `v1/index.json` or `v2/index.json`: which immutable day documents exist, per area. */
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
    const val SUPPORTED_VERSION = RelayContractVersion.V1

    private val DAY_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

    /** Parse an index of [version], or `null` when it is not one this client can use. */
    fun parse(body: String, version: Int = RelayContractVersion.V1): RelayIndex? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.strictPositiveInt("v") != version) return null
        val areas = json.optJSONObject("areas") ?: return null
        val days = HashMap<String, Set<String>>()
        val resolutions = HashMap<String, Int>()
        for (id in areas.keys()) {
            // An invalid area entry is skipped; the other areas keep their days.
            if (!isValidAreaId(id)) continue
            val entry = areas.optJSONObject(id) ?: continue
            val array = entry.optJSONArray("days") ?: continue
            val listed = ArrayList<String>(array.length())
            var valid = true
            for (index in 0 until array.length()) {
                val day = array.opt(index) as? String
                if (day == null || !DAY_PATTERN.matches(day)) {
                    valid = false
                    break
                }
                listed.add(day)
            }
            if (!valid) continue
            // A resolution this client cannot plan with skips the area, not the index.
            val res = entry.strictPositiveInt("res")
            if (entry.has("res") && res !in RELAY_RESOLUTIONS) continue
            days[id] = listed.toSet()
            res?.let { resolutions[id] = it }
        }
        return RelayIndex(
            v = version,
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
    /** The market calendar the file's date belongs to (`market_tz`, else `tz`). */
    val marketTz: String,
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
    val SUPPORTED_RESOLUTIONS: Set<Int> = RELAY_RESOLUTIONS

    /**
     * Parse one day document for [areaId] and [requestedDate]. [areaMarketTz] is the area's market
     * calendar, which the file's own `market_tz` (else its `tz`) must name: a Great Britain file is
     * shown in London and dated in Paris, every v1 zone's file is one calendar.
     */
    fun parse(body: String, areaId: String, areaMarketTz: String, areaCurrency: String, requestedDate: String): DayParse {
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
        val calendar = if (json.has("market_tz")) json.strictText("market_tz") else json.strictText("tz")
        if (calendar != areaMarketTz) {
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
                area = areaId, date = requestedDate, marketTz = areaMarketTz, start = start,
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
                out.add(PricePoint(instant.atZone(zone).toOffsetDateTime(), euro * document.fxRate, document.resMinutes))
            }
        }
        return out
    }
}
