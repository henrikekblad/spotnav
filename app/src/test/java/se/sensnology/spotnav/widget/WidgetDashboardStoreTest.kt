package se.sensnology.spotnav.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.time.Instant

class WidgetDashboardStoreTest {
    private val captured = Instant.parse("2026-09-22T06:00:00Z").toEpochMilli()
    private val body = DashboardFixtures.json("target_soc_estimated.json").toString()
    private val warnings = mutableListOf<String>()
    private val backing = FakeKeyValueStore()
    private val store = WidgetDashboardStore(backing) { warnings += it }

    @Test fun aStoredAnswerComesBackAsTheDashboardItWas() {
        assertNull(store.dashboardFor("c1"))
        store.put("c1", body, captured)
        val read = store.dashboardFor("c1")!!
        assertEquals(captured, read.capturedAt)
        assertEquals(DashboardFixtures.dashboard("target_soc_estimated.json"), read.dashboard)
        assertNull(store.dashboardFor("other"))
        assertNull(store.dashboardFor(null))
        assertNull(store.dashboardFor(""))
    }

    @Test fun theStatusLineIsWordedFromTheSameDashboardTheChartIsDrawnFrom() {
        store.put("c1", body, captured)
        val read = store.dashboardFor("c1")!!
        val line = WidgetStatusLine.compose(read.status, "sv", Instant.ofEpochMilli(captured + 10 * 60_000))!!
        assertEquals("Planerat från 10:15 · 34,5 kWh · 82,92 kr · 17,3 mil", line.text)
    }

    @Test fun anOlderCaptureNeverReplacesANewerOne() {
        store.put("c1", body, captured)
        store.put("c1", DashboardFixtures.json().toString(), captured - 1_000)
        val read = store.dashboardFor("c1")!!
        assertEquals(captured, read.capturedAt)
        assertEquals(DashboardFixtures.dashboard("target_soc_estimated.json"), read.dashboard)
        // The same instant, or a later one, does replace it.
        store.put("c1", DashboardFixtures.json().toString(), captured + 1)
        assertEquals(captured + 1, store.dashboardFor("c1")!!.capturedAt)
    }

    @Test fun oneValueHoldsTheTimeAndTheAnswerSoTheyCannotBePairedFromDifferentReads() {
        store.put("c1", body, captured)
        val raw = backing.rawOrNull("dashboard.c1")!!
        assertTrue(raw.startsWith("$captured\n"))
        assertEquals(body, raw.substringAfter('\n'))
    }

    @Test fun clearingForgetsOneChargerOnly() {
        store.put("c1", body, captured)
        store.put("c2", body, captured)
        store.clear("c1")
        assertNull(store.dashboardFor("c1"))
        assertEquals(captured, store.dashboardFor("c2")!!.capturedAt)
    }

    @Test fun anUnreadableDocumentIsAbsentAndSaysSo() {
        backing.setRaw("dashboard.c1", "$captured\n{not json")
        assertNull(store.dashboardFor("c1"))
        backing.setRaw("dashboard.c2", "no time\n$body")
        assertNull(store.dashboardFor("c2"))
        backing.setRaw("dashboard.c3", "$captured\n{\"api_version\":2}")
        assertNull(store.dashboardFor("c3"))
        assertEquals(3, warnings.size)
    }
}
