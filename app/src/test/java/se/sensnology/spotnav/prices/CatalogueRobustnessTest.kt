package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.RelayFixtures

/** One invalid entry is skipped, never the whole catalogue. */
class CatalogueRobustnessTest {
    private fun areas(vararg entries: String) =
        """{"v":2,"generated":"2026-10-03T10:00:00+02:00","areas":[${entries.joinToString(",")}]}"""

    private val good = RelayFixtures.areasBody(listOf(RelayFixtures.se4))
        .substringAfter("\"areas\":[").substringBeforeLast("]}")

    @Test
    fun `an invalid area is skipped and the rest kept`() {
        val parsed = RelayAreasParser.parse(areas("""{"id":"x"}""", "42", good))

        val ids = (parsed as CatalogueParse.Ok).catalogue.areas.map { it.id }
        assertEquals(listOf("SE4"), ids)
    }

    @Test
    fun `an id longer than 12 characters is accepted up to 32`() {
        val long = good.replace("\"SE4\"", "\"SE-LONG-ID-OF-TWENTY-CHARS\"")
        val tooLong = good.replace("\"SE4\"", "\"" + "A".repeat(33) + "\"")

        assertTrue(RelayAreasParser.parse(areas(long)) is CatalogueParse.Ok)
        assertTrue(RelayAreasParser.parse(areas(tooLong)) is CatalogueParse.Invalid)
        val mixed = RelayAreasParser.parse(areas(tooLong, good)) as CatalogueParse.Ok
        assertEquals(listOf("SE4"), mixed.catalogue.areas.map { it.id })
    }

    @Test
    fun `a duplicate id still refuses the catalogue`() {
        assertTrue(RelayAreasParser.parse(areas(good, good)) is CatalogueParse.Invalid)
    }
}
