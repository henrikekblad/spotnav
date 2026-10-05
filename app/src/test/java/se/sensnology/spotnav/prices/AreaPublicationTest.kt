package se.sensnology.spotnav.prices

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.RelayV2Fixtures
import java.time.LocalTime

/**
 * Contract v2's optional `publication` (`{ "time": "HH:MM", "tz": "<IANA zone>" }`): when an area's
 * prices for tomorrow are expected. Absent means 13:00 Brussels; a malformed one falls back to that
 * default and never costs the area its place in the list.
 */
class AreaPublicationTest {
    private val london = AreaPublication(LocalTime.of(16, 0), "Europe/London")
    private val madrid = AreaPublication(LocalTime.of(20, 15), "Europe/Madrid")

    private fun parsed(body: String, version: Int = RelayContractVersion.V2): List<PriceMarket> {
        val result = RelayAreasParser.parse(body, version)
        check(result is CatalogueParse.Ok) { "does not parse: $result" }
        return result.catalogue.areas.map(PriceMarket::of)
    }

    private fun gbRaw(): JSONObject {
        val areas = JSONObject(RelayV2Fixtures.read("areas-v2.json")).getJSONArray("areas")
        return (0 until areas.length()).map { areas.getJSONObject(it) }.first { it.getString("id") == "GB-C" }
    }

    private fun withAreas(vararg areas: JSONObject): String =
        JSONObject(RelayV2Fixtures.read("areas-v2.json")).put("areas", JSONArray(areas.toList())).toString()

    @Test
    fun theDefaultIsThirteenHundredBrussels() {
        assertEquals(AreaPublication(LocalTime.of(13, 0), "Europe/Brussels"), AreaPublication.DEFAULT)
    }

    @Test
    fun aStatedTimeIsReadInItsOwnZone() {
        assertEquals(london, RelayV2Fixtures.area("GB-C").publication)
        val built = parsed(RelayFixtures.areasV2Body(listOf(RelayFixtures.gbC, RelayFixtures.esPvpc, RelayFixtures.se4)))
        assertEquals(listOf(london, madrid, AreaPublication.DEFAULT), built.map { it.publication })
    }

    @Test
    fun anAreaWithoutOneIsExpectedAtTheDefault() {
        assertEquals(AreaPublication.DEFAULT, RelayV2Fixtures.area("PT").publication)
        assertEquals(AreaPublication.DEFAULT, RelayV2Fixtures.area("SE4").publication)
    }

    @Test
    fun aMalformedOneFallsBackToTheDefaultAndKeepsTheArea() {
        val malformed = listOf<Any>(
            "16:00",
            16,
            JSONArray(listOf("16:00", "Europe/London")),
            JSONObject.NULL,
            JSONObject(),
            JSONObject().put("time", "16:00"),
            JSONObject().put("tz", "Europe/London"),
            JSONObject().put("time", "16:00").put("tz", "Mars/Olympus"),
            JSONObject().put("time", "16:00").put("tz", ""),
            JSONObject().put("time", "16:00").put("tz", 1),
            JSONObject().put("time", "24:00").put("tz", "Europe/London"),
            JSONObject().put("time", "16:60").put("tz", "Europe/London"),
            JSONObject().put("time", "4:00").put("tz", "Europe/London"),
            JSONObject().put("time", "16:00:00").put("tz", "Europe/London"),
            JSONObject().put("time", " 16:00").put("tz", "Europe/London"),
            JSONObject().put("time", 1600).put("tz", "Europe/London"),
            JSONObject().put("time", "").put("tz", "Europe/London")
        )
        for (bad in malformed) {
            val areas = parsed(withAreas(gbRaw().put("publication", bad), gbRaw().put("id", "GB-D")))
            assertEquals(bad.toString(), listOf("GB-C", "GB-D"), areas.map { it.id })
            assertEquals(bad.toString(), AreaPublication.DEFAULT, areas[0].publication)
            assertEquals(london, areas[1].publication)
        }
    }

    @Test
    fun unknownExtraFieldsAreAcceptedInTheAreaAndInThePublication() {
        val extra = gbRaw()
            .put("some_future_field", JSONObject().put("x", 1))
            .put("publication", JSONObject().put("time", "16:00").put("tz", "Europe/London").put("window_min", 30))
        val area = parsed(withAreas(extra)).single()
        assertEquals("GB-C", area.id)
        assertEquals(london, area.publication)
    }

    @Test
    fun aV1ListNeverReadsIt() {
        val stray = JSONObject(RelayV2Fixtures.read("areas-v1.json"))
        stray.getJSONArray("areas").getJSONObject(0)
            .put("publication", JSONObject().put("time", "20:15").put("tz", "Europe/Madrid"))
        assertTrue(parsed(stray.toString(), RelayContractVersion.V1).all { it.publication == AreaPublication.DEFAULT })
    }

    @Test
    fun aHeldV2ListKeepsThePublicationAcrossARestart() {
        val store = FakeKeyValueStore()
        val body = RelayFixtures.areasV2Body(listOf(RelayFixtures.esPvpc))
        AreaCatalogue.refresh(store, { null }, 10_000_000L, true, fetchV2 = { RelayFetch.Body(body) })
        assertEquals(madrid, AreaCatalogue.startup(store, { null }).areas.single().publication)
    }
}
