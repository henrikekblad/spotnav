package se.sensnology.spotnav.push

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.push.PushWake.Action

class PushWakeTest {
    @Test
    fun aWakeRunsTheCheck() {
        assertEquals(Action.CHECK, PushWake.action(mapOf("t" to "wake"), pushEnabled = true))
    }

    @Test
    fun aTestPostsTheTestNotificationAndRunsTheCheck() {
        assertEquals(Action.TEST_AND_CHECK, PushWake.action(mapOf("t" to "test"), pushEnabled = true))
    }

    @Test
    fun anythingElseOrAMessageWhileOffDoesNothing() {
        assertEquals(Action.NONE, PushWake.action(mapOf("t" to "wake"), pushEnabled = false))
        assertEquals(Action.NONE, PushWake.action(mapOf("t" to "test"), pushEnabled = false))
        assertEquals(Action.NONE, PushWake.action(mapOf("t" to "other"), pushEnabled = true))
        assertEquals(Action.NONE, PushWake.action(emptyMap(), pushEnabled = true))
    }
}
