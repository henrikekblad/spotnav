package se.sensnology.spotnav.push

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/**
 * A build with push: Firebase Cloud Messaging, used only once instant notifications are turned on.
 * The token is asked for with `getToken`/`deleteToken`, deprecated in favour of `register`, which
 * hands the token only to the service later; this flow needs it at once to register at the relay.
 */
@Suppress("DEPRECATION")
internal object PushPlatforms {
    private const val TAG = "SpotNavPush"
    private const val TIMEOUT_S = 30L

    val current: PushPlatform = object : PushPlatform {
        override fun available(context: Context): Boolean =
            GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

        override fun token(context: Context): String? = try {
            Tasks.await(FirebaseMessaging.getInstance().token, TIMEOUT_S, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            Log.w(TAG, "No push token: ${failure.javaClass.simpleName}")
            null
        }

        override fun deleteToken(context: Context) {
            try {
                Tasks.await(FirebaseMessaging.getInstance().deleteToken(), TIMEOUT_S, TimeUnit.SECONDS)
            } catch (failure: Exception) {
                Log.w(TAG, "The push token could not be deleted: ${failure.javaClass.simpleName}")
            }
        }
    }
}
