package se.sensnology.spotnav.push

import android.content.Context
import android.util.Log
import se.sensnology.spotnav.BuildConfig
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.notify.LocalNotifications
import java.util.concurrent.Executors

/**
 * Instant notifications on Android: an opt-in, content-free wake-up through SpotNav Relay and
 * Firebase Cloud Messaging that runs this phone's own notification check at once instead of up to
 * 15 minutes later. Only in a build with push ([BuildConfig.PUSH_AVAILABLE]) on a phone with Google
 * Play services; see [PushRegistration] for what is registered where.
 */
internal object PushNotifications {
    private const val TAG = "SpotNavPush"
    private val worker = Executors.newSingleThreadExecutor()

    /** Whether the switch is offered at all. */
    fun available(context: Context): Boolean =
        BuildConfig.PUSH_AVAILABLE && runCatching { PushPlatforms.current.available(context.applicationContext) }.getOrDefault(false)

    fun enabled(context: Context): Boolean = BuildConfig.PUSH_AVAILABLE && PushStore.forContext(context).enabled

    private fun registration(context: Context): PushRegistration {
        val app = context.applicationContext
        val platform = PushPlatforms.current
        return PushRegistration(
            store = PushStore.forContext(app),
            tokens = object : PushRegistration.Tokens {
                override fun token(): String? = platform.token(app)
                override fun delete() = platform.deleteToken(app)
            },
            relay = PushRelay::register,
            homeAssistant = { localId, ref, events ->
                val profile = ChargerProfileStore.forContext(app).listProfiles().firstOrNull { it.localId == localId && it.configured }
                profile != null && HomeAssistantClient.registerPush(profile.toHomeAssistantSettings(), ref, events)
            },
            local = {
                val profiles = ChargerProfileStore.forContext(app).listProfiles().filter { it.configured }.map { it.localId }
                PushRegistration.Local.of(LocalNotificationStore.forContext(app), profiles)
            }
        )
    }

    private fun background(name: String, block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (failure: Exception) {
                Log.w(TAG, "$name failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Turn instant notifications on or off in the background; [done] gets the result off the main thread. */
    fun setEnabled(context: Context, on: Boolean, done: (PushRegistration.Result) -> Unit) {
        if (!BuildConfig.PUSH_AVAILABLE) return done(PushRegistration.Result.FAILED)
        background("Turning instant notifications ${if (on) "on" else "off"}") {
            val registration = registration(context)
            if (on) {
                done(registration.enable())
            } else {
                registration.disable()
                done(PushRegistration.Result.OFF)
            }
        }
    }

    /** Bring every paired charger's Home Assistant up to date (the events, a pairing, the switch). */
    fun sync(context: Context) {
        if (!BuildConfig.PUSH_AVAILABLE) return
        val app = context.applicationContext
        // Nothing to tell when it was never turned on and no Home Assistant holds a reference.
        val store = PushStore.forContext(app)
        if (!store.enabled && store.known.isEmpty()) return
        background("Updating instant notifications") { registration(app).sync() }
    }

    /** Firebase handed out a new token. */
    fun onNewToken(context: Context, token: String) {
        if (!enabled(context)) return
        background("Registering a new push token") { registration(context).onNewToken(token) }
    }

    /** A push message arrived: run the check now, after a test notification when it is a test. */
    fun onMessage(context: Context, data: Map<String, String>) {
        val action = PushWake.action(data, enabled(context))
        // The charging screen in view reads its charger at once, beside the notification check.
        PushWakeListener.deliver(action)
        when (action) {
            PushWake.Action.NONE -> return
            PushWake.Action.CHECK -> LocalNotifications.checkNow(context)
            PushWake.Action.TEST_AND_CHECK -> {
                LocalNotifications.postTest(context)
                LocalNotifications.checkNow(context)
            }
        }
    }
}
