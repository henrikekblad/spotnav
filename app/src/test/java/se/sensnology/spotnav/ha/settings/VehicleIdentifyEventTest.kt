package se.sensnology.spotnav.ha.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.testing.FakeKeyValueStore

/**
 * The notification event `vehicle_identify` ("Which car is plugged in?"): one of the Companion app's
 * events, on by default, and never one of this phone's own checks (the app shows the question itself).
 */
class VehicleIdentifyEventTest {
    @Test fun itIsACompanionEventOnByDefault() {
        assertEquals(NotificationEvent.VEHICLE_IDENTIFY, NotificationEvent.of("vehicle_identify"))
        assertTrue(NotificationEvent.VEHICLE_IDENTIFY in NotificationEvent.DEFAULTS)
        assertEquals(
            listOf("plan_stopped", "plan_at_risk", "charge_complete", "vehicle_identify"),
            HaSettingsCodec.canonicalEvents(listOf("vehicle_identify", "charge_complete", "plan_at_risk", "plan_stopped"))
        )
    }

    @Test fun thisPhonesOwnChecksNeverOfferIt() {
        assertFalse(NotificationEvent.VEHICLE_IDENTIFY in NotificationEvent.LOCAL)
        assertEquals(
            setOf(NotificationEvent.PLAN_STOPPED, NotificationEvent.PLAN_AT_RISK, NotificationEvent.CHARGE_COMPLETE),
            LocalNotificationStore(FakeKeyValueStore()).events
        )
    }
}
