package se.sensnology.spotnav.push

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.BuildConfig

/** A build without app/google-services.json contains no Firebase at all; a build with it does. */
class PushBuildTest {
    private fun present(name: String): Boolean = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

    @Test
    fun firebaseIsInTheBuildExactlyWhenPushIs() {
        val push = BuildConfig.PUSH_AVAILABLE
        assertEquals(push, present("com.google.firebase.messaging.FirebaseMessagingService"))
        assertEquals(push, present("com.google.firebase.FirebaseApp"))
        assertEquals(push, present("com.google.android.gms.common.GoogleApiAvailabilityLight"))
        assertEquals(push, present("se.sensnology.spotnav.push.SpotNavMessagingService"))
    }
}
