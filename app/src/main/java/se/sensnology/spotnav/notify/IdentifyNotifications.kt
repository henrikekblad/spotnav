package se.sensnology.spotnav.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.IdentifyVehicle
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.NotificationEvent
import java.util.concurrent.Executors

/**
 * The question in view on the charging screen: which question's banner it shows (its key, see
 * [IdentifyNotice]) while the app is in the foreground, so the background check does not post it too.
 */
internal object InAppQuestion {
    @Volatile var foreground: Boolean = false
    @Volatile var banner: String? = null

    val visible: String? get() = if (foreground) banner else null
}

/**
 * "Which car is plugged in?" as this phone's own notification (the decisions are [IdentifyNotice]'s):
 * posted by the background check, one car button each answering through the charger's webhook
 * (`identify_vehicle`, the same action the in-app banner sends) from [IdentifyAnswerReceiver], so it
 * works with the app closed. One notification per charger, replaced in place: a person's answer
 * becomes "EV6 · selected" silently and goes after a short while; a failure says to open the app.
 */
internal object IdentifyNotifications {
    private const val TAG = "SpotNavNotify"
    private const val ACTION_ANSWER = "se.sensnology.spotnav.action.IDENTIFY_ANSWER"
    private const val EXTRA_PROFILE = "profile"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_VEHICLE = "vehicle"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_CHARGER = "charger"

    /** How long the answer's confirmation stays. */
    private const val CONFIRMED_MS = 8_000L

    private val ID = NotificationEvent.VEHICLE_IDENTIFY.ordinal

    private fun tag(localId: String) = "$localId:${NotificationEvent.VEHICLE_IDENTIFY.wire}"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** One check's read of one charger: post, keep or take off its question. */
    fun follow(context: Context, store: LocalNotificationStore, localId: String, chargerName: String, dashboard: Dashboard, chosen: Boolean) {
        when (val step = IdentifyNotice.step(dashboard, chosen, store.identifyPosted(localId), InAppQuestion.visible)) {
            is IdentifyNotice.Step.Post -> {
                // Not allowed now: not remembered either, so a later check posts it once it is.
                if (!LocalNotifications.allowed(context)) return
                if (post(context, localId, chargerName, step.question)) store.setIdentifyPosted(localId, step.question.key)
            }
            IdentifyNotice.Step.Retire, IdentifyNotice.Step.Suppress -> {
                cancel(context, localId)
                store.setIdentifyPosted(localId, null)
            }
            IdentifyNotice.Step.Keep -> Unit
        }
    }

    private fun builder(context: Context, chargerName: String): Notification.Builder =
        Notification.Builder(context, LocalNotifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_spotnav_monochrome)
            .setContentTitle(AppLanguageSettings.text(context, R.string.identify_question))
            .setContentIntent(LocalNotifications.openApp(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

    private fun answerIntent(context: Context, localId: String, chargerName: String, key: String, car: IdentifyNotice.Car, index: Int): PendingIntent {
        val intent = Intent(context, IdentifyAnswerReceiver::class.java)
            .setAction(ACTION_ANSWER)
            // A distinct address per button, so no two buttons share one pending intent.
            .setData(Uri.parse("spotnav-identify://$localId/$index"))
            .putExtra(EXTRA_PROFILE, localId)
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_VEHICLE, car.vehicleId)
            .putExtra(EXTRA_NAME, car.name)
            .putExtra(EXTRA_CHARGER, chargerName)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(context: Context, localId: String, chargerName: String, question: IdentifyNotice.Question): Boolean {
        LocalNotifications.ensureChannels(context)
        val icon = Icon.createWithResource(context, R.drawable.ic_spotnav_monochrome)
        val builder = builder(context, chargerName).setContentText(chargerName)
        question.cars.forEachIndexed { index, car ->
            builder.addAction(Notification.Action.Builder(icon, car.name, answerIntent(context, localId, chargerName, question.key, car, index)).build())
        }
        if (question.openButton) {
            val open = AppLanguageSettings.text(context, R.string.notify_identify_open)
            builder.addAction(Notification.Action.Builder(icon, open, LocalNotifications.openApp(context)).build())
        }
        return notify(context, localId, builder.build())
    }

    private fun notify(context: Context, localId: String, notification: Notification): Boolean = try {
        manager(context).notify(tag(localId), ID, notification)
        true
    } catch (denied: SecurityException) {
        Log.w(TAG, "Notifications are not allowed")
        false
    }

    fun cancel(context: Context, localId: String) {
        manager(context).cancel(tag(localId), ID)
    }

    /**
     * The question replaced by what came of a button, the car chosen or that it failed: silently, as the
     * same notification still showing with only-alert-once does not sound again.
     */
    private fun replace(context: Context, localId: String, chargerName: String, text: String, timeoutMs: Long?) {
        LocalNotifications.ensureChannels(context)
        val builder = builder(context, chargerName).setContentText(text).setSubText(chargerName)
        timeoutMs?.let { builder.setTimeoutAfter(it) }
        notify(context, localId, builder.build())
    }

    /**
     * A car button was tapped: answer, when the question it was posted for is still open, through the
     * charger's webhook. Blocks; never on the main thread. Never throws.
     */
    fun answer(context: Context, intent: Intent) {
        val localId = intent.getStringExtra(EXTRA_PROFILE) ?: return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val vehicleId = intent.getStringExtra(EXTRA_VEHICLE) ?: return
        val name = intent.getStringExtra(EXTRA_NAME) ?: vehicleId
        val chargerName = intent.getStringExtra(EXTRA_CHARGER).orEmpty()
        val store = LocalNotificationStore.forContext(context)
        fun gone() {
            cancel(context, localId)
            if (store.identifyPosted(localId) == key) store.setIdentifyPosted(localId, null)
        }
        fun failed() = replace(context, localId, chargerName, AppLanguageSettings.text(context, R.string.notify_identify_failed), null)
        val profile = ChargerProfileStore.forContext(context).listProfiles().firstOrNull { it.localId == localId && it.configured }
            ?: return gone()
        val settings = profile.toHomeAssistantSettings()
        val dashboard = runCatching { HomeAssistantClient.dashboard(settings, connectTimeoutMs = 8_000, readTimeoutMs = 12_000) }.getOrNull()
        when (IdentifyNotice.tap(dashboard, key)) {
            // Answered elsewhere, decided by the cars, or another plug-in's question by now: it takes itself off.
            IdentifyNotice.Tap.GONE -> gone()
            IdentifyNotice.Tap.FAILED -> failed()
            IdentifyNotice.Tap.ANSWER -> when (HomeAssistantClient.identifyVehicle(settings, vehicleId)) {
                is IdentifyVehicle.Outcome.Identified -> {
                    // Nothing posted any more: a later check's retirement leaves the confirmation alone.
                    if (store.identifyPosted(localId) == key) store.setIdentifyPosted(localId, null)
                    replace(context, localId, chargerName, AppLanguageSettings.text(context, R.string.notify_identify_chosen, name), CONFIRMED_MS)
                }
                IdentifyVehicle.Outcome.NotPluggedIn -> gone()
                else -> failed()
            }
        }
    }
}

/** A car button of the question: answered off the main thread, with the app closed as well. */
class IdentifyAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                IdentifyNotifications.answer(app, intent)
            } catch (failure: Exception) {
                Log.w("SpotNavNotify", "The answer failed: ${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
