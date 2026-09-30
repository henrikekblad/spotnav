package se.sensnology.spotnav.chargers

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargerProfileLabelTest {
    private fun profile(displayName: String, remoteChargerName: String?) = ChargerProfile(
        localId = "local-a",
        displayName = displayName,
        baseUrl = "http://192.168.1.50:8123",
        webhookId = "wh-a",
        remoteChargerName = remoteChargerName
    )

    @Test fun theLabelPrefersTheNameHomeAssistantReports() {
        assertEquals("Remote name", profile("My charger", "Remote name").label("Fallback"))
        assertEquals("Remote name", profile("Old name", "Remote name").label("Fallback"))
        assertEquals("Fallback", profile("", null).label("Fallback"))
        assertEquals("Fallback", profile("   ", "  ").label("Fallback"))
    }

    @Test fun theLabelFallsBackToTheStoredNameWhenHomeAssistantHasNone() {
        assertEquals("My charger", profile("My charger", null).label("Fallback"))
        assertEquals("My charger", profile("My charger", "   ").label("Fallback"))
        assertEquals("My charger", profile("  My charger  ", null).label("Fallback"))
    }
}
