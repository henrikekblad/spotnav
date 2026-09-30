package se.sensnology.spotnav.prices

import java.time.ZoneId
import java.util.Locale

/** One selectable area, as the relay's catalogue describes it. */
data class PriceMarket(
    val id: String,
    val countries: List<String>,
    val name: String,
    val tz: String,
    val currency: String,
    val majorUnit: String,
    val minorUnit: String,
    val vatPercent: Double? = null,
    val suggestedTax: Double? = null,
    val suggestedGridFee: Double? = null
) {
    /** The zone this area's days are rendered in. */
    val zoneId: ZoneId get() = ZoneId.of(tz)

    val selectorLabel: String get() = "$id – $name"

    /**
     * The unit of a price that has been through `WidgetSettings.apply`, which is `local major x
     * 100` plus the minor-unit tax and grid fee: the number is in the area's **minor** unit, so the
     * label is too.
     */
    val appliedPriceUnit: String get() = "$minorUnit/kWh"

    /** Whether this area covers [region] (an ISO 3166-1 alpha-2 code, case-insensitive). */
    fun covers(region: String): Boolean =
        region.isNotBlank() && countries.any { it.equals(region, ignoreCase = true) }

    /**
     * The heading this area is listed under: the local region when the area covers it, otherwise
     * the area's own first country.
     */
    fun heading(region: String): String =
        countries.firstOrNull { it.equals(region, ignoreCase = true) } ?: countries.first()

    companion object {
        /** The app's own shape for one validated catalogue entry. */
        internal fun of(area: RelayArea): PriceMarket = PriceMarket(
            id = area.id,
            countries = area.countries,
            name = area.name,
            tz = area.tz,
            currency = area.currency,
            majorUnit = area.majorUnit,
            minorUnit = area.minorUnit,
            vatPercent = area.vatPercent,
            suggestedTax = area.suggestedTax,
            suggestedGridFee = area.suggestedGridFee
        )
    }
}

/**
 * The catalogue the app is currently using: the last valid remote document, or the bundled
 * snapshot, or both refusing to load.
 */
object PriceMarkets {
    @Volatile
    private var current: List<PriceMarket> = emptyList()

    /** Every area currently selectable, in catalogue order. */
    val all: List<PriceMarket> get() = current

    /** Replace the catalogue atomically. */
    fun replace(areas: List<PriceMarket>) {
        current = areas
    }

    /** The area with this id, or `null`. */
    fun find(area: String): PriceMarket? = current.firstOrNull { it.id == area }
}

/**
 * How an area is chosen and listed, as pure functions of a catalogue and a region, so every rule
 * below is a JVM test rather than something only visible on a phone.
 */
internal object AreaSelection {
    /**
     * The defaults today's regions already had, each used only when that exact id exists in the
     * catalogue: a default that names an area the relay does not serve is not a default, it is the
     * bug this replaces.
     */
    private val LOCALE_DEFAULTS = mapOf("SE" to "SE4", "NO" to "NO1", "DK" to "DK1", "FI" to "FI")

    private const val FALLBACK_ID = "SE4"

    /** The area a person in [region] gets with nothing saved. */
    fun defaultArea(areas: List<PriceMarket>, region: String): String? {
        if (areas.isEmpty()) return null
        val upper = region.uppercase(Locale.ROOT)
        LOCALE_DEFAULTS[upper]?.let { preferred ->
            areas.firstOrNull { it.id == preferred }?.let { return it.id }
        }
        areas.firstOrNull { it.covers(upper) }?.let { return it.id }
        areas.firstOrNull { it.id == FALLBACK_ID }?.let { return it.id }
        return areas.first().id
    }

    /**
     * The picker's order: areas covering the local region first, then the rest, each group in
     * catalogue order. The list is stable, so an area does not move around under a person's finger
     * when a refresh changes nothing.
     */
    fun orderedForPicker(areas: List<PriceMarket>, region: String): List<PriceMarket> {
        val (local, rest) = areas.partition { it.covers(region) }
        return local + rest
    }

    /** Headings and the areas under them, in first-appearance order over [orderedForPicker]. */
    fun grouped(areas: List<PriceMarket>, region: String): List<Pair<String, List<PriceMarket>>> =
        orderedForPicker(areas, region).groupBy { it.heading(region) }.toList()

    /** A country's name in the person's own language, from Java's locale data. */
    fun countryLabel(country: String, locale: Locale): String {
        val upper = country.uppercase(Locale.ROOT)
        val name = runCatching { Locale("", upper).getDisplayCountry(locale) }.getOrNull()
        return if (name.isNullOrBlank() || name.equals(upper, ignoreCase = true)) upper else name
    }
}
