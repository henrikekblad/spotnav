package se.sensnology.spotnav.push

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A wake-up reaches the screen in view, so it reads its charger at once. */
class PushWakeListenerTest {
    @After fun clear() { PushWakeListener.set(null) }

    @Test fun aWakeOrATestReachesTheListener() {
        var heard = 0
        PushWakeListener.set { heard++ }
        PushWakeListener.deliver(PushWake.Action.CHECK)
        PushWakeListener.deliver(PushWake.Action.TEST_AND_CHECK)
        assertEquals(2, heard)
    }

    @Test fun nothingAskedReachesNobody() {
        var heard = 0
        PushWakeListener.set { heard++ }
        PushWakeListener.deliver(PushWake.Action.NONE)
        assertEquals(0, heard)
    }

    @Test fun withNoScreenListeningAWakeIsDropped() {
        var heard = 0
        PushWakeListener.set { heard++ }
        PushWakeListener.set(null)
        PushWakeListener.deliver(PushWake.Action.CHECK)
        assertEquals(0, heard)
    }
}
