package se.sensnology.spotnav.testing

import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.EntsoeRoute
import se.sensnology.spotnav.prices.PriceMarket
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The app's bundled area list, read from the source tree as the app ships it. */
internal object BundledAreas {
    val all: List<PriceMarket> by lazy {
        AreaCatalogue.bundledAreas(File("src/main/assets/${AreaCatalogue.BUNDLED_ASSET}").readText())
    }

    fun area(id: String): PriceMarket = all.single { it.id == id }

    fun route(id: String): EntsoeRoute = checkNotNull(area(id).entsoe) { "$id has no ENTSO-E route" }

    /** The calendar an area's ENTSO-E day is asked in: its market zone. */
    fun zone(id: String): ZoneId = area(id).marketZoneId

    /** Local midnight of [date] in [id]'s market zone, as an instant. */
    fun midnight(id: String, date: String) = LocalDate.parse(date).atStartOfDay(zone(id)).toInstant()
}
