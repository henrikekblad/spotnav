package se.sensnology.spotnav.push

import android.content.Context

/**
 * This phone's side of Firebase Cloud Messaging. Only a build with push has a real one
 * (`app/src/push`); every other build has [PushPlatforms.current] that offers nothing
 * (`app/src/nopush`) and contains no Firebase.
 */
internal interface PushPlatform {
    /** Whether this build has push and the phone has Google Play services. */
    fun available(context: Context): Boolean

    /** This app's current push token, or `null`. Blocks: never on the main thread. */
    fun token(context: Context): String?

    /** Invalidate the token, so nothing can wake this app any more. Blocks; never throws. */
    fun deleteToken(context: Context)
}
