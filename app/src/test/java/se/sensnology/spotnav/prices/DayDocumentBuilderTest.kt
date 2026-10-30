package se.sensnology.spotnav.prices

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.prices.entsoe.EntsoeParse
import se.sensnology.spotnav.prices.entsoe.EntsoeParser
import se.sensnology.spotnav.prices.entsoe.EntsoeRow
import se.sensnology.spotnav.testing.BundledAreas
import se.sensnology.spotnav.testing.RelayUpstream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class DayDocumentBuilderTest {
    /** The relay's frozen-day clock (`format_test.go` `fixedNow`). */
    private val retrieved = Instant.parse("2026-09-19T21:40:11Z")
    private val sek = FxRate(11.2915, LocalDate.parse("2026-09-18"))

    private fun parsed(file: String, id: String, date: String): EntsoeParse.Day =
        EntsoeParser.parse(RelayUpstream.read(file), BundledAreas.route(id), BundledAreas.zone(id), LocalDate.parse(date)) as EntsoeParse.Day

    private fun built(result: DayBuild): String = (result as DayBuild.Built).json

    @Test
    fun theSe4CaptureBuildsTheRelaysOwnDayFileByteForByte() {
        val day = parsed("entsoe_SE4_2026-09-21.xml", "SE4", "2026-09-21")
        val json = built(DayDocumentBuilder.build(BundledAreas.area("SE4"), LocalDate.parse("2026-09-21"), day, sek, retrieved))
        assertEquals(RelayUpstream.golden("v1_day_SE4.json"), json)
    }

    @Test
    fun aBuiltDocumentRoundTripsThroughTheRelayParser() {
        val se4 = BundledAreas.area("SE4")
        val day = parsed("entsoe_SE4_2026-09-21.xml", "SE4", "2026-09-21")
        val json = built(DayDocumentBuilder.build(se4, LocalDate.parse("2026-09-21"), day, sek, retrieved))

        val document = (RelayDayParser.parse(json, "SE4", se4.marketTz, se4.currency, "2026-09-21") as DayParse.Ok).document
        val points = RelayDayPoints.points(document, se4.zoneId)

        assertEquals(96, points.size)
        assertEquals(day.rows[0].start, points[0].start.toInstant())
        day.rows.forEachIndexed { i, row ->
            assertEquals("point $i is EUR x rate", DayDocumentBuilder.round(row.eur, 5) * 11.2915, points[i].pricePerKwh, 1e-12)
        }
    }

    @Test
    fun aNonEurDayWithoutARateIsNotBuilt() {
        val day = parsed("entsoe_SE4_2026-09-21.xml", "SE4", "2026-09-21")
        assertEquals(DayBuild.NoRate, DayDocumentBuilder.build(BundledAreas.area("SE4"), LocalDate.parse("2026-09-21"), day, null, retrieved))
    }

    @Test
    fun aEurDayCarriesAnEmptyRateAndNoRateDate() {
        val nl = BundledAreas.area("NL")
        val day = parsed("entsoe_NL_2026-09-21.xml", "NL", "2026-09-21")
        val json = JSONObject(built(DayDocumentBuilder.build(nl, LocalDate.parse("2026-09-21"), day, sek, retrieved)))
        assertEquals(0, json.getJSONObject("fx").length())
        assertFalse(json.has("fx_date"))
        assertFalse(json.has("market_tz"))
        assertEquals("Europe/Amsterdam", json.getString("tz"))
        assertTrue(RelayDayParser.parse(json.toString(), "NL", nl.marketTz, nl.currency, "2026-09-21") is DayParse.Ok)
    }

    @Test
    fun portugalIsShownInLisbonAndDatedInMadrid() {
        val pt = BundledAreas.area("PT")
        val madridMidnight = LocalDate.parse("2026-09-21").atStartOfDay(pt.marketZoneId).toInstant()
        val rows = (0 until 24).map { EntsoeRow(madridMidnight.plus(Duration.ofHours(it.toLong())), 0.05 + it / 1000.0) }
        val json = built(DayDocumentBuilder.build(pt, LocalDate.parse("2026-09-21"), EntsoeParse.Day(rows, null), null, retrieved))

        assertTrue(json, json.startsWith("{\"v\":1,\"area\":\"PT\",\"date\":\"2026-09-21\",\"tz\":\"Europe/Lisbon\",\"market_tz\":\"Europe/Madrid\",\"start\":\"2026-09-21T00:00:00+02:00\",\"res\":60,"))
        assertTrue(json, json.endsWith(",\"fx\":{},\"src\":\"entsoe.eu\",\"published\":null,\"retrieved\":\"2026-09-19T23:40:11+02:00\"}"))
        assertTrue(RelayDayParser.parse(json, "PT", pt.marketTz, pt.currency, "2026-09-21") is DayParse.Ok)
    }

    @Test
    fun numbersAreWrittenAsTheRelayWritesThem() {
        assertEquals("0", DayDocumentBuilder.number(0.0))
        assertEquals("-0", DayDocumentBuilder.number(-0.0))
        assertEquals("0.00001", DayDocumentBuilder.number(0.00001))
        assertEquals("-0.00125", DayDocumentBuilder.number(-0.00125))
        assertEquals("0.1", DayDocumentBuilder.number(0.1))
        assertEquals("11.2915", DayDocumentBuilder.number(11.2915))
        assertEquals("10", DayDocumentBuilder.number(10.0))
        assertEquals("364.28", DayDocumentBuilder.number(364.28))
    }

    @Test
    fun roundingIsHalfAwayFromZeroAndKeepsTheSignOfZero() {
        assertEquals(0.00002, DayDocumentBuilder.round(0.000015, 5), 0.0)
        assertEquals(-0.00002, DayDocumentBuilder.round(-0.000015, 5), 0.0)
        assertEquals("-0", DayDocumentBuilder.number(DayDocumentBuilder.round(-0.000004, 5)))
        assertEquals(0.09842, DayDocumentBuilder.round(98.42 / 1000, 5), 0.0)
    }

    @Test
    fun aDayOffItsGridIsNotBuilt() {
        val start = Instant.parse("2026-09-20T22:00:00Z")
        val rows = listOf(EntsoeRow(start, 0.1), EntsoeRow(start.plusSeconds(900), 0.1), EntsoeRow(start.plusSeconds(3600), 0.1))
        val nl = BundledAreas.area("NL")
        assertTrue(DayDocumentBuilder.build(nl, LocalDate.parse("2026-09-21"), EntsoeParse.Day(rows, null), null, retrieved) is DayBuild.Invalid)
        assertTrue(DayDocumentBuilder.build(nl, LocalDate.parse("2026-09-21"), EntsoeParse.Day(rows.take(1), null), null, retrieved) is DayBuild.Invalid)
    }
}
