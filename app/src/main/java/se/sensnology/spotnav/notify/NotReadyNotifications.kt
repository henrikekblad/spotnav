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
import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.LauncherActivity
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.heat.HeatLoadPress
import se.sensnology.spotnav.ha.house.NotReady
import se.sensnology.spotnav.ha.house.Plan
import se.sensnology.spotnav.ui.common.CardStringCache
import se.sensnology.spotnav.ui.common.CardStringSync
import se.sensnology.spotnav.ui.common.CardText
import se.sensnology.spotnav.ui.common.cardResource
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.site.NotReadyText
import se.sensnology.spotnav.ui.site.SiteText
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * When the phone's own "Not ready in time" notice for a site is posted, kept or taken off (`docs/notifications.md`, *Not
 * ready in time across the site*): one per site and phone, posted once per set of deadlines (a new deadline joining
 * tells it again), never between 22:00 and 07:00 (one from the night is told at 07:00 if it still stands, at the first
 * check after), and taken off once nothing in it asks any more. Pure.
 */
internal object NotReadyNotice {
    sealed interface Step {
        /** Post (or replace) it under [key]. */
        data class Post(val key: String) : Step

        /** Shown as it is; nothing to do. */
        data object Keep : Step

        /** Nothing asks any more: take it off. */
        data object Retire : Step

        /** It asks, but not now (the night): neither posted nor remembered. */
        data object Wait : Step
    }

    /** The deadlines that still ask, as a key: the same set of loads and times is told once. */
    fun key(block: NotReady): String =
        block.items.filter { it.chosen == null }.map { "${it.loadId}@${it.deadline}" }.sorted().joinToString(",")

    /**
     * [block] without the cars known to be away ([away]: their charger entry ids) and their choices: the plan is made
     * for a car's next plug-in, so its departure is not told while it is away (Home Assistant's `fit_may_tell`). The
     * spa's baths and the sauna's sessions stay. `null` when nothing is left.
     */
    fun withoutAway(block: NotReady?, away: Set<String>): NotReady? {
        if (block == null || away.isEmpty()) return block
        val gone = block.items.filter { it.kind == "car" && it.loadId in away }.map { it.loadId }.toSet()
        if (gone.isEmpty()) return block
        val items = block.items.filter { it.loadId !in gone }
        if (items.isEmpty()) return null
        return NotReady(items, block.ranked.filter { it.loadId == null || it.loadId !in gone })
    }

    fun step(block: NotReady?, posted: String?, now: Instant, zone: ZoneId): Step {
        if (block == null || !block.asks) return if (posted == null) Step.Keep else Step.Retire
        val key = key(block)
        if (key == posted) return Step.Keep
        val clock = now.atZone(zone).toLocalTime()
        if (clock >= QUIET_FROM || clock < QUIET_UNTIL) return Step.Wait
        return Step.Post(key)
    }

    private val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    private val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)
}

/**
 * "Not ready in time" as this phone's own notification, where the app already tells a charger's events without the
 * Companion app ([LocalNotifications]): the site's plan (`get_plan`'s `not_ready`) read with the paired chargers, one
 * notice per site with each load's line and the three choices that free the most as buttons ([NotReadyText]), each sent
 * from [NotReadyAnswerReceiver] through the webhooks (a car's choice through its own charger's, a bath's or a session's
 * as `spa` / `sauna`), so it works with the app closed. A tap opens the app at the house page, where the plan box has
 * the whole sheet.
 */
internal object NotReadyNotifications {
    private const val TAG = "SpotNavNotify"
    private const val ACTION_ANSWER = "se.sensnology.spotnav.action.NOT_READY_ANSWER"
    private const val EXTRA_PROFILE = "profile"
    private const val EXTRA_SITE = "site"
    private const val EXTRA_CODE = "code"
    private const val EXTRA_LOADS = "loads"
    private const val ID = 13_060

    /** The most buttons a notice carries (Android shows three). */
    const val BUTTONS = 3

    /** The launch extra that opens the house page of this profile ([LauncherActivity]). */
    const val EXTRA_OPEN_HOUSE = "se.sensnology.spotnav.extra.OPEN_HOUSE"

    /** Set when the app was opened from the notice: the plan box brings its sheet into view once. */
    @Volatile var openSheet: Boolean = false

    private fun tag(site: String) = "not_ready:$site"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /**
     * The card's words in the app's language, outside a screen: those fetched from [baseUrl]'s Home Assistant when the
     * app holds them, else its own.
     */
    fun words(context: Context, baseUrl: String? = null): CardWords {
        val fetched = CardStringSync.words(context, baseUrl?.takeIf { it.isNotBlank() }?.let(CardStringCache::instance))
        return CardWords { key, params -> CardText.text(key, params, fetched, { AppLanguageSettings.text(context, it) }, { name -> cardResource(context, name) }) }
    }

    private fun text(context: Context, baseUrl: String?) =
        SiteText(words(context, baseUrl), AppLanguageSettings.locale(context), AppLanguageSettings.numberLocale(context))

    /**
     * One check's read of a site's plan, for the paired charger [profile] it was read through: post, keep or take off
     * its notice. The cars in [away] (charger entry ids) are left out of it. Not chosen ([chosen] false, the phone's "Not ready in time" off): taken off.
     */
    fun follow(
        context: Context,
        store: LocalNotificationStore,
        profile: ChargerProfile,
        plan: Plan,
        chosen: Boolean,
        now: Instant = Instant.now(),
        away: Set<String> = emptySet()
    ) {
        val site = plan.siteId
        val posted = store.notReadyPosted(site)
        val block = NotReadyNotice.withoutAway(plan.notReady, away)
        val step = if (chosen) NotReadyNotice.step(block, posted, now, plan.zone) else NotReadyNotice.Step.Retire
        when (step) {
            is NotReadyNotice.Step.Post -> {
                if (!LocalNotifications.allowed(context)) return
                if (post(context, profile, plan, block!!)) store.setNotReadyPosted(site, step.key)
            }
            NotReadyNotice.Step.Retire -> {
                manager(context).cancel(tag(site), ID)
                store.setNotReadyPosted(site, null)
            }
            NotReadyNotice.Step.Keep, NotReadyNotice.Step.Wait -> Unit
        }
    }

    private fun post(context: Context, profile: ChargerProfile, plan: Plan, block: NotReady): Boolean {
        LocalNotifications.ensureChannels(context)
        // The check's own worker thread: the kept card words may be read here.
        CardStringSync.preloadNow(context, profile.baseUrl)
        val said = NotReadyText(text(context, profile.baseUrl), plan.zone, plan.money)
        val message = block.items.joinToString("\n") { said.line(it) }
        val builder = Notification.Builder(context, LocalNotifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_spotnav_monochrome)
            .setContentTitle(said.title())
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openHouse(context, profile.localId))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        val icon = Icon.createWithResource(context, R.drawable.ic_spotnav_monochrome)
        said.buttons(block, limit = BUTTONS).forEachIndexed { index, button ->
            builder.addAction(Notification.Action.Builder(icon, button.label, answerIntent(context, profile.localId, plan.siteId, button.press, index)).build())
        }
        return try {
            manager(context).notify(tag(plan.siteId), ID, builder.build())
            true
        } catch (denied: SecurityException) {
            Log.w(TAG, "Notifications are not allowed")
            false
        }
    }

    /** A tap: the app at the house page of [localId], its plan box's sheet in view. */
    private fun openHouse(context: Context, localId: String): PendingIntent = PendingIntent.getActivity(
        context, ID,
        Intent(context, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setData(Uri.parse("spotnav-not-ready://$localId"))
            .putExtra(EXTRA_OPEN_HOUSE, localId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun answerIntent(context: Context, localId: String, site: String, press: NotReadyText.Press, index: Int): PendingIntent {
        val loads = JSONArray().apply { press.loads.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind)) } }
        val intent = Intent(context, NotReadyAnswerReceiver::class.java)
            .setAction(ACTION_ANSWER)
            // A distinct address per button, so no two buttons share one pending intent.
            .setData(Uri.parse("spotnav-not-ready://$localId/$index"))
            .putExtra(EXTRA_PROFILE, localId)
            .putExtra(EXTRA_SITE, site)
            .putExtra(EXTRA_CODE, press.code)
            .putExtra(EXTRA_LOADS, loads.toString())
        return PendingIntent.getBroadcast(context, index, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * A button was tapped: its choice sent for each of its loads, a car's through that charger's own paired webhook
     * (one this phone has not paired is skipped), a bath's or a session's through the profile's. The notice goes; the
     * next check posts it again only for a new deadline. Blocks; never on the main thread. Never throws.
     */
    fun answer(context: Context, intent: Intent) {
        val localId = intent.getStringExtra(EXTRA_PROFILE) ?: return
        val site = intent.getStringExtra(EXTRA_SITE) ?: return
        val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { it in NotReady.CODES } ?: return
        val loads = runCatching {
            val array = JSONArray(intent.getStringExtra(EXTRA_LOADS).orEmpty())
            (0 until array.length()).map { array.getJSONObject(it).let { load -> NotReadyText.Load(load.getString("id"), load.getString("kind")) } }
        }.getOrNull() ?: return
        manager(context).cancel(tag(site), ID)
        val profiles = ChargerProfileStore.forContext(context).listProfiles().filter { it.configured }
        val profile = profiles.firstOrNull { it.localId == localId } ?: return
        for (load in loads) {
            val action = NotReadyText.action(code, load)
            if (load.kind == "car") {
                val charger = profiles.firstOrNull { it.baseUrl == profile.baseUrl && it.remoteChargerId == load.id } ?: continue
                val settings = charger.toHomeAssistantSettings()
                if (action == "release_import_target") HomeAssistantClient.releaseImportTarget(settings)
                else HomeAssistantClient.doesNotFitChoice(settings, action)
            } else {
                HomeAssistantClient.heatLoadPress(profile.toHomeAssistantSettings(), HeatLoadPress.deadline(load.kind, action))
            }
        }
    }
}

/** A button of the "not ready in time" notice: answered off the main thread, with the app closed as well. */
class NotReadyAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                NotReadyNotifications.answer(app, intent)
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
