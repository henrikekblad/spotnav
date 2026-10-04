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

    @Test
    fun `a car finishing past the last window shows nothing when it stops taking current`() {
        assertFalse(advisory {
            getJSONObject("status").getJSONArray("lines")
                .put(JSONObject().put("code", "topping_off").put("params", JSONObject().put("until", "2026-09-22T05:40:00+00:00")))
        })
    }

    @Test
    fun `a charge to the car's own limit shows nothing when the car stops taking current`() {
        assertFalse(advisory {
            getJSONObject("status").getJSONArray("lines")
                .put(JSONObject().put("code", "charging_to_vehicle_limit").put("params", JSONObject().put("percent", 100)))
        })
    }
}
