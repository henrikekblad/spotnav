package se.sensnology.spotnav.ha.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.testing.FakeKeyValueStore

/**
 * The notification event `vehicle_identify` ("Which car is plugged in?"): one of the Companion app's
 * events, on by default, and one of this phone's own where Home Assistant identifies cars.
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

    @Test fun thisPhonesOwnChecksOfferItOnByDefaultWhereHomeAssistantIdentifies() {
        assertTrue(NotificationEvent.VEHICLE_IDENTIFY in NotificationEvent.LOCAL)
        assertEquals(
            setOf(NotificationEvent.PLAN_STOPPED, NotificationEvent.PLAN_AT_RISK, NotificationEvent.CHARGE_COMPLETE, NotificationEvent.VEHICLE_IDENTIFY),
            LocalNotificationStore(FakeKeyValueStore()).events
        )
        assertTrue(NotificationEvent.VEHICLE_IDENTIFY in NotificationEvent.localOffered(identifies = true))
        assertFalse(NotificationEvent.VEHICLE_IDENTIFY in NotificationEvent.localOffered(identifies = false))
        assertEquals(7, NotificationEvent.localOffered(identifies = false).size)
    }

    @Test fun aChoiceStoredBeforeTheQuestionExistedHasItOn() {
        val kv = FakeKeyValueStore()
        kv.putString("events", "[\"plan_stopped\"]")
        assertEquals(setOf(NotificationEvent.PLAN_STOPPED, NotificationEvent.VEHICLE_IDENTIFY), LocalNotificationStore(kv).events)
        // A choice made since keeps it off.
        val store = LocalNotificationStore(kv)
        store.events = setOf(NotificationEvent.PLAN_STOPPED)
        assertEquals(setOf(NotificationEvent.PLAN_STOPPED), store.events)
    }

    @Test fun aSaveWhereTheQuestionIsNotOfferedKeepsItAsItWas() {
        val offered = NotificationEvent.localOffered(identifies = false)
        val ticked = offered.map { it == NotificationEvent.PLAN_STOPPED }
        assertEquals(
            setOf(NotificationEvent.PLAN_STOPPED, NotificationEvent.VEHICLE_IDENTIFY),
            NotificationEvent.localChoice(offered, ticked, previous = setOf(NotificationEvent.VEHICLE_IDENTIFY, NotificationEvent.UNPLUGGED))
        )
        assertEquals(
            setOf(NotificationEvent.PLAN_STOPPED),
            NotificationEvent.localChoice(offered, ticked, previous = emptySet())
        )
    }
}
