package se.sensnology.spotnav.chargers

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which single charger action the charging state calls for, when Home Assistant names none. */
class ChargerActionsTest {
    @Test fun onlyImmediateActionsExist() {
        assertEquals(setOf(ChargerAction.START, ChargerAction.STOP), ChargerAction.entries.toSet())
    }

    @Test fun anIdleChargerOffersStartAndAChargingOneStop() {
        assertEquals(ChargerAction.START, ChargerActions.primaryAction(false))
        assertEquals(ChargerAction.STOP, ChargerActions.primaryAction(true))
    }

    @Test fun anUnknownChargingStateIsTreatedAsIdle() {
        assertEquals(ChargerAction.START, ChargerActions.primaryAction(null))
    }
}
