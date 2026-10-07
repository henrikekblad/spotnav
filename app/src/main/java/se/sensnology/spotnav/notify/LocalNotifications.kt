package se.sensnology.spotnav.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.LauncherActivity
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.push.PushNotifications
import se.sensnology.spotnav.vehicles.VehicleIdentification
import java.text.NumberFormat
import java.time.Instant
import java.util.concurrent.Executors

/**
 * This phone's own notifications for a paired charger, without the Home Assistant Companion app: a
 * periodic background check (Android's job scheduler, every 15 minutes at the soonest, only with a
 * network) reads each paired charger's dashboard and posts what changed (see [NotificationRules]).
 * Off by default; never scheduled while nothing is paired.
 */
internal object LocalNotifications {
    private const val TAG = "SpotNavNotify"
    private const val JOB_ID = 13_050
    private const val NOW_JOB_ID = 13_052
    private const val TEST_TAG = "push_test"
    private const val PERIOD_MS = 15 * 60 * 1000L
    private const val FLEX_MS = 5 * 60 * 1000L

    const val CHANNEL_ALERTS = "spotnav_alerts"
    const val CHANNEL_UPDATES = "spotnav_updates"

    /** The request code of the Android 13+ permission prompt. */
    const val PERMISSION_REQUEST = 13_051

    private val ALERTS = setOf(NotificationEvent.PLAN_STOPPED, NotificationEvent.PLAN_AT_RISK)

    private fun pairedProfiles(context: Context): List<ChargerProfile> =
        ChargerProfileStore.forContext(context.applicationContext).listProfiles().filter { it.configured }

    /** Whether Android 13+ needs the person to allow notifications first. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Whether a notification posted now would be shown. */
    fun allowed(context: Context): Boolean =
        !needsPermission(context) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** Schedule the check when it is on and something is paired; cancel it otherwise. */
    private fun schedule(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val wanted = LocalNotificationStore.forContext(app).enabled && pairedProfiles(app).isNotEmpty()
        if (!wanted) {
            if (scheduler.getPendingJob(JOB_ID) != null) scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(app, LocalNotificationJob::class.java))
            .setPeriodic(PERIOD_MS, FLEX_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        // A refused schedule (a missing permission, a system limit) must never take the app down.
        val result = runCatching { scheduler.schedule(job) }.getOrElse { error ->
            Log.w(TAG, "The notification check could not be scheduled: ${error.javaClass.simpleName}")
            JobScheduler.RESULT_FAILURE
        }
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "The notification check could not be scheduled")
    }

    /** [schedule], and bring the instant notifications every Home Assistant holds up to date. */
    fun sync(context: Context) {
        schedule(context)
        PushNotifications.sync(context)
    }

    /**
     * One check as soon as there is a network, for a push wake-up: an expedited job where Android
     * has them (12+), an immediate one otherwise. It replaces one still waiting.
     */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
        val builder = JobInfo.Builder(NOW_JOB_ID, ComponentName(app, LocalNotificationJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(true)
        val result = runCatching { scheduler.schedule(builder.build()) }.getOrElse { error ->
            Log.w(TAG, "The immediate check could not be scheduled: ${error.javaClass.simpleName}")
            JobScheduler.RESULT_FAILURE
        }
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "The immediate check could not be scheduled")
    }

    /** A test push from Home Assistant: a fixed notification saying instant notifications work. */
    fun postTest(context: Context) {
        val app = context.applicationContext
        if (!allowed(app)) return
        ensureChannels(app)
        val message = AppLanguageSettings.text(app, R.string.notify_push_test)
        val notification = Notification.Builder(app, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_spotnav_monochrome)
            .setContentTitle(AppLanguageSettings.text(app, R.string.app_name))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp(app))
            .setAutoCancel(true)
            .build()
        try {
            app.getSystemService(NotificationManager::class.java).notify(TEST_TAG, 0, notification)
        } catch (denied: SecurityException) {
            Log.w(TAG, "Notifications are not allowed")
        }
    }

    /** One profile was unpaired: its snapshot goes, and the check stops when nothing is paired. */
    fun onProfileForgotten(context: Context, localId: String) {
        LocalNotificationStore.forContext(context).forget(localId)
        IdentifyNotifications.cancel(context.applicationContext, localId)
        sync(context)
    }

    /** One background check of every paired charger. Never throws. */
    fun check(context: Context, now: Instant = Instant.now()) {
        val app = context.applicationContext
        val store = LocalNotificationStore.forContext(app)
        val profiles = pairedProfiles(app)
        if (!store.enabled || profiles.isEmpty()) {
            sync(app)
            return
        }
        val chosen = store.events
        var identifyingChanged = false
        for (profile in profiles) {
            val dashboard = runCatching {
                HomeAssistantClient.dashboard(profile.toHomeAssistantSettings(), connectTimeoutMs = 10_000, readTimeoutMs = 20_000)
            }.getOrNull() ?: continue
            // Whether this Home Assistant takes the question as an instant-notification event.
            if (store.setIdentifies(profile.localId, VehicleIdentification.advertised(dashboard))) identifyingChanged = true
            val current = NotificationRules.snapshot(dashboard, now)
            val derivation = NotificationRules.derive(store.snapshot(profile.localId), current, chosen, store.lastSent(profile.localId))
            store.remember(profile.localId, current, derivation.lastSent)
            val name = dashboard.chargerName?.trim()?.takeIf { it.isNotEmpty() }
                ?: profile.label(AppLanguageSettings.text(app, R.string.section_charger))
            IdentifyNotifications.follow(app, store, profile.localId, name, dashboard, NotificationEvent.VEHICLE_IDENTIFY in chosen)
            if (derivation.events.isEmpty() || !allowed(app)) continue
            derivation.events.forEach { post(app, profile.localId, name, it) }
        }
        if (identifyingChanged) PushNotifications.sync(app)
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                AppLanguageSettings.text(context, R.string.notify_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                AppLanguageSettings.text(context, R.string.notify_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun post(context: Context, localId: String, chargerName: String, event: LocalEvent) {
        ensureChannels(context)
        val open = openApp(context)
        val message = message(context, event)
        val notification = Notification.Builder(context, if (event.event in ALERTS) CHANNEL_ALERTS else CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_spotnav_monochrome)
            .setContentTitle(AppLanguageSettings.text(context, R.string.notify_title, chargerName))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One tag per charger and kind: a newer one of the same kind replaces the older.
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify("$localId:${event.event.wire}", event.event.ordinal, notification)
        } catch (denied: SecurityException) {
            Log.w(TAG, "Notifications are not allowed")
        }
    }

    /** The message of one event, in the app's language (the Home Assistant integration's own words). */
    fun message(context: Context, event: LocalEvent): String {
        fun t(id: Int, vararg args: Any) = AppLanguageSettings.text(context, id, *args)
        val locale = AppLanguageSettings.numberLocale(context)
        fun number(value: Double, digits: Int) = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = digits
        }.format(value)
        return when (event.event) {
            NotificationEvent.PLAN_STOPPED -> t(
                when (event.reason) {
                    "stopped" -> R.string.notify_stopped_stopped
                    "charger_unavailable" -> R.string.notify_stopped_unavailable
                    "vehicle_not_requesting" -> R.string.notify_stopped_vehicle
                    "held_by_charger" -> R.string.notify_stopped_held
                    "charger_disabled" -> R.string.notify_stopped_disabled
                    NotificationRules.IGNORES_STOP -> R.string.notify_stopped_ignores_stop
                    else -> R.string.notify_stopped_not_started
                }
            )
            NotificationEvent.PLAN_AT_RISK ->
                event.time?.let { t(R.string.notify_at_risk_time, it) } ?: t(R.string.notify_at_risk)
            NotificationEvent.CHARGE_COMPLETE -> when {
                event.reason == "target" && event.percent != null -> t(
                    R.string.notify_complete_target,
                    number(event.percent, 1) + if (AppLanguageSettings.language(context) == "en") "%" else " %"
                )
                event.reason == "energy" -> t(R.string.notify_complete_energy)
                else -> t(R.string.notify_complete_plan)
            }
            NotificationEvent.CHARGE_STARTED ->
                event.time?.let { t(R.string.notify_started_until, it) } ?: t(R.string.notify_started)
            NotificationEvent.PLUGGED_IN -> t(R.string.notify_plugged_in)
            NotificationEvent.UNPLUGGED -> t(R.string.notify_unplugged)
            NotificationEvent.PLAN_INSTALLED -> {
                val start = t(R.string.notify_plan_installed, event.time.orEmpty())
                val kwh = event.kwh?.takeIf { it > 0 } ?: return start
                "$start ${t(R.string.notify_planned_kwh, number(kwh, 1))}"
            }
            // Posted on its own, with car buttons (IdentifyNotifications); worded all the same.
            NotificationEvent.VEHICLE_IDENTIFY -> t(R.string.identify_question)
        }
    }
}

/** The scheduled check: run off the main thread, finished when every charger has been read. */
class LocalNotificationJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        worker.execute {
            try {
                LocalNotifications.check(applicationContext)
            } catch (failure: Exception) {
                Log.w("SpotNavNotify", "The notification check failed: ${failure.javaClass.simpleName}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** Stopped early (the network went): the next period checks again. */
    override fun onStopJob(params: JobParameters): Boolean = false

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
