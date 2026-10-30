package se.sensnology.spotnav.testing

import se.sensnology.spotnav.prices.CatalogueParse
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.RelayAreasParser

/**
 * The relay's own contract v2 documents (`src/test/resources/relay-v2/`, copied from the relay with a
 * README naming its commit), so these tests read what the relay publishes rather than an imitation.
 */
object RelayV2Fixtures {
    fun read(name: String): String =
        checkNotNull(RelayV2Fixtures::class.java.getResource("/relay-v2/$name")) { "no fixture $name" }.readText()

    /** The v2 list's areas, as the app holds them. */
    fun areas(): List<PriceMarket> {
        val parsed = RelayAreasParser.parse(read("areas-v2.json"))
        check(parsed is CatalogueParse.Ok) { "areas-v2.json does not parse: $parsed" }
        return parsed.catalogue.areas.map(PriceMarket::of)
    }

    fun area(id: String): PriceMarket = areas().first { it.id == id }

    /** A day source answering the day files under their relay names, every one of them worth asking for. */
    internal fun source(dayFiles: List<String>, replace: Map<String, String> = emptyMap(), failDays: Boolean = false): FakeDaySource =
        FakeDaySource(
            days = dayFiles.associate { file ->
                val (area, date) = file.removeSuffix(".json").split('_')
                "$area:$date" to (replace[file] ?: read(file))
            },
            failDays = failDays
        )
}
