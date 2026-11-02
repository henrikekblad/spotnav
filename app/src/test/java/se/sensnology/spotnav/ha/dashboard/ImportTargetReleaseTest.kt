package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

/** "Release the import target tonight": beside the at-risk line where the plan asks, and only where Home Assistant answers it. */
class ImportTargetReleaseTest {
    private fun dashboard(releasable: Any?, capability: Boolean = true): Dashboard {
        val json = HaFixtures.json("webhook/dashboard.json")
        json.getJSONObject("charger").getJSONObject("capabilities").put("release_import_target", capability)
        val params = JSONObject().put("departure", "2026-09-23T05:00:00+00:00").put("missing_kwh", 4.0).put("target_kw", 6.0)
        if (releasable != null) params.put("releasable", releasable)
        json.getJSONObject("status").getJSONArray("lines").put(JSONObject().put("code", "import_target_at_risk").put("params", params))
        return Dashboard.parse(json)
    }

    @Test fun offeredBesideTheAtRiskLineWhereThePlanAsks() {
        assertEquals("Släpp effektmålet i natt", ImportTargetRelease.label(dashboard(true), "sv"))
        assertEquals("Release the import target tonight", ImportTargetRelease.label(dashboard(null), "en"))
    }

    @Test fun notOfferedWhereThePlanHoldsTheTargetOrHomeAssistantCannot() {
        assertNull(ImportTargetRelease.label(dashboard(false), "sv"))
        assertNull(ImportTargetRelease.label(dashboard(true, capability = false), "sv"))
        assertNull(ImportTargetRelease.label(Dashboard.parse(HaFixtures.json("webhook/dashboard.json")), "sv"))
    }
}
