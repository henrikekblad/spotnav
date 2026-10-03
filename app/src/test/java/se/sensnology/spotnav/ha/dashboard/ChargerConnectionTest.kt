package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures

/** The dashboard's `connection` block and the full-car rule for the not-requesting advisory. */
class ChargerConnectionTest {
    private fun connection(state: String) = DashboardFixtures.dashboard("target_soc_estimated.json") {
        put("connection", JSONObject().put("state", state).put("source", JSONObject.NULL))
    }.connection

    @Test
    fun `every known state is read and unknown or absent shows nothing`() {
        assertEquals(ConnectionState.DISCONNECTED, connection("disconnected"))
        assertEquals(ConnectionState.CONNECTED, connection("connected"))
        assertEquals(ConnectionState.CHARGING, connection("charging"))
        assertEquals(ConnectionState.PAUSED, connection("paused"))
        assertEquals(ConnectionState.FINISHED, connection("finished"))
        assertEquals(ConnectionState.ERROR, connection("error"))
        assertNull(connection("unknown"))
        assertNull(connection("from-the-future"))
        assertNull(DashboardFixtures.dashboard("target_soc_estimated.json") { remove("connection") }.connection)
    }

    @Test
    fun `the line joins the vehicle, its charge and the state`() {
        assertEquals("EV6 · 96 % · Ansluten", ChargerStatusLine.text("EV6", "96 %", "Ansluten"))
        assertEquals("EV6 · 96 %", ChargerStatusLine.text("EV6", "96 %", null))
        assertEquals("Ansluten", ChargerStatusLine.text("EV6", null, "Ansluten"))
        assertNull(ChargerStatusLine.text(null, null, null))
    }

    private fun advisory(edit: JSONObject.() -> Unit = {}) = FullCarRule.advisory(
        DashboardFixtures.dashboard("target_soc_estimated.json") {
            put(
                "charge_progress",
                JSONObject().put("state", "vehicle_not_requesting_current")
                    .put("reason", "suspended_ev_zero_current").put("since", JSONObject.NULL)
            )
            edit()
        }
    )

    @Test
    fun `the advisory shows for a car that still needs charge`() {
        assertTrue(advisory())
    }

    @Test
    fun `a car at its target or maximum, or a need of zero, shows nothing`() {
        assertFalse(advisory { getJSONObject("soc").put("value", 80.0) })
        assertFalse(advisory { getJSONObject("soc").put("value", 85.0).put("target_percent", 90.0).put("vehicle_max_percent", 85.0) })
        assertFalse(advisory { getJSONObject("soc").put("need_kwh", 0.0) })
    }

    @Test
    fun `a satisfied status line shows nothing`() {
        assertFalse(advisory {
            getJSONObject("status").getJSONArray("lines")
                .put(JSONObject().put("code", "hybrid_satisfied").put("params", JSONObject()))
        })
    }
}
