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
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.heat.HeatLoadPress
import se.sensnology.spotnav.ha.heat.HeatLoads
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * When the phone's own pool season notice is posted, kept or taken off: posted once while the heat page hints at
 * closing the season (`season_hint`, the season open), never between 22:00 and 07:00 (told at the first check after),
 * and taken off once the hint is gone or the phone no longer wants it. Pure.
 */
internal object PoolSeasonNotice {
    enum class Step { POST, KEEP, RETIRE, WAIT }

    fun step(pool: HeatLoads.Pool?, chosen: Boolean, posted: Boolean, now: Instant, zone: ZoneId): Step {
        val asks = chosen && pool != null && pool.seasonOpen && pool.seasonHint
        if (!asks) return if (posted) Step.RETIRE else Step.KEEP
        if (posted) return Step.KEEP
        val clock = now.atZone(zone).toLocalTime()
        if (clock >= QUIET_FROM || clock < QUIET_UNTIL) return Step.WAIT
        return Step.POST
    }

    private val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    private val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)
}

/**
 * The pool's season as this phone's own notification (`pool_season`, Home Assistant's choice for this phone): "The water
 * has been around 10 °C for three days: time to close the pool season?" with "Close the season", which closes it from
 * the notification (webhook `pool`, `pool_action: "season"`, `open: false`) as the heat page's switch does, with the
 * app closed as well. One per Home Assistant.
 */
internal object PoolSeasonNotifications {
    private const val TAG = "SpotNavNotify"
    private const val ACTION_CLOSE = "se.sensnology.spotnav.action.POOL_SEASON_CLOSE"
    private const val EXTRA_DOOR = "door"
    private const val ID = 13_070

    private fun tag(door: String) = "pool_season:$door"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** One check's read of [door]'s pool ([pool], `null` without one or unread when not [chosen]): post, keep or take off. */
    fun follow(context: Context, store: LocalNotificationStore, door: PhoneDoor, pool: HeatLoads.Pool?, chosen: Boolean, zone: ZoneId, now: Instant = Instant.now()) {
        val posted = store.poolSeasonPosted(door.key)
        when (PoolSeasonNotice.step(pool, chosen, posted, now, zone)) {
            PoolSeasonNotice.Step.POST -> {
                if (!LocalNotifications.allowed(context)) return
                if (post(context, door.key)) store.setPoolSeasonPosted(door.key, true)
            }
            PoolSeasonNotice.Step.RETIRE -> {
                manager(context).cancel(tag(door.key), ID)
                store.setPoolSeasonPosted(door.key, false)
            }
            PoolSeasonNotice.Step.KEEP, PoolSeasonNotice.Step.WAIT -> Unit
        }
    }

    private fun post(context: Context, door: String): Boolean {
        LocalNotifications.ensureChannels(context)
        fun t(id: Int) = AppLanguageSettings.text(context, id)
        val message = t(R.string.notify_pool_season_message)
        val close = PendingIntent.getBroadcast(
            context, ID,
            Intent(context, PoolSeasonReceiver::class.java)
                .setAction(ACTION_CLOSE)
                .setData(Uri.parse("spotnav-pool-season://${Uri.encode(door)}"))
                .putExtra(EXTRA_DOOR, door),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, LocalNotifications.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_spotnav_monochrome)
            .setContentTitle(t(R.string.notify_pool_season_title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(LocalNotifications.openApp(context))
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_spotnav_monochrome), t(R.string.notify_pool_season_close), close).build())
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        return try {
            manager(context).notify(tag(door), ID, notification)
            true
        } catch (denied: SecurityException) {
            Log.w(TAG, "Notifications are not allowed")
            false
        }
    }

    /** "Close the season" was tapped: the season closed through that Home Assistant's door. Blocks; never throws. */
    fun close(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_DOOR) ?: return
        manager(context).cancel(tag(key), ID)
        val door = PhoneNotificationsSync.doors(context).firstOrNull { it.key == key } ?: return
        val outcome = HomeAssistantClient.heatLoadPress(door.settings, HeatLoadPress.pool("season", mapOf("open" to false)))
        if (outcome != HeatLoadPress.Outcome.Done) Log.w(TAG, "The pool season could not be closed")
    }
}

/** The pool season notice's button: answered off the main thread, with the app closed as well. */
class PoolSeasonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                PoolSeasonNotifications.close(app, intent)
            } catch (failure: Exception) {
                Log.w("SpotNavNotify", "Closing the pool season failed: ${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}
