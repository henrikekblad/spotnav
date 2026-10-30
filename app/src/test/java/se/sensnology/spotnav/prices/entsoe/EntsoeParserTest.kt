package se.sensnology.spotnav.prices.entsoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.sensnology.spotnav.prices.EntsoeRoute
import se.sensnology.spotnav.testing.BundledAreas
import se.sensnology.spotnav.testing.RelayUpstream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * The relay's parser tests (`entsoe_test.go`, `entsoe_a03_test.go`, `entsoe_mixed_test.go`,
 * `entsoe_sequence_test.go`, `entsoe_at_ie_test.go`), ported against the app's bundled routes.
 */
class EntsoeParserTest {
    // helpers

    private fun parse(body: String, id: String, date: String, route: EntsoeRoute = BundledAreas.route(id)): EntsoeParse =
        EntsoeParser.parse(body, route, BundledAreas.zone(id), LocalDate.parse(date))

    private fun day(body: String, id: String, date: String, route: EntsoeRoute = BundledAreas.route(id)): EntsoeParse.Day {
        val parsed = parse(body, id, date, route)
        if (parsed !is EntsoeParse.Day) fail("not a day: $parsed")
        return parsed as EntsoeParse.Day
    }

    private fun refused(body: String, id: String, date: String, route: EntsoeRoute = BundledAreas.route(id)): String {
        val parsed = parse(body, id, date, route)
        if (parsed !is EntsoeParse.Refused) fail("not refused: $parsed")
        return (parsed as EntsoeParse.Refused).reason
    }

    private fun fixture(name: String, id: String, date: String) = day(RelayUpstream.read(name), id, date)

    private fun same(got: Double, want: Double, what: String) =
        assertTrue("$what = $got, want $want", abs(got - want) <= 1e-12)

    private fun assertMentions(reason: String, vararg wanted: String) {
        for (want in wanted) assertTrue("\"$reason\" should mention \"$want\"", reason.contains(want))
        for (forbidden in listOf("http", "securityToken")) assertFalse("\"$reason\" carries $forbidden", reason.contains(forbidden))
    }

    private fun withSequence(body: String, label: String) = body.replaceFirst(
        "<curveType>A01</curveType>",
        "<curveType>A01</curveType><classificationSequence_AttributeInstanceComponent.position>$label</classificationSequence_AttributeInstanceComponent.position>"
    )

    private fun entsoeBody(eic: String, start: String, end: String, resolution: String, prices: List<String>): String {
        val points = prices.withIndex().joinToString("") { (i, price) ->
            "<Point><position>${i + 1}</position><price.amount>$price</price.amount></Point>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
  <type>A44</type>
  <createdDateTime>2026-09-18T12:31:00Z</createdDateTime>
  <period.timeInterval><start>$start</start><end>$end</end></period.timeInterval>
  <TimeSeries>
    <in_Domain.mRID>$eic</in_Domain.mRID>
    <out_Domain.mRID>$eic</out_Domain.mRID>
    <currency_Unit.name>EUR</currency_Unit.name>
    <price_Measure_Unit.name>MWh</price_Measure_Unit.name>
    <curveType>A01</curveType>
    <Period>
      <timeInterval><start>$start</start><end>$end</end></timeInterval>
      <resolution>$resolution</resolution>
      $points
    </Period>
  </TimeSeries>
</Publication_MarketDocument>"""
    }

    /** One series of a hand-built document: its label, per-slot values ("" unstated) and an optional period end. */
    private data class Series(val sequence: String, val values: List<String>, val end: String? = null)

    private fun entsoeDoc(eic: String, start: String, end: String, resolution: String, curve: String, vararg series: Series): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<Publication_MarketDocument xmlns=\"urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3\">\n")
        out.append("  <type>A44</type>\n")
        for (s in series) {
            out.append("  <TimeSeries>\n")
            out.append("    <in_Domain.mRID>$eic</in_Domain.mRID>\n")
            out.append("    <out_Domain.mRID>$eic</out_Domain.mRID>\n")
            out.append("    <currency_Unit.name>EUR</currency_Unit.name>\n")
            out.append("    <price_Measure_Unit.name>MWH</price_Measure_Unit.name>\n")
            out.append("    <curveType>$curve</curveType>\n")
            if (s.sequence.isNotEmpty()) {
                out.append("    <classificationSequence_AttributeInstanceComponent.position>${s.sequence}</classificationSequence_AttributeInstanceComponent.position>\n")
            }
            out.append("    <Period>\n")
            out.append("      <timeInterval><start>$start</start><end>${s.end ?: end}</end></timeInterval>\n")
            out.append("      <resolution>$resolution</resolution>\n")
            s.values.forEachIndexed { position, value ->
                if (value.isNotEmpty()) {
                    out.append("      <Point><position>${position + 1}</position><price.amount>$value</price.amount></Point>\n")
                }
            }
            out.append("    </Period>\n")
            out.append("  </TimeSeries>\n")
        }
        out.append("</Publication_MarketDocument>\n")
        return out.toString()
    }

    private fun hourly(sequence: String, amount: String, slots: Int) =
        Series(sequence, List(slots) { if (it == 0) amount else "" })

    private fun partial(sequence: String, amount: String, slots: Int, covered: Int) = Series(
        sequence,
        List(slots) { if (it == 0) amount else "" },
        end = Instant.parse("2026-09-20T22:00:00Z").plus(Duration.ofHours(covered.toLong())).toString().replace(":00Z", "Z")
    )

    private val dayStart = "2026-09-20T22:00Z"
    private val dayEnd = "2026-09-21T22:00Z"
    private val nlEic = "10YNL----------L"
    private val deLuEic = "10Y1001A1001A82H"
    private val atEic = "10YAT-APG------L"
    private val fiEic = "10YFI-1--------U"

    /** NL has no sequence policy: the general rules. */
    private fun parseNl(body: String) = parse(body, "NL", "2026-09-21")

    // every zone's own day

    private val everyZone = listOf(
        Triple("DE-LU", "2026-09-19", "202609182200" to "202609192200"),
        Triple("DE-LU", "2026-03-29", "202603282300" to "202603292200"),
        Triple("DE-LU", "2025-10-26", "202510252200" to "202510262300"),
        Triple("PL", "2026-09-19", "202609182200" to "202609192200"),
        Triple("EE", "2026-09-19", "202609182100" to "202609192100"),
        Triple("LV", "2026-09-19", "202609182100" to "202609192100"),
        Triple("LT", "2026-09-19", "202609182100" to "202609192100"),
        Triple("ES", "2026-03-29", "202603282300" to "202603292200"),
        Triple("PT", "2026-09-19", "202609182200" to "202609192200"),
        Triple("PT", "2025-10-26", "202510252200" to "202510262300"),
        Triple("BE", "2026-09-19", "202609182200" to "202609192200"),
        Triple("FR", "2026-03-29", "202603282300" to "202603292200"),
        Triple("CH", "2025-10-26", "202510252200" to "202510262300"),
        Triple("CZ", "2026-09-19", "202609182200" to "202609192200"),
        Triple("HR", "2026-09-19", "202609182200" to "202609192200"),
        Triple("HU", "2026-09-19", "202609182200" to "202609192200"),
        Triple("SI", "2026-09-19", "202609182200" to "202609192200"),
        Triple("SK", "2026-09-19", "202609182200" to "202609192200"),
        Triple("IT-NORTH", "2026-03-29", "202603282300" to "202603292200"),
        Triple("IT-SICILY", "2025-10-26", "202510252200" to "202510262300"),
        Triple("BG", "2026-09-19", "202609182100" to "202609192100"),
        Triple("GR", "2026-03-29", "202603282200" to "202603292100"),
        Triple("RO", "2025-10-26", "202510252100" to "202510262200"),
        Triple("AT", "2026-09-19", "202609182200" to "202609192200"),
        Triple("SE4", "2026-09-19", "202609182200" to "202609192200"),
        Triple("FI", "2026-09-19", "202609182100" to "202609192100")
    )

    private fun formatStamp(compact: String) =
        "${compact.substring(0, 4)}-${compact.substring(4, 6)}-${compact.substring(6, 8)}T${compact.substring(8, 10)}:${compact.substring(10, 12)}Z"

    @Test
    fun theDayAskedForIsTheZonesOwnLocalDay() {
        for ((id, date, bounds) in everyZone) {
            val (start, end) = EntsoeParser.dayBounds(LocalDate.parse(date), BundledAreas.zone(id))
            assertEquals("$id $date", formatStamp(bounds.first), start.toString().replace(":00Z", "Z"))
            assertEquals("$id $date", formatStamp(bounds.second), end.toString().replace(":00Z", "Z"))
        }
    }

    @Test
    fun everyZoneParsesItsOwnDay() {
        for ((id, date, bounds) in everyZone) {
            val route = BundledAreas.route(id)
            val start = formatStamp(bounds.first)
            val end = formatStamp(bounds.second)
            val hours = Duration.between(Instant.parse(start.replace("Z", ":00Z")), Instant.parse(end.replace("Z", ":00Z"))).toHours().toInt()
            var body = entsoeBody(route.eic, start, end, "PT60M", List(hours) { "${40 + it}.50" })
            route.sequence?.let { body = withSequence(body, it) }
            val parsed = day(body, id, date)
            assertEquals("$id $date", hours, parsed.rows.size)
            assertEquals("$id $date", BundledAreas.midnight(id, date), parsed.rows[0].start)
            same(parsed.rows[0].eur, 0.0405, "$id first price")
        }
    }

    // the hand-built fixtures

    @Test
    fun aNormalDay() {
        val parsed = fixture("synthetic_a44_60m.xml", "DE-LU", "2026-09-19")
        assertEquals(24, parsed.rows.size)
        for ((index, euro) in listOf(0 to 0.09842, 11 to 0.09015, 12 to 0.08870, 23 to 0.09340)) {
            same(parsed.rows[index].eur, euro, "row $index")
        }
        val midnight = BundledAreas.midnight("DE-LU", "2026-09-19")
        assertEquals(midnight, parsed.rows[0].start)
        assertEquals("the second period lands after the twelfth hour", Duration.ofHours(12), Duration.between(midnight, parsed.rows[12].start))
        assertEquals(Instant.parse("2026-09-18T12:31:00Z"), parsed.published)
    }

    @Test
    fun clockChangeDaysAreTwentyThreeAndTwentyFiveHours() {
        val berlin = ZoneId.of("Europe/Berlin")
        val spring = fixture("synthetic_a44_dst_spring.xml", "DE-LU", "2026-03-29")
        val autumn = fixture("synthetic_a44_dst_autumn.xml", "DE-LU", "2025-10-26")
        assertEquals(23, spring.rows.size)
        assertEquals(25, autumn.rows.size)
        assertEquals(Duration.ofHours(22), Duration.between(spring.rows.first().start, spring.rows.last().start))
        assertEquals(Duration.ofHours(24), Duration.between(autumn.rows.first().start, autumn.rows.last().start))
        assertEquals(0, spring.rows.count { it.start.atZone(berlin).hour == 2 })
        assertEquals(2, autumn.rows.count { it.start.atZone(berlin).hour == 2 })
    }

    @Test
    fun aQuarterHourlyDayIsNinetySixRows() {
        val parsed = fixture("synthetic_a44_15m.xml", "DE-LU", "2026-09-19")
        assertEquals(96, parsed.rows.size)
        assertEquals(Duration.ofMinutes(15), Duration.between(parsed.rows[0].start, parsed.rows[1].start))
        same(parsed.rows[0].eur, 0.09, "row 0")
    }

    @Test
    fun negativePricesSurviveTheConversion() {
        val parsed = fixture("synthetic_a44_60m_negative.xml", "PL", "2026-09-19")
        for ((index, euro) in listOf(0 to 0.04520, 8 to -0.00125, 9 to -0.00314)) same(parsed.rows[index].eur, euro, "row $index")
    }

    @Test
    fun theNoDataAcknowledgementIsNoDataNotADay() {
        assertEquals(EntsoeParse.NoData, parse(RelayUpstream.read("synthetic_ack_999_no_data.xml"), "DE-LU", "2026-09-19"))
        assertEquals(EntsoeParse.NoData, parse(RelayUpstream.read("entsoe_SE4_2026-09-24_no_data.xml"), "SE4", "2026-09-24"))
    }

    @Test
    fun theMeasureUnitIsReadCaseInsensitively() {
        val base = RelayUpstream.read("synthetic_a44_60m.xml")
        for (spelling in listOf("MWh", "MWH", "mwh")) {
            val body = base.replaceFirst("<price_Measure_Unit.name>MWh</price_Measure_Unit.name>", "<price_Measure_Unit.name>$spelling</price_Measure_Unit.name>")
            assertEquals(spelling, 24, day(body, "DE-LU", "2026-09-19").rows.size)
        }
    }

    @Test
    fun anUnreadableCreationTimeLeavesPublishedAbsent() {
        val body = RelayUpstream.read("synthetic_a44_60m.xml")
            .replaceFirst("<createdDateTime>2026-09-18T12:31:00Z</createdDateTime>", "<createdDateTime>not a time</createdDateTime>")
        assertNull(day(body, "DE-LU", "2026-09-19").published)
    }

    @Test
    fun theParserRefusesRatherThanRepairing() {
        val eic = deLuEic
        val base = RelayUpstream.read("synthetic_a44_60m.xml")
        val last = base.lastIndexOf("      <Point>")
        val closing = base.indexOf("</Point>\n", last) + "</Point>\n".length
        val gap = base.substring(0, last) + base.substring(closing)

        val cases = listOf(
            Triple("a different document type", base.replaceFirst("<type>A44</type>", "<type>A69</type>"), "not A44"),
            Triple("another zone's series and none of ours", base.replace(eic, "10YPL-AREA-----S"), "no series for $eic"),
            Triple("a currency that is not EUR", base.replaceFirst("<currency_Unit.name>EUR</currency_Unit.name>", "<currency_Unit.name>PLN</currency_Unit.name>"), "not \"EUR\""),
            Triple("a measure unit that is not MWh", base.replaceFirst("<price_Measure_Unit.name>MWh</price_Measure_Unit.name>", "<price_Measure_Unit.name>KWH</price_Measure_Unit.name>"), "not MWh"),
            Triple("a half-hourly resolution", base.replaceFirst("      <resolution>PT60M</resolution>", "      <resolution>PT30M</resolution>"), "PT30M"),
            Triple("a resolution this app does not read", base.replaceFirst("      <resolution>PT60M</resolution>", "      <resolution>P1D</resolution>"), "\"P1D\""),
            Triple("an A01 period that starts before the day", base.replaceFirst("        <start>2026-09-18T22:00Z</start>", "        <start>2026-09-18T21:00Z</start>"), "A01"),
            Triple("an A01 period whose declared end cannot hold its points", base.replaceFirst("        <end>2026-09-19T10:00Z</end>", "        <end>2026-09-19T09:00Z</end>"), "beyond its own"),
            Triple("an A01 second period overlapping the first", base.replaceFirst("        <start>2026-09-19T10:00Z</start>", "        <start>2026-09-19T09:00Z</start>"), "A01"),
            Triple("a period that is not a whole number of slots", base.replaceFirst("        <start>2026-09-19T10:00Z</start>", "        <start>2026-09-19T10:30Z</start>"), "not a whole number of"),
            Triple("an A01 series missing its last slot", gap, "A01"),
            Triple("an A01 slot past the end of its period", base.replaceFirst("        <position>12</position>", "        <position>13</position>"), "beyond its own"),
            Triple(
                "an A01 series with a slot missing",
                base.replaceFirst("      <Point>\n        <position>5</position>\n        <price.amount>88.9</price.amount>\n      </Point>\n", ""),
                "A01"
            ),
            Triple("no curveType at all", base.replaceFirst("    <curveType>A01</curveType>\n", ""), "does not interpret"),
            Triple("an unknown curveType", base.replaceFirst("<curveType>A01</curveType>", "<curveType>A99</curveType>"), "does not interpret"),
            Triple("a duplicated position", base.replaceFirst("        <position>2</position>", "        <position>1</position>"), "repeats position 1"),
            Triple("a price that is not a finite number", base.replaceFirst("        <price.amount>98.42</price.amount>", "        <price.amount>NaN</price.amount>"), "not a finite number"),
            Triple("a timestamp that is not UTC", base.replaceFirst("        <start>2026-09-18T22:00Z</start>", "        <start>2026-09-19T00:00+02:00</start>"), "not a UTC timestamp"),
            Triple("an empty body", "", "not an A44 document"),
            Triple("an error page", "<html><body>502 Bad Gateway</body></html>", "not an A44 document"),
            Triple("a document with no series", "<Publication_MarketDocument xmlns=\"urn:x\"><type>A44</type></Publication_MarketDocument>", "no TimeSeries"),
            Triple("a price that is not a number", base.replaceFirst("        <price.amount>98.42</price.amount>", "        <price.amount>98,42</price.amount>"), "not an A44 document"),
            Triple("a position that is not a number", base.replaceFirst("        <position>2</position>", "        <position>two</position>"), "not an A44 document"),
            Triple("a document type declaration", "<!DOCTYPE x [<!ENTITY e \"y\">]>" + base.substringAfter("?>"), "not an A44 document")
        )
        for ((name, body, want) in cases) {
            val parsed = parse(body, "DE-LU", "2026-09-19")
            assertTrue("$name: $parsed", parsed is EntsoeParse.Refused)
            assertMentions((parsed as EntsoeParse.Refused).reason, want)
        }
        assertTrue("the base itself parses", parse(base, "DE-LU", "2026-09-19") is EntsoeParse.Day)
    }

    // the real captures

    @Test
    fun aRealQuarterHourlyDay() {
        val parsed = fixture("entsoe_SE4_2026-09-21.xml", "SE4", "2026-09-21")
        assertEquals(96, parsed.rows.size)
        assertEquals(Duration.ofMinutes(15), Duration.between(parsed.rows[0].start, parsed.rows[1].start))
        assertEquals(BundledAreas.midnight("SE4", "2026-09-21"), parsed.rows[0].start)
        assertNotNull(parsed.published)
    }

    @Test
    fun aRealDayWithNegativePrices() {
        assertTrue(fixture("entsoe_NO2_2026-09-19_negative.xml", "NO2", "2026-09-19").rows.minOf { it.eur } < 0)
    }

    @Test
    fun aRealDocumentCarryingAnotherDay() {
        val parsed = fixture("entsoe_FI_2026-09-21_multiday.xml", "FI", "2026-09-21")
        assertEquals(96, parsed.rows.size)
        assertEquals(BundledAreas.midnight("FI", "2026-09-21"), parsed.rows[0].start)
    }

    @Test
    fun aRealDocumentThatPublishesTheSameCurveTwice() {
        assertEquals(96, fixture("entsoe_NO1_2026-09-21_duplicate.xml", "NO1", "2026-09-21").rows.size)
        assertEquals(96, fixture("entsoe_PL_2026-09-21.xml", "PL", "2026-09-21").rows.size)
    }

    @Test
    fun aRealSparseA03DayCarriesValuesForward() {
        val parsed = fixture("entsoe_SE3_2026-09-21_a03_blocks.xml", "SE3", "2026-09-21")
        assertEquals(96, parsed.rows.size)
        assertEquals(parsed.rows[27].eur, parsed.rows[28].eur, 0.0)
        assertNotEquals(parsed.rows[28].eur, parsed.rows[29].eur, 0.0)
    }

    @Test
    fun theRealDeLuDocumentPublishesTheSequenceItsZoneNames() {
        val body = RelayUpstream.read("entsoe_DE-LU_2026-09-21_sequences.xml")
        val parsed = day(body, "DE-LU", "2026-09-21")
        assertEquals(96, parsed.rows.size)
        assertEquals(0.05068, parsed.rows[0].eur, 0.0)
        assertTrue(parsed.rows.none { it.eur == 0.1121 })

        val reason = refused(body, "DE-LU", "2026-09-21", BundledAreas.route("DE-LU").copy(sequence = null))
        assertMentions(reason, "sequences 1 and 2", "disagree", "50.68 EUR/MWh", "112.10")
        assertFalse(reason.contains("classificationSequence"))
    }

    private fun seriesBlock(body: String, label: String): String =
        Regex("""(?s)\n *<TimeSeries>.*?</TimeSeries>""").findAll(body).map { it.value }
            .first { it.contains("<classificationSequence_AttributeInstanceComponent.position>$label<") }

    @Test
    fun aThirdUnknownDeLuSequenceRefusesTheDay() {
        val body = RelayUpstream.read("entsoe_DE-LU_2026-09-21_sequences.xml")
        val third = seriesBlock(body, "2")
            .replaceFirst("<mRID>2</mRID>", "<mRID>3</mRID>")
            .replaceFirst("<classificationSequence_AttributeInstanceComponent.position>2<", "<classificationSequence_AttributeInstanceComponent.position>3<")
        val withThird = body.replaceFirst("</Publication_MarketDocument>", "$third</Publication_MarketDocument>")
        assertMentions(refused(withThird, "DE-LU", "2026-09-21"), "sequence 3")

        val twoSeries = body.replaceFirst(seriesBlock(body, "2"), "")
        assertEquals(0.05068, day(twoSeries, "DE-LU", "2026-09-21").rows[0].eur, 0.0)

        val missing = body.replaceFirst(seriesBlock(body, "1"), "")
        assertMentions(refused(missing, "DE-LU", "2026-09-21"), "no sequence 1")
    }

    @Test
    fun theDutchCapturesNormaliseIntoWholeLocalDays() {
        for ((file, date, rows, lowest) in listOf(
            Quad("entsoe_NL_2026-09-21.xml", "2026-09-21", 96, 0.03698),
            Quad("entsoe_NL_2026-03-29_dst_spring.xml", "2026-03-29", 92, -0.00206),
            Quad("entsoe_NL_2025-10-26_dst_autumn.xml", "2025-10-26", 100, -0.00104),
            Quad("entsoe_NL_2026-05-24_negative.xml", "2026-05-24", 96, -0.0865)
        )) {
            val parsed = fixture(file, "NL", date)
            assertEquals(file, rows, parsed.rows.size)
            assertEquals(file, BundledAreas.midnight("NL", date), parsed.rows[0].start)
            for (i in 1 until parsed.rows.size) {
                assertEquals(Duration.ofMinutes(15), Duration.between(parsed.rows[i - 1].start, parsed.rows[i].start))
            }
            assertEquals(
                BundledAreas.midnight("NL", LocalDate.parse(date).plusDays(1).toString()),
                parsed.rows.last().start.plus(Duration.ofMinutes(15))
            )
            same(parsed.rows.minOf { it.eur }, lowest, "$file lowest")
        }
    }

    private data class Quad(val file: String, val date: String, val rows: Int, val lowest: Double)

    @Test
    fun realClockChangeDaysAreWhole() {
        assertEquals(92, fixture("entsoe_SE4_2026-03-29_dst_spring.xml", "SE4", "2026-03-29").rows.size)
        assertEquals(100, fixture("entsoe_SE4_2025-10-26_dst_autumn.xml", "SE4", "2025-10-26").rows.size)
    }

    @Test
    fun realDanishDocumentsWithTwoIdenticalSequences() {
        for ((file, id, date) in listOf(
            Triple("entsoe_DK1_2026-09-21_sequences.xml", "DK1", "2026-09-21"),
            Triple("entsoe_DK2_2026-09-19_sequences.xml", "DK2", "2026-09-19"),
            Triple("entsoe_DK2_2026-09-20_sequences.xml", "DK2", "2026-09-20")
        )) {
            val parsed = fixture(file, id, date)
            assertEquals(file, 96, parsed.rows.size)
            assertEquals(file, BundledAreas.midnight(id, date), parsed.rows[0].start)
        }
    }

    @Test
    fun aPrePublicationPrefixIsNoDataRatherThanAFailure() {
        val prefix = entsoeBody(deLuEic, "2026-09-18T22:00Z", "2026-09-18T23:00Z", "PT60M", listOf("42.00"))
        assertEquals(EntsoeParse.NoData, parse(withSequence(prefix, "1"), "DE-LU", "2026-09-19"))
        val nlPrefix = entsoeBody(nlEic, "2026-09-20T22:00Z", "2026-09-21T10:00Z", "PT15M", List(48) { "10" })
        assertEquals(EntsoeParse.NoData, parseNl(nlPrefix))
    }

    @Test
    fun aMalformedPartialDayRemainsAFailure() {
        val broken = entsoeBody(nlEic, "2026-09-20T22:00Z", "2026-09-21T00:00Z", "PT60M", listOf("42.00"))
        assertTrue(parseNl(broken) is EntsoeParse.Refused)
    }

    // A03

    private fun a03(vararg blocks: Pair<Int, String>, start: String = dayStart, resolution: String = "PT60M"): String {
        // Built point by point, in the order given, so a test can state positions out of order or twice.
        val points = blocks.joinToString("") { (position, amount) ->
            "<Point><position>$position</position><price.amount>$amount</price.amount></Point>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<Publication_MarketDocument xmlns="urn:iec62325.351:tc57wg16:451-3:publicationdocument:7:3">
  <type>A44</type>
  <createdDateTime>2026-09-21T12:50:21Z</createdDateTime>
  <TimeSeries>
    <in_Domain.mRID>$nlEic</in_Domain.mRID>
    <out_Domain.mRID>$nlEic</out_Domain.mRID>
    <currency_Unit.name>EUR</currency_Unit.name>
    <price_Measure_Unit.name>MWH</price_Measure_Unit.name>
    <curveType>A03</curveType>
    <Period>
      <timeInterval><start>$start</start><end>$dayEnd</end></timeInterval>
      <resolution>$resolution</resolution>
      $points
    </Period>
  </TimeSeries>
</Publication_MarketDocument>"""
    }

    private fun a03Day(body: String) = day(body, "NL", "2026-09-21")

    private fun a03Refused(body: String) = refused(body, "NL", "2026-09-21")

    @Test
    fun anUnstatedSlotCarriesThePreviousValue() {
        val parsed = a03Day(a03(1 to "10", 3 to "20", 5 to "30"))
        assertEquals(24, parsed.rows.size)
        for ((slot, want) in listOf(1 to 0.01, 2 to 0.01, 3 to 0.02, 4 to 0.02, 5 to 0.03, 24 to 0.03)) {
            same(parsed.rows[slot - 1].eur, want, "slot $slot")
        }
    }

    @Test
    fun severalConsecutiveSlotsShareOneValue() {
        val parsed = a03Day(a03(1 to "40", 5 to "44.5", 9 to "41.25", resolution = "PT15M"))
        assertEquals(96, parsed.rows.size)
        for (slot in 1..4) same(parsed.rows[slot - 1].eur, 0.04, "slot $slot")
        for (slot in 5..8) same(parsed.rows[slot - 1].eur, 0.0445, "slot $slot")
        for (slot in 9..96) same(parsed.rows[slot - 1].eur, 0.04125, "slot $slot")
    }

    @Test
    fun theLastBlockRunsToTheEndOfThePeriod() {
        val parsed = a03Day(a03(1 to "10", 22 to "99"))
        same(parsed.rows[23].eur, 0.099, "the last slot")
        same(parsed.rows[21].eur, 0.099, "slot 22")
    }

    @Test
    fun aPeriodStartingBeforeTheDayCarriesIntoIt() {
        val parsed = a03Day(a03(1 to "77", 14 to "88", start = "2026-09-20T21:00Z"))
        assertEquals(24, parsed.rows.size)
        same(parsed.rows[0].eur, 0.077, "the day's first slot")
        same(parsed.rows[12].eur, 0.088, "slot 13 of the day")
    }

    @Test
    fun a03RefusalsNameWhatIsWrong() {
        assertMentions(a03Refused(a03(2 to "10", 5 to "20")), "first block is at slot 2")
        assertMentions(a03Refused(a03(1 to "10", 4 to "20", 4 to "30")), "repeats position 4")
        assertMentions(a03Refused(a03(1 to "10", 25 to "30")), "beyond its own 24 slots")
        assertMentions(a03Refused(a03(0 to "10")), "not a slot number")
        assertMentions(a03Refused(a03(1 to "NaN")), "not a finite number")
    }

    @Test
    fun a03OutOfOrderSlotsAreSorted() {
        val parsed = a03Day(a03(5 to "30", 1 to "10", 3 to "20"))
        same(parsed.rows[0].eur, 0.01, "slot 1")
        same(parsed.rows[2].eur, 0.02, "slot 3")
        same(parsed.rows[4].eur, 0.03, "slot 5")
    }

    @Test
    fun theCurveTypesAreNotInterchangeable() {
        val sparse = a03(1 to "10", 5 to "20").replaceFirst("<curveType>A03</curveType>", "<curveType>A01</curveType>")
        assertMentions(a03Refused(sparse), "A01", "2 of its 24")

        val dense = a03(*(1..24).map { it to "$it" }.toTypedArray()).replaceFirst("<curveType>A03</curveType>", "<curveType>A01</curveType>")
        val parsed = a03Day(dense)
        assertEquals(24, parsed.rows.size)
        same(parsed.rows[0].eur, 0.001, "slot 1")
        same(parsed.rows[23].eur, 0.024, "slot 24")
    }

    // mixed resolution

    private fun mixedDoc(eic: String, vararg periods: Quad): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Publication_MarketDocument xmlns=\"urn:x\">\n  <type>A44</type>\n  <TimeSeries>\n")
        out.append("    <in_Domain.mRID>$eic</in_Domain.mRID>\n    <out_Domain.mRID>$eic</out_Domain.mRID>\n")
        out.append("    <currency_Unit.name>EUR</currency_Unit.name>\n    <price_Measure_Unit.name>MWH</price_Measure_Unit.name>\n")
        out.append("    <curveType>A01</curveType>\n")
        periods.forEachIndexed { p, period ->
            // Quad reused as (start, end, resolution, slots).
            out.append("    <Period>\n      <timeInterval><start>${period.file}</start><end>${period.date}</end></timeInterval>\n")
            out.append("      <resolution>${resolutionOf(period)}</resolution>\n")
            for (i in 1..period.rows) {
                out.append("      <Point><position>$i</position><price.amount>${10 * (p + 1) + i % 10}</price.amount></Point>\n")
            }
            out.append("    </Period>\n")
        }
        out.append("  </TimeSeries>\n</Publication_MarketDocument>\n")
        return out.toString()
    }

    private fun resolutionOf(period: Quad) = if (period.lowest == 60.0) "PT60M" else "PT15M"

    private fun span(start: String, end: String, minutes: Int, slots: Int) = Quad(start, end, slots, minutes.toDouble())

    @Test
    fun anEetDayAcrossTheQuarterHourSwitchIsReadAtFifteenMinutes() {
        val parsed = day(RelayUpstream.read("synthetic_a44_FI_2025-10-01_mixed_resolution.xml"), "FI", "2025-10-01")
        assertEquals(96, parsed.rows.size)
        val start = Instant.parse("2025-09-30T21:00:00Z")
        parsed.rows.forEachIndexed { i, row -> assertEquals(start.plus(Duration.ofMinutes(15L * i)), row.start) }
        for (i in 0 until 4) assertEquals(0.0614, parsed.rows[i].eur, 1e-9)
        assertEquals(0.05, parsed.rows[4].eur, 1e-9)
        assertEquals(0.0526, parsed.rows[5].eur, 1e-9)
        assertEquals(0.0591, parsed.rows[95].eur, 1e-9)
    }

    @Test
    fun daysOnOneSideOfTheSwitchKeepTheirOwnResolution() {
        val cet = RelayUpstream.read("synthetic_a44_FI_2025-10-01_mixed_resolution.xml").replace(fiEic, BundledAreas.route("SE3").eic)
        val after = day(cet, "SE3", "2025-10-01")
        assertEquals(96, after.rows.size)
        val before = day(cet, "SE3", "2025-09-30")
        assertEquals(24, before.rows.size)
        assertEquals(Duration.ofHours(1), Duration.between(before.rows[0].start, before.rows[1].start))
    }

    @Test
    fun aMixedResolutionDayIsExactOrRefused() {
        val cases = listOf(
            listOf(span("2025-09-30T21:00Z", "2025-09-30T22:00Z", 60, 1), span("2025-09-30T22:00Z", "2025-10-01T21:00Z", 15, 92)) to null,
            listOf(span("2025-09-30T21:00Z", "2025-09-30T22:00Z", 60, 1), span("2025-09-30T22:15Z", "2025-10-01T21:00Z", 15, 91)) to
                "the day has a gap, a repeat or an overlapping period",
            listOf(span("2025-09-30T22:00Z", "2025-09-30T23:00Z", 60, 1), span("2025-09-30T23:00Z", "2025-10-01T21:00Z", 15, 88)) to
                "not at the requested",
            listOf(span("2025-09-30T21:00Z", "2025-09-30T23:00Z", 60, 2), span("2025-09-30T22:00Z", "2025-10-01T21:00Z", 15, 92)) to
                "overlapping period",
            listOf(span("2025-09-30T20:30Z", "2025-09-30T21:30Z", 60, 1), span("2025-09-30T21:30Z", "2025-10-01T21:00Z", 15, 94)) to
                "cannot be split",
            listOf(span("2025-09-30T21:00Z", "2025-09-30T22:00Z", 60, 1), span("2025-09-30T22:00Z", "2025-10-01T20:00Z", 15, 88)) to
                "no data"
        )
        for ((periods, wantErr) in cases) {
            val parsed = parse(mixedDoc(fiEic, *periods.toTypedArray()), "FI", "2025-10-01")
            when (wantErr) {
                null -> {
                    val rows = (parsed as EntsoeParse.Day).rows
                    assertEquals(96, rows.size)
                    assertEquals(rows[0].eur, rows[3].eur, 0.0)
                    assertEquals(Duration.ofHours(1), Duration.between(rows[0].start, rows[4].start))
                }
                "no data" -> assertEquals(EntsoeParse.NoData, parsed)
                else -> assertMentions((parsed as EntsoeParse.Refused).reason, wantErr)
            }
        }
    }

    @Test
    fun aMixedAutumnDayIsOneHundredQuarters() {
        val body = mixedDoc(
            fiEic,
            span("2025-10-25T21:00Z", "2025-10-25T22:00Z", 60, 1),
            span("2025-10-25T22:00Z", "2025-10-26T22:00Z", 15, 96)
        )
        assertEquals(100, day(body, "FI", "2025-10-26").rows.size)
    }

    // sequences

    @Test
    fun sequenceCombinationsWithoutAPolicy() {
        val cases = listOf(
            listOf(hourly("", "10", 24)) to null,
            listOf(hourly("1", "10", 24)) to null,
            listOf(hourly("", "10", 24), hourly("2", "10", 24)) to null,
            listOf(hourly("", "10", 24), hourly("2", "40", 24)) to "sequences none and 2",
            listOf(hourly("", "10", 24), partial("2", "40", 24, 12)) to "cannot show which sequence is the product",
            listOf(partial("", "10", 24, 12), hourly("2", "40", 24)) to "cannot show which sequence is the product",
            listOf(partial("", "10", 24, 12), partial("2", "40", 24, 12)) to "no price sequence for",
            listOf(hourly("", "10", 24), hourly("1", "10", 24), hourly("2", "10", 24)) to null,
            listOf(hourly("", "10", 24), hourly("1", "10", 24), hourly("2", "11", 24)) to "cannot choose between them"
        )
        for ((series, wantErr) in cases) {
            val parsed = parseNl(entsoeDoc(nlEic, dayStart, dayEnd, "PT60M", "A03", *series.toTypedArray()))
            if (wantErr == null) {
                val rows = (parsed as EntsoeParse.Day).rows
                assertEquals(24, rows.size)
                same(rows[0].eur, 0.01, "slot 1")
            } else {
                assertTrue("$series: $parsed", parsed is EntsoeParse.Refused)
                assertMentions((parsed as EntsoeParse.Refused).reason, wantErr)
            }
        }
    }

    @Test
    fun anIncompleteSequenceBlocksIdentityCollapse() {
        val body = entsoeDoc(nlEic, dayStart, dayEnd, "PT60M", "A03", hourly("", "10", 24), hourly("1", "10", 24), partial("2", "10", 24, 12))
        assertMentions((parseNl(body) as EntsoeParse.Refused).reason, "2")
        val mirror = entsoeDoc(nlEic, dayStart, dayEnd, "PT60M", "A03", partial("2", "10", 24, 12), hourly("", "10", 24), hourly("1", "10", 24))
        assertTrue(parseNl(mirror) is EntsoeParse.Refused)
    }

    @Test
    fun theDeLuSequenceRulesCaseByCase() {
        val cases = listOf(
            listOf(hourly("1", "10", 24)) to null,
            listOf(hourly("1", "10", 24), hourly("2", "40", 24)) to null,
            listOf(hourly("2", "40", 24)) to "no sequence 1",
            listOf(partial("1", "10", 24, 12), hourly("2", "40", 24)) to "does not cover",
            listOf(hourly("1", "10", 24), hourly("2", "40", 24), hourly("3", "70", 24)) to "sequence 3",
            listOf(hourly("1", "10", 24), hourly("", "40", 24)) to "sequence none"
        )
        for ((series, wantErr) in cases) {
            val parsed = parse(entsoeDoc(deLuEic, dayStart, dayEnd, "PT60M", "A03", *series.toTypedArray()), "DE-LU", "2026-09-21")
            if (wantErr == null) {
                val rows = (parsed as EntsoeParse.Day).rows
                assertEquals(24, rows.size)
                same(rows[0].eur, 0.01, "the published sequence")
            } else {
                assertMentions((parsed as EntsoeParse.Refused).reason, wantErr)
            }
        }
        // And without the policy, one complete sequence beside an incomplete one refuses.
        val body = entsoeDoc(deLuEic, dayStart, dayEnd, "PT60M", "A03", partial("1", "10", 24, 12), hourly("2", "40", 24))
        assertTrue(parse(body, "DE-LU", "2026-09-21", BundledAreas.route("DE-LU").copy(sequence = null)) is EntsoeParse.Refused)
    }

    @Test
    fun aZoneWithAPolicyAndNoExpectedListExpectsOnlyItsOwn() {
        val strict = BundledAreas.route("DE-LU").copy(expectedSequences = emptyList())
        val alone = entsoeDoc(deLuEic, dayStart, dayEnd, "PT60M", "A03", hourly("1", "10", 24))
        assertTrue(parse(alone, "DE-LU", "2026-09-21", strict) is EntsoeParse.Day)
        val withSecond = entsoeDoc(deLuEic, dayStart, dayEnd, "PT60M", "A03", hourly("1", "10", 24), hourly("2", "40", 24))
        assertTrue(parse(withSecond, "DE-LU", "2026-09-21", strict) is EntsoeParse.Refused)
        val forgot = BundledAreas.route("DE-LU").copy(expectedSequences = listOf("2"))
        assertTrue(parse(withSecond, "DE-LU", "2026-09-21", forgot) is EntsoeParse.Day)
    }

    @Test
    fun aPeriodThatIsNotAWholeNumberOfSlotsOrRunsBackwardsIsRefused() {
        val body = entsoeDoc(nlEic, "2026-09-20T22:00Z", "2026-09-20T23:05Z", "PT15M", "A03", hourly("", "10", 5))
        assertMentions((parseNl(body) as EntsoeParse.Refused).reason, "not a whole number of")
        // Never a day. (Its period touches the requested day only at its start, so like the relay this
        // reads it as another day's period and the document as not published yet.)
        val backwards = entsoeDoc(nlEic, "2026-09-21T00:00Z", "2026-09-20T22:00Z", "PT60M", "A03", hourly("", "10", 24))
        assertFalse(parseNl(backwards) is EntsoeParse.Day)
        val inside = entsoeDoc(nlEic, "2026-09-21T02:00Z", "2026-09-21T01:00Z", "PT60M", "A03", hourly("", "10", 24))
        assertMentions((parseNl(inside) as EntsoeParse.Refused).reason, "ends at or before it starts")
    }

    // Austria, and a half-hourly route

    private fun quarterSeries(sequence: String, slots: Int, first: List<Double>, base: Double) = Series(
        sequence,
        List(slots) { i -> String.format(java.util.Locale.ROOT, "%.2f", if (i < first.size) first[i] else base + i % 7) }
    )

    @Test
    fun austriaPublishesSequenceOneBesideExaa() {
        val first = listOf(185.11, 204.65, 183.92, 174.30)
        val seq1 = quarterSeries("1", 96, first, 190.0)
        val seq2 = quarterSeries("2", 96, listOf(199.99, 210.5), 205.0).let { s ->
            s.copy(values = s.values.mapIndexed { i, v -> if (i == 10 || i == 50) "" else v })
        }
        val start = "2026-10-01T22:00Z"
        val end = "2026-10-02T22:00Z"
        val parsed = day(entsoeDoc(atEic, start, end, "PT15M", "A03", seq2, seq1, seq1), "AT", "2026-10-02")
        assertEquals(96, parsed.rows.size)
        first.forEachIndexed { i, want -> assertEquals(want, parsed.rows[i].eur * 1000, 1e-9) }
        assertEquals(Instant.parse("2026-10-01T22:00:00Z"), parsed.rows[0].start)

        assertTrue(parse(entsoeDoc(atEic, start, end, "PT15M", "A03", seq2), "AT", "2026-10-02") is EntsoeParse.Refused)
        val third = parse(entsoeDoc(atEic, start, end, "PT15M", "A03", seq1, seq2, quarterSeries("3", 96, emptyList(), 50.0)), "AT", "2026-10-02")
        assertMentions((third as EntsoeParse.Refused).reason, "sequence 3")
        val other = quarterSeries("1", 96, listOf(185.11, 999.0), 190.0)
        assertTrue(parse(entsoeDoc(atEic, start, end, "PT15M", "A03", seq1, other), "AT", "2026-10-02") is EntsoeParse.Refused)
    }

    @Test
    fun onlyAHalfHourlyRouteReadsThirtyMinuteDocuments() {
        val ie = EntsoeRoute("10Y1001A1001A59C", halfHourly = true)
        val paris = ZoneId.of("Europe/Paris")
        val halfHours = Series("", List(48) { String.format(java.util.Locale.ROOT, "%.2f", 100.0 + it) })
        val body = entsoeDoc(ie.eic, "2026-10-04T22:00Z", "2026-10-05T22:00Z", "PT30M", "A01", halfHours)
        val parsed = EntsoeParser.parse(body, ie, paris, LocalDate.parse("2026-10-05")) as EntsoeParse.Day
        assertEquals(48, parsed.rows.size)
        assertEquals(Duration.ofMinutes(30), Duration.between(parsed.rows[0].start, parsed.rows[1].start))

        val nlBody = entsoeDoc(nlEic, "2026-10-04T22:00Z", "2026-10-05T22:00Z", "PT30M", "A01", halfHours)
        assertMentions((parse(nlBody, "NL", "2026-10-05") as EntsoeParse.Refused).reason, "PT30M")

        val prefix = entsoeDoc(ie.eic, "2026-10-04T22:00Z", "2026-10-05T10:00Z", "PT30M", "A01", Series("", halfHours.values.take(24)))
        assertEquals(EntsoeParse.NoData, EntsoeParser.parse(prefix, ie, paris, LocalDate.parse("2026-10-05")))
    }
}
