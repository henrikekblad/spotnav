package se.sensnology.spotnav.push

import android.content.Context

/** A build without push (no `app/google-services.json`): nothing to offer, and no Firebase. */
internal object PushPlatforms {
    val current: PushPlatform = object : PushPlatform {
        override fun available(context: Context): Boolean = false
        override fun token(context: Context): String? = null
        override fun deleteToken(context: Context) = Unit
    }
}
