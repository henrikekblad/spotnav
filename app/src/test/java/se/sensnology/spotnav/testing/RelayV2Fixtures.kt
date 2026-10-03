package se.sensnology.spotnav.testing

import se.sensnology.spotnav.prices.CatalogueParse
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.RelayAreasParser
import se.sensnology.spotnav.prices.RelayContractVersion

/**
 * The relay's own contract v2 documents (`src/test/resources/relay-v2/`, copied from the relay with a
 * README naming its commit), so these tests read what the relay publishes rather than an imitation.
 */
object RelayV2Fixtures {
    fun read(name: String): String =
        checkNotNull(RelayV2Fixtures::class.java.getResource("/relay-v2/$name")) { "no fixture $name" }.readText()

    /** The v2 list's areas, as the app holds them. */
    fun areas(): List<PriceMarket> {
        val parsed = RelayAreasParser.parse(read("areas-v2.json"), RelayContractVersion.V2)
        check(parsed is CatalogueParse.Ok) { "areas-v2.json does not parse: $parsed" }
        return parsed.catalogue.areas.map(PriceMarket::of)
    }

    fun area(id: String): PriceMarket = areas().first { it.id == id }

    /** A transport answering the v2 index and the day files under their relay names. */
    fun transport(
        dayFiles: List<String>,
        indexV2: String? = read("index-v2.json"),
        replace: Map<String, String> = emptyMap()
    ): FakeRelayTransport = FakeRelayTransport(
        indexV2 = indexV2,
        days = dayFiles.associate { file ->
            val (area, date) = file.removeSuffix(".json").split('_')
            "$area:$date" to (replace[file] ?: read(file))
        }
    )
}
