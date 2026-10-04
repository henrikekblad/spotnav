package se.sensnology.spotnav.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The relay's wake-ups. A message carries no content, only `t` (see [PushWake]); everything shown
 * comes from this phone's own check of its own Home Assistant.
 */
class SpotNavMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        PushNotifications.onMessage(applicationContext, message.data)
    }

    /** A new or rotated token, from the older token API this app asks with ([PushPlatforms]). */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        PushNotifications.onNewToken(applicationContext, token)
    }

    /** The same, from the newer registration API, should Firebase hand it out that way. */
    override fun onRegistered(token: String) {
        PushNotifications.onNewToken(applicationContext, token)
    }
}
