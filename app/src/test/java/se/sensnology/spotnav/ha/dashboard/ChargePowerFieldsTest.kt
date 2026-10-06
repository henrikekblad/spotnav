package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.DashboardFixtures

/** The live measured current and the installed schedule's power, read leniently for the charge bar. */
class ChargePowerFieldsTest {
    private fun withLive(value: Any?) = DashboardFixtures.dashboard {
        getJSONObject("live").put("measured_current_a", value ?: JSONObject.NULL)
    }

    private fun withInstalledPower(value: Any?) = DashboardFixtures.dashboard {
        getJSONObject("plan").put("installed", JSONObject().apply {
            put("active_period_index", JSONObject.NULL)
            put("amps", 10)
            put("identity", "x")
            put("phases", 3)
            put("power_kw", value ?: JSONObject.NULL)
            put("periods", JSONArray())
        })
    }

    @Test fun readsTheMeasuredCurrent() {
        assertEquals(15.5, withLive(15.5).live.measuredCurrentA!!, 1e-9)
        assertEquals(0.0, withLive(0).live.measuredCurrentA!!, 1e-9)
    }

    @Test fun anUnreadableMeasuredCurrentIsSimplyNotThere() {
        assertNull(withLive(null).live.measuredCurrentA)
        assertNull(withLive("16").live.measuredCurrentA)
        assertNull(withLive(-1).live.measuredCurrentA)
        assertNull(DashboardFixtures.dashboard { getJSONObject("live").remove("measured_current_a") }.live.measuredCurrentA)
    }

    @Test fun readsTheInstalledPower() {
        assertEquals(6.9, withInstalledPower(6.9).plan.installed!!.powerKw!!, 1e-9)
        assertNull(withInstalledPower(null).plan.installed!!.powerKw)
        assertNull(withInstalledPower(0).plan.installed!!.powerKw)
        assertNull(withInstalledPower("6.9").plan.installed!!.powerKw)
    }
}
