package se.sensnology.spotnav.notify

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * How this phone names itself to Home Assistant (a pairing request, `phone_notifications`): its own name as the person
 * set it (Android's "Device name", e.g. "Anna's Pixel"), else the model; and the model beside it. Home Assistant
 * matches them against the Home Assistant app's phones, whose name and model it knows.
 */
internal object PhoneName {
    /** The phone's model, e.g. "Pixel 8" or "CPH2765". */
    val model: String get() = Build.MODEL.orEmpty().trim()

    fun of(context: Context): String = choose(
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        model
    )

    /** [own] when it says anything, else [model]. */
    fun choose(own: String?, model: String): String = own?.trim()?.takeIf { it.isNotEmpty() } ?: model
}
