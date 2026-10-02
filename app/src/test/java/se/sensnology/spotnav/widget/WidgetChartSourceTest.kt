package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chart.DashboardChart
import se.sensnology.spotnav.testing.DashboardFixtures

/** Which chart a widget draws from: the stored dashboard's chart when paired. */
class WidgetChartSourceTest {
    private val stored = StoredDashboard(DashboardFixtures.dashboard("target_soc_estimated.json"), 1_000L)
    private val shown = WidgetSettings(area = "SE4", showChargingPlan = true)
    private val noon = java.time.Instant.parse("2026-09-22T10:00:00Z")
    private val hidden = shown.copy(showChargingPlan = false)

    @Test fun aPairedWidgetWithAStoredDashboardDrawsTheDashboardChart() {
        val source = WidgetChartSource.of("c1", chargerKnown = true, shown, stored, noon) as WidgetChartSource.Dashboard
        val expected = DashboardChart.build(stored.dashboard, shown.intervalMinutes, now = noon)!!
        assertEquals(expected, source.chart)
        assertEquals(expected.bands, source.bands)
        assertTrue(source.bands.isNotEmpty())
        assertEquals(expected.market.areaId, source.areaId)
    }

    @Test fun theWidgetsOwnChoiceHidesTheBandsAndNothingElse() {
        val source = WidgetChartSource.of("c1", chargerKnown = true, hidden, stored, noon) as WidgetChartSource.Dashboard
        assertTrue(source.bands.isEmpty())
        assertEquals(DashboardChart.build(stored.dashboard, hidden.intervalMinutes, now = noon), source.chart)
    }

    @Test fun theBoundaryIsComputedFromExactlyWhatWasDrawn() {
        val source = WidgetChartSource.of("c1", chargerKnown = true, shown, stored, noon) as WidgetChartSource.Dashboard
        assertSame(source.chart.market, source.need.market)
        assertSame(source.chart.prices, source.need.result)
    }

    @Test fun anUnpairedWidgetKeepsThePlanPass() {
        assertSame(WidgetChartSource.Pass, WidgetChartSource.of(null, chargerKnown = false, shown, stored))
    }

    @Test fun aWidgetWhoseChargerIsGoneKeepsThePlanPass() {
        assertSame(WidgetChartSource.Pass, WidgetChartSource.of("c1", chargerKnown = false, shown, stored))
    }

    @Test fun aPairedWidgetWithNoDashboardYetFallsBackToThePlanPass() {
        assertSame(WidgetChartSource.Pass, WidgetChartSource.of("c1", chargerKnown = true, shown, null))
    }

    @Test fun aDashboardWithNothingPricedIsNoChartAndFallsBack() {
        val unpriced = StoredDashboard(
            DashboardFixtures.dashboard("target_soc_estimated.json") {
                getJSONObject("prices").put("intervals", org.json.JSONArray())
            },
            1_000L
        )
        assertSame(WidgetChartSource.Pass, WidgetChartSource.of("c1", chargerKnown = true, shown, unpriced, noon))
        assertNotNull(stored)
    }

    @Test fun aPlanWindowTomorrowIsShadedOnTheEveningBefore() {
        val tomorrowPlan = StoredDashboard(
            DashboardFixtures.dashboard("target_soc_estimated.json") {
                val periods = org.json.JSONArray().put(
                    org.json.JSONObject().put("start", "2026-09-23T09:30:00+00:00").put("end", "2026-09-23T12:45:00+00:00")
                )
                getJSONObject("plan").getJSONObject("proposal").put("periods", periods)
                getJSONObject("plan").getJSONObject("installed").put("periods", periods)
            },
            1_000L
        )
        val evening = java.time.Instant.parse("2026-09-22T18:00:00Z")
        val source = WidgetChartSource.of("c1", chargerKnown = true, shown, tomorrowPlan, evening) as WidgetChartSource.Dashboard
        assertEquals(96, source.chart.prices.tomorrow.size)
        assertEquals(
            listOf(se.sensnology.spotnav.chart.ChartBand(java.time.LocalDate.parse("2026-09-23"), 690f, 885f)),
            source.bands
        )
    }
}
