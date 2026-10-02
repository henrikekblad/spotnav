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
}
