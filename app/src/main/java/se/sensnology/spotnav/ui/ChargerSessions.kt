package se.sensnology.spotnav.ui

import android.os.Handler
import android.os.Looper
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.session.HomeAssistantTransport
import se.sensnology.spotnav.ha.session.ProfileDashboardRecorder

/**
 * The session for one charger, on this screen's generation: whatever it answers is delivered on the
 * main thread and dropped when the Activity is gone or the screen has been rebuilt since the
 * request went out.
 */
internal fun ScreenShell.haSession(profile: ChargerProfile): HaSession {
    val generation = generation
    val handler = Handler(Looper.getMainLooper())
    fun current() = !activity.isDestroyed && generation == this.generation
    return HaSession(
        transport = HomeAssistantTransport(profile.toHomeAssistantSettings()),
        recorder = ProfileDashboardRecorder(activity, profile.localId),
        background = io,
        mainThread = { block -> activity.runOnUiThread { if (current()) block() } },
        schedule = { delayMs, block -> handler.postDelayed({ if (current()) block() }, delayMs) }
    )
}
