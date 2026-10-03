package se.sensnology.spotnav.chart

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardDecodeException
import se.sensnology.spotnav.ha.dashboard.DashboardForecastChoice
import se.sensnology.spotnav.ha.dashboard.DashboardMarket
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.testing.HaFixtures
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** A paired charger's chart and plan figures, read from the dashboard's `prices` and `plan`. */
class DashboardChartTest {
    private val NOON = OffsetDateTime.parse("2026-09-22T12:00:00+02:00").toInstant()

    private fun fixture(name: String) = Dashboard.parse(HaFixtures.json("dashboard/$name.json"))

    @Test fun everyVendoredFixtureDecodesItsPrices() {
        for (file in HaFixtures.files("dashboard")) {
            val d = Dashboard.parse(JSONObject(file.readText()))
            assertEquals(file.name, JSONObject(file.readText()).getJSONObject("prices").getInt("interval_count"), d.prices.intervals.size)
        }
        val d = fixture("cheapest_direct_site_admin")
        assertEquals("ready", d.prices.state)
        assertEquals("2026-09-22", d.prices.today)
        assertEquals(15, d.prices.resolutionMinutes)
        assertEquals(192, d.prices.intervals.size)
        val first = d.prices.intervals.first()
        assertEquals(208.711525, first.effectivePrice!!, 1e-9)
        assertEquals(15, first.durationMinutes)
    }

    @Test fun aMalformedIntervalRefusesTheAnswerButUnknownKeysDoNot() {
        val json = HaFixtures.json("dashboard/cheapest_direct_site_admin.json")
        json.getJSONObject("prices").getJSONArray("intervals").getJSONObject(0).put("added_later", "x")
        assertNotNull(Dashboard.parse(json))
        json.getJSONObject("prices").getJSONArray("intervals").getJSONObject(0).put("start", "yesterday-ish")
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(json) }
        val noPrices = HaFixtures.json("dashboard/cheapest_direct_site_admin.json").apply { remove("prices") }
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(noPrices) }
    }

    @Test fun theSitesForecastChoicesAreDecoded() {
        val site = fixture("hybrid_derived_site_with_forecast").site!!
        assertEquals(listOf(DashboardForecastChoice("roof_hybrid", "Roof (hybrid)")), site.solarForecastChoices)
        assertEquals(listOf("roof_hybrid"), site.solarForecastSelected)
        assertTrue(fixture("cheapest_direct_site_admin").site!!.solarForecastChoices.isEmpty())
    }

    @Test fun theChartIsHomeAssistantsOwnAllInPriceWithNoFiscalAppliedAgain() {
        val d = fixture("cheapest_direct_site_admin")
        val chart = DashboardChart.build(d, 15, now = NOON)!!
        assertEquals("SE4", chart.market.areaId)
        assertEquals(FiscalInput.OFF, chart.market.vat)
        assertEquals(FiscalInput.OFF, chart.market.tax)
        assertEquals(FiscalInput.OFF, chart.market.transfer)
        // The renderer's own arithmetic returns the number Home Assistant stated.
        val stated = d.prices.intervals.first().effectivePrice!!
        assertEquals(stated, chart.market.apply(chart.prices.today.first().pricePerKwh), 1e-9)
        assertEquals(96, chart.prices.today.size)
        assertEquals(96, chart.prices.tomorrow.size)
        // Today is the day `prices.today` names and the market's own clock.
        val zone = ZoneId.of("Europe/Stockholm")
        assertTrue(chart.prices.today.all { it.start.atZoneSameInstant(zone).toLocalDate() == LocalDate.parse("2026-09-22") })
        assertTrue(chart.prices.tomorrow.all { it.start.atZoneSameInstant(zone).toLocalDate() == LocalDate.parse("2026-09-23") })
    }

    @Test fun theHourlyPresentationAveragesTheSameAllInPrices() {
        val d = fixture("cheapest_direct_site_admin")
        val chart = DashboardChart.build(d, 60, now = NOON)!!
        val hour = PriceAggregation.aggregate(chart.prices.today, chart.market).first()
        val quarters = d.prices.intervals.take(4).map { it.effectivePrice!! }
        assertEquals(quarters.average(), chart.market.apply(hour.second), 1e-6)
    }

    @Test fun anUnknownPriceIsAGapNeverAZero() {
        val json = HaFixtures.json("dashboard/cheapest_direct_site_admin.json")
        val intervals = json.getJSONObject("prices").getJSONArray("intervals")
        intervals.getJSONObject(0).put("effective_price", JSONObject.NULL).put("known", false)
        val chart = DashboardChart.build(Dashboard.parse(json), 15, now = NOON)!!
        assertEquals(95, chart.prices.today.size)
    }

    @Test fun theProposalShadesTheChartAndStatesItsFacts() {
        val d = fixture("cheapest_direct_site_admin")
        val chart = DashboardChart.build(d, 15, now = NOON)!!
        // 12:15-15:15 local on 2026-09-22 (10:15-13:15 UTC, +02:00): three phases, so a shorter window.
        val band = chart.bands.single()
        assertEquals(LocalDate.parse("2026-09-22"), band.date)
        assertEquals(12 * 60 + 15f, band.fromMinute, 0f)
        assertEquals(15 * 60 + 15f, band.toMinute, 0f)
        val footer = chart.footer!!
        assertEquals(1, footer.periodCount)
        assertEquals(20.784609690826528, footer.energyKwh, 1e-9)
        assertEquals(10.392304845413264, footer.distanceMil, 1e-9)
        assertFalse(footer.unpriced)
    }

    @Test fun theFiguresAreTheProposalsWithItsCurrencyAndUnit() {
        val f = DashboardChart.figures(fixture("cheapest_direct_site_admin"))!!
        assertTrue(f.fromProposal)
        assertEquals(28.737126984088583, f.costMajor!!, 1e-9)
        assertEquals("SEK", f.costCurrency)
        assertEquals("kr", f.costUnit)
        assertEquals(20.784609690826528, f.energyKwh!!, 1e-9)
        assertEquals(10.392304845413264, f.distanceMil!!, 1e-9)
        assertEquals(OffsetDateTime.parse("2026-09-22T10:15:00+00:00"), f.periods.single().start)
        assertEquals("28.74 kr", DashboardChart.costText(f, Locale.US))
        assertEquals("28,74 kr", DashboardChart.costText(f, Locale.forLanguageTag("sv")))
    }

    @Test fun aCostInAnotherCurrencyIsNeverLabelledWithTheMarketsUnit() {
        val market = DashboardMarket("SE4", "Europe/Stockholm", "SEK", "kr", "öre")
        assertEquals("kr", DashboardChart.moneyUnit("SEK", market))
        assertEquals("EUR", DashboardChart.moneyUnit("EUR", market))
        assertEquals("SEK", DashboardChart.moneyUnit("SEK", market.copy(majorUnit = null)))
    }

    @Test fun theTargetSocFixtureStatesItsFiguresToo() {
        val f = DashboardChart.figures(fixture("target_soc_estimated"))!!
        assertEquals(82.9205950375, f.costMajor!!, 1e-9)
        assertEquals(34.5, f.energyKwh!!, 1e-9)
        assertEquals(17.25, f.distanceMil!!, 1e-9)
    }

    @Test fun withNoPlanThereAreNoFiguresAndNoBands() {
        val d = fixture("waiting_for_publication")
        assertNull(DashboardChart.figures(d))
        val chart = DashboardChart.build(d, 15, now = NOON)!!
        assertTrue(chart.bands.isEmpty())
        assertNull(chart.footer)
        // One market day only: the second is not published yet.
        assertEquals(96, chart.prices.today.size)
        assertTrue(chart.prices.tomorrow.isEmpty())
    }

    @Test fun anAnswerWithNoPricesOrNoAreaDrawsNothing() {
        assertNull(DashboardChart.build(fixture("solar_derived_site"), 15, now = NOON))
    }

    @Test fun anInstalledScheduleWithNoProposalShowsItsPeriodsAndNoFigures() {
        val json = HaFixtures.json("dashboard/cheapest_direct_site_admin.json")
        json.getJSONObject("plan").put("proposal", JSONObject.NULL)
        val d = Dashboard.parse(json)
        val f = DashboardChart.figures(d)!!
        assertFalse(f.fromProposal)
        assertNull(f.costMajor)
        assertNull(f.energyKwh)
        val chart = DashboardChart.build(d, 15, now = NOON)!!
        assertEquals(1, chart.bands.size)
        assertNull(chart.footer)
    }

    @Test fun anUnpricedProposalIsMarkedAsSuch() {
        val json = HaFixtures.json("dashboard/cheapest_direct_site_admin.json")
        json.getJSONObject("plan").getJSONObject("proposal").put("unpriced_slots", 3).put("unpriced", true)
        val f = DashboardChart.figures(Dashboard.parse(json))!!
        assertTrue(f.unpriced)
        assertEquals(3, f.unpricedSlots)
        assertTrue(DashboardChart.build(Dashboard.parse(json), 15, now = NOON)!!.footer!!.unpriced)
    }

    @Test fun theSameAnswerIsTheSameChartSoAnUnchangedScreenIsNotRedrawn() {
        val d = fixture("cheapest_direct_site_admin")
        assertEquals(DashboardChart.build(d, 15, now = NOON), DashboardChart.build(d, 15, now = NOON))
        assertEquals(
            OffsetDateTime.parse("2026-09-22T06:00:00+00:00").toInstant().toEpochMilli(),
            DashboardChart.stamp(d)
        )
    }

    private fun at(iso: String) = OffsetDateTime.parse(iso).toInstant()

    @Test fun theDayIsCutByTheLocalClockNotByTheAnswersOwnToday() {
        // The answer was composed on the 22nd (today = 22nd, tomorrow = 23rd); the screen is opened on the morning of the 23rd.
        val d = fixture("cheapest_direct_site_admin")
        val chart = DashboardChart.build(d, 15, now = at("2026-09-23T07:30:00+02:00"))!!
        val zone = ZoneId.of("Europe/Stockholm")
        assertEquals(96, chart.prices.today.size)
        assertTrue(chart.prices.today.all { it.start.atZoneSameInstant(zone).toLocalDate() == LocalDate.parse("2026-09-23") })
        assertTrue(chart.prices.tomorrow.isEmpty())
    }

    @Test fun theDayChangesAtLocalMidnightAndNotBefore() {
        val d = fixture("cheapest_direct_site_admin")
        val before = DashboardChart.build(d, 15, now = at("2026-09-22T23:59:59+02:00"))!!
        val after = DashboardChart.build(d, 15, now = at("2026-09-23T00:00:00+02:00"))!!
        assertEquals(LocalDate.parse("2026-09-22"), before.prices.today.first().start.toLocalDate())
        assertEquals(96, before.prices.tomorrow.size)
        assertEquals(LocalDate.parse("2026-09-23"), after.prices.today.first().start.toLocalDate())
        assertTrue(after.prices.tomorrow.isEmpty())
    }

    @Test fun midnightIsTheMarketsNotTheDevicesOrUtc() {
        // 22:30 UTC on the 22nd is 00:30 on the 23rd in Stockholm (+02:00).
        val d = fixture("cheapest_direct_site_admin")
        val chart = DashboardChart.build(d, 15, now = at("2026-09-22T22:30:00+00:00"))!!
        assertEquals(LocalDate.parse("2026-09-23"), chart.prices.today.first().start.toLocalDate())
    }

    @Test fun anAnswerOlderThanItsLastDayDrawsNothingRatherThanAWrongDay() {
        val d = fixture("cheapest_direct_site_admin")
        assertNull(DashboardChart.build(d, 15, now = at("2026-09-25T12:00:00+02:00")))
    }

    @Test fun theLocalDateIsTheMarketsDate() {
        val d = fixture("cheapest_direct_site_admin")
        assertEquals(LocalDate.parse("2026-09-23"), DashboardChart.localDate(d, at("2026-09-22T22:00:00+00:00")))
    }

    /**
     * Portugal is published on the Madrid clock, so for a device in Lisbon the price day changes at
     * 23:00 local (midnight in Madrid), not at the device's own midnight.
     */
    @Test fun aDeviceInLisbonCutsThePortugueseDayAtTwentyThreeHundredLocal() {
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Lisbon"))
        try {
            val json = HaFixtures.json("dashboard/cheapest_direct_site_admin.json")
            json.getJSONObject("market").put("area_id", "PT").put("timezone", "Europe/Madrid")
            val d = Dashboard.parse(json)
            val lisbon = ZoneId.of("Europe/Lisbon")
            val madrid = ZoneId.of("Europe/Madrid")

            // 22:59 in Lisbon is 23:59 in Madrid: still the 22nd.
            val before = DashboardChart.build(d, 15, now = at("2026-09-22T21:59:00+00:00"))!!
            assertEquals(LocalDate.parse("2026-09-22"), before.prices.today.first().start.toLocalDate())
            assertEquals(96, before.prices.tomorrow.size)

            // 23:00 in Lisbon is midnight in Madrid: the 23rd, though it is still the 22nd on the phone.
            val nowInstant = at("2026-09-22T22:00:00+00:00")
            assertEquals(LocalDate.parse("2026-09-22"), nowInstant.atZone(lisbon).toLocalDate())
            val after = DashboardChart.build(d, 15, now = nowInstant)!!
            assertEquals(LocalDate.parse("2026-09-23"), after.prices.today.first().start.toLocalDate())
            assertEquals(96, after.prices.today.size)
            assertTrue(after.prices.tomorrow.isEmpty())
            assertEquals(LocalDate.parse("2026-09-23"), DashboardChart.localDate(d, nowInstant))

            // The redraw is armed for Madrid's midnight, which is 23:00 on a Lisbon clock.
            val next = DayBoundary.nextMidnight(at("2026-09-22T21:00:00+00:00"), madrid)
            assertEquals(at("2026-09-22T22:00:00+00:00"), next)
            assertEquals(java.time.LocalTime.of(23, 0), next.atZone(lisbon).toLocalTime())
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }
}
