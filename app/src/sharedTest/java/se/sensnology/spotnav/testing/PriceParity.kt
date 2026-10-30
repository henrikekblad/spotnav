package se.sensnology.spotnav.testing

import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.DayBuild
import se.sensnology.spotnav.prices.DayDocumentBuilder
import se.sensnology.spotnav.prices.DayParse
import se.sensnology.spotnav.prices.EcbParser
import se.sensnology.spotnav.prices.FxRate
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.RelayDayParser
import se.sensnology.spotnav.prices.RelayDayPoints
import se.sensnology.spotnav.prices.entsoe.EntsoeAck
import se.sensnology.spotnav.prices.entsoe.EntsoeParse
import se.sensnology.spotnav.prices.entsoe.EntsoeParser
import java.time.Instant
import java.time.LocalDate

/**
 * What the app's own price parsing makes of the vendored captures, one line each, so the JVM the unit tests
 * run on and Android's own XML and JSON implementations can be held to the same text (design 3.3, step A5).
 * Each day's rows are folded bit for bit, so a single price or instant read differently changes the line.
 * Shared by the unit test (`PriceParityTest`) and the emulator check (`DeviceChecks`).
 */
internal object PriceParity {
    /** The area and market day each capture is read as; the acknowledgements and the ECB table by kind. */
    private val DAYS = linkedMapOf(
        "entsoe_DE-LU_2026-09-21_sequences.xml" to ("DE-LU" to "2026-09-21"),
        "entsoe_DK1_2026-09-21_sequences.xml" to ("DK1" to "2026-09-21"),
        "entsoe_DK2_2026-09-19_sequences.xml" to ("DK2" to "2026-09-19"),
        "entsoe_DK2_2026-09-20_sequences.xml" to ("DK2" to "2026-09-20"),
        "entsoe_FI_2026-09-21_multiday.xml" to ("FI" to "2026-09-21"),
        "entsoe_NL_2025-10-26_dst_autumn.xml" to ("NL" to "2025-10-26"),
        "entsoe_NL_2026-03-29_dst_spring.xml" to ("NL" to "2026-03-29"),
        "entsoe_NL_2026-05-24_negative.xml" to ("NL" to "2026-05-24"),
        "entsoe_NL_2026-09-21.xml" to ("NL" to "2026-09-21"),
        "entsoe_NO1_2026-09-21_duplicate.xml" to ("NO1" to "2026-09-21"),
        "entsoe_NO2_2026-09-19_negative.xml" to ("NO2" to "2026-09-19"),
        "entsoe_PL_2026-09-21.xml" to ("PL" to "2026-09-21"),
        "entsoe_SE3_2026-09-21_a03_blocks.xml" to ("SE3" to "2026-09-21"),
        "entsoe_SE4_2025-10-26_dst_autumn.xml" to ("SE4" to "2025-10-26"),
        "entsoe_SE4_2026-03-29_dst_spring.xml" to ("SE4" to "2026-03-29"),
        "entsoe_SE4_2026-09-21.xml" to ("SE4" to "2026-09-21"),
        "entsoe_SE4_2026-09-24_no_data.xml" to ("SE4" to "2026-09-24"),
        "synthetic_a44_15m.xml" to ("DE-LU" to "2026-09-19"),
        "synthetic_a44_60m.xml" to ("DE-LU" to "2026-09-19"),
        "synthetic_a44_60m_negative.xml" to ("PL" to "2026-09-19"),
        "synthetic_a44_dst_autumn.xml" to ("DE-LU" to "2025-10-26"),
        "synthetic_a44_dst_spring.xml" to ("DE-LU" to "2026-03-29"),
        "synthetic_a44_FI_2025-10-01_mixed_resolution.xml" to ("FI" to "2025-10-01")
    )
    private val ACKS = listOf("synthetic_ack_999_auth.xml", "synthetic_ack_999_no_data.xml", "synthetic_ack_999_unknown.xml")
    private const val ECB = "ecb_daily.xml"

    /** The relay's frozen clock and rate for the SE4 day file (`format_test.go`), as `DayDocumentBuilderTest` uses them. */
    private val RETRIEVED: Instant = Instant.parse("2026-09-19T21:40:11Z")
    private val SEK = FxRate(11.2915, LocalDate.parse("2026-09-18"))

    /**
     * The lines for [capture] (name to text) against the bundled area list [areasJson], ending with the SE4
     * day document built and read back, and whether it is the relay's own file [goldenSe4] byte for byte.
     */
    fun lines(areasJson: String, capture: (String) -> String, goldenSe4: String): List<String> {
        val areas = AreaCatalogue.bundledAreas(areasJson)
        val byId = areas.associateBy { it.id }
        val out = ArrayList<String>()
        out += "areas ${areas.size} routed ${areas.count { it.entsoe != null }}"
        for ((name, day) in DAYS) {
            val area = byId.getValue(day.first)
            out += "$name ${describe(EntsoeParser.parse(capture(name), area.entsoe!!, area.marketZoneId, LocalDate.parse(day.second)))}"
        }
        for (name in ACKS) {
            val ack = EntsoeAck.parse(capture(name))
            out += "$name ${ack?.kind(200)?.name} ${ack?.reasonCode()}"
        }
        val table = EcbParser.daily(capture(ECB))
        out += "$ECB ${table?.day} ${table?.values?.toSortedMap()?.entries?.joinToString(",") { "${it.key}=${bits(it.value)}" }}"
        out += se4RoundTrip(byId.getValue("SE4"), capture("entsoe_SE4_2026-09-21.xml"), goldenSe4)
        return out
    }

    private fun se4RoundTrip(se4: PriceMarket, body: String, golden: String): String {
        val day = EntsoeParser.parse(body, se4.entsoe!!, se4.marketZoneId, LocalDate.parse("2026-09-21")) as EntsoeParse.Day
        val built = DayDocumentBuilder.build(se4, LocalDate.parse("2026-09-21"), day, SEK, RETRIEVED) as DayBuild.Built
        val parsed = RelayDayParser.parse(built.json, "SE4", se4.marketTz, se4.currency, "2026-09-21") as DayParse.Ok
        val points = RelayDayPoints.points(parsed.document, se4.zoneId)
        var fold = 0L
        for (point in points) fold = fold * 31 + point.start.toInstant().epochSecond * 7 + bits(point.pricePerKwh)
        return "SE4 document golden=${built.json == golden} points=${points.size} fold=$fold"
    }

    private fun describe(parse: EntsoeParse): String = when (parse) {
        is EntsoeParse.Day -> {
            var fold = 0L
            for (row in parse.rows) fold = fold * 31 + row.start.epochSecond * 7 + bits(row.eur)
            "day rows=${parse.rows.size} first=${parse.rows.firstOrNull()?.start} last=${parse.rows.lastOrNull()?.start} " +
                "published=${parse.published} fold=$fold"
        }
        EntsoeParse.NoData -> "no data"
        is EntsoeParse.Refused -> "refused ${parse.reason}"
    }

    private fun bits(value: Double): Long = java.lang.Double.doubleToLongBits(value)
}
