package se.sensnology.spotnav.notify

import android.content.Context
import android.os.Build
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.client.PhoneNotifications
import se.sensnology.spotnav.ha.client.PhoneNotificationsCall
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.sites.SiteProfile
import se.sensnology.spotnav.sites.SiteProfileStore

/**
 * One Home Assistant this phone is paired with, as `phone_notifications` is asked there: the first paired home's
 * webhook (a charger's for a pairing before 3.0), and the paired chargers of that instance. The phones are the
 * installation's, so one entry per Home Assistant: [key] is its address.
 */
internal data class PhoneDoor(val key: String, val settings: HomeAssistantSettings, val localIds: List<String>) {
    companion object {
        fun key(baseUrl: String): String = baseUrl.trim().trimEnd('/')

        /** Every Home Assistant of the paired [sites] and [profiles], in the order they were paired. */
        fun of(sites: List<SiteProfile>, profiles: List<ChargerProfile>): List<PhoneDoor> {
            val chargers = profiles.filter { it.configured }
            val homes = sites.filter { it.configured }
            val keys = (chargers.map { key(it.baseUrl) } + homes.map { key(it.baseUrl) }).distinct()
            return keys.mapNotNull { door ->
                val own = chargers.filter { key(it.baseUrl) == door }
                val settings = homes.firstOrNull { key(it.baseUrl) == door }?.toHomeAssistantSettings()
                    ?: own.firstOrNull()?.toHomeAssistantSettings()
                    ?: return@mapNotNull null
                PhoneDoor(door, settings, own.map { it.localId })
            }
        }
    }

    /** Never the webhook id, which is a secret. */
    override fun toString(): String = "PhoneDoor(key=$key, localIds=$localIds)"
}

/**
 * What each Home Assistant said of this phone's notifications, kept between two reads: whether it answers
 * `phone_notifications` at all ([Mode.CURRENT]) or is older ([Mode.LEGACY], asked again after a while, since Home
 * Assistant may be updated), its last answer, and what was handed over (`shows`, the first contact's carried choice).
 */
internal class PhoneNotificationsStore(private val store: KeyValueStore) {
    enum class Mode { CURRENT, LEGACY }

    fun mode(key: String): Mode? = store.getString(MODE + key)?.let { stored -> Mode.entries.firstOrNull { it.name == stored } }

    /** When an older Home Assistant last answered "Unsupported action" (ms). */
    fun legacyAt(key: String): Long = store.getString(LEGACY_AT + key)?.toLongOrNull() ?: 0L

    /** The last answer of a Home Assistant that answers the action; `null` for an older one or none yet. */
    fun current(key: String): PhoneNotifications? {
        if (mode(key) != Mode.CURRENT) return null
        val raw = store.getString(ANSWER + key) ?: return null
        return runCatching { PhoneNotificationsCall.decode(JSONObject(raw)) }.getOrNull()
    }

    fun setCurrent(key: String, value: PhoneNotifications) {
        store.putString(MODE + key, Mode.CURRENT.name)
        store.putString(ANSWER + key, PhoneNotificationsCall.encode(value).toString())
        store.remove(LEGACY_AT + key)
    }

    fun setLegacy(key: String, at: Long) {
        store.putString(MODE + key, Mode.LEGACY.name)
        store.putString(LEGACY_AT + key, at.toString())
        store.remove(ANSWER + key)
        store.remove(SHOWS + key)
    }

    fun sentShows(key: String): String? = store.getString(SHOWS + key)

    fun setSentShows(key: String, shows: String) = store.putString(SHOWS + key, shows)

    /** Whether the first contact's carrying over of this phone's own choice is done. */
    fun carried(key: String): Boolean = store.getString(CARRIED + key) == "true"

    fun setCarried(key: String) = store.putString(CARRIED + key, "true")

    /** Forget the Home Assistants no longer paired (all but [keep]). */
    fun prune(keep: Set<String>) {
        store.keys().forEach { stored ->
            val prefix = PREFIXES.firstOrNull { stored.startsWith(it) } ?: return@forEach
            if (stored.removePrefix(prefix) !in keep) store.remove(stored)
        }
    }

    companion object {
        private const val PREFS = "spotnav_phone_notifications"
        private const val MODE = "mode."
        private const val LEGACY_AT = "legacy_at."
        private const val ANSWER = "answer."
        private const val SHOWS = "shows."
        private const val CARRIED = "carried."
        private val PREFIXES = listOf(MODE, LEGACY_AT, ANSWER, SHOWS, CARRIED)

        fun forContext(context: Context): PhoneNotificationsStore =
            PhoneNotificationsStore(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))
    }
}

/**
 * Asking one Home Assistant for this phone's notifications (`phone_notifications`), and keeping the answer: the one
 * place the app's secret id, `shows` and the first contact are handled. Blocks: never on the main thread.
 *
 * **An older Home Assistant** (2.x, 3.0.0b4 and earlier) answers "Unsupported action": it is marked [Mode.LEGACY] and
 * the app keeps its own choice and `push_register` there, as before; it is asked again after [RECHECK_MS].
 *
 * **The first contact** with a Home Assistant that answers: Home Assistant makes this phone's entry (on, its defaults,
 * or the entry of an older app it adopts by the wake-up reference). When that entry has no Home Assistant app beside it,
 * the app's own choice until then (on or off, its events) is written once, so nobody gets more or less than they chose.
 * An entry joined to a Home Assistant app keeps what Home Assistant holds.
 */
internal class PhoneNotificationsSync(
    private val store: PhoneNotificationsStore,
    private val appId: () -> String,
    private val deviceName: String,
    private val call: (HomeAssistantSettings, JSONObject) -> PhoneNotificationsCall.Outcome,
    /** This phone's own choice from before (on, events); `null` when a person never made one (nothing is carried). */
    private val ownChoice: () -> Pair<Boolean, Set<NotificationEvent>>?,
    private val now: () -> Long = System::currentTimeMillis
) {
    sealed interface Result {
        data class Current(val value: PhoneNotifications) : Result

        /** An older Home Assistant: the app's own choice and `push_register` hold there. */
        data object Legacy : Result

        /** Refused as a body that cannot be stored: nothing was written. */
        data object Refused : Result

        /** No answer, or one that could not be read: nothing is known anew. */
        data class Failed(val code: String?) : Result
    }

    /** Whether to ask [door] now: always, except an older Home Assistant asked within [RECHECK_MS]. */
    fun due(door: PhoneDoor): Boolean =
        store.mode(door.key) != PhoneNotificationsStore.Mode.LEGACY || now() - store.legacyAt(door.key) >= RECHECK_MS

    fun read(door: PhoneDoor, ref: PhoneNotificationsCall.Ref = PhoneNotificationsCall.Ref.Keep, change: PhoneNotificationsCall.Change? = null): Result {
        val first = store.current(door.key) == null
        val shows = SHOWS.joinToString(",")
        val sendShows = store.sentShows(door.key) != shows
        val body = PhoneNotificationsCall.payload(
            appId(),
            name = deviceName.trim().takeIf { first && it.isNotEmpty() },
            ref = ref,
            shows = SHOWS.takeIf { sendShows },
            change = change
        )
        return when (val outcome = call(door.settings, body)) {
            is PhoneNotificationsCall.Outcome.Read -> {
                if (sendShows) store.setSentShows(door.key, shows)
                var value = outcome.value
                store.setCurrent(door.key, value)
                if (!store.carried(door.key)) {
                    store.setCarried(door.key)
                    val carry = if (change == null && value.companionName == null) ownChoice()?.let { carried(value, it.first, it.second) } else null
                    if (carry != null) {
                        val again = call(door.settings, PhoneNotificationsCall.payload(appId(), change = carry))
                        if (again is PhoneNotificationsCall.Outcome.Read) value = again.value.also { store.setCurrent(door.key, it) }
                    }
                }
                Result.Current(value)
            }
            PhoneNotificationsCall.Outcome.NotSupported -> {
                store.setLegacy(door.key, now())
                Result.Legacy
            }
            PhoneNotificationsCall.Outcome.Refused -> Result.Refused
            is PhoneNotificationsCall.Outcome.Failed -> Result.Failed(outcome.code)
        }
    }

    companion object {
        /** How long an older Home Assistant's "Unsupported action" stands before it is asked again. */
        const val RECHECK_MS = 6 * 60 * 60 * 1000L

        /**
         * What this app shows itself: the charger's eight events, and a spa's or a sauna's "not ready in time" (the
         * site's notice, [NotReadyNotifications]).
         */
        val SHOWS: List<String> = NotificationEvent.entries.map { it.wire } + PhoneNotificationsCall.HEAT_AT_RISK

        /**
         * The write that carries this phone's own choice ([on], [events]) into [value]: its charger events replaced,
         * the rest (the pool's) kept; `null` when nothing differs.
         */
        fun carried(value: PhoneNotifications, on: Boolean, events: Set<NotificationEvent>): PhoneNotificationsCall.Change? {
            val chargerEvents = NotificationEvent.entries.map { it.wire }.toSet()
            val wanted = value.allEvents.filter { event ->
                if (event in chargerEvents) NotificationEvent.of(event) in events else event in value.events
            } + value.events.filter { it !in value.allEvents }
            val change = PhoneNotificationsCall.Change(
                on = on.takeIf { it != value.on },
                events = wanted.takeIf { it.toSet() != value.events.toSet() }
            )
            return change.takeUnless { it.empty }
        }

        fun forContext(context: Context): PhoneNotificationsSync {
            val app = context.applicationContext
            val local = LocalNotificationStore.forContext(app)
            return PhoneNotificationsSync(
                store = PhoneNotificationsStore.forContext(app),
                appId = { AppIdentity.forContext(app).appId() },
                deviceName = Build.MODEL.orEmpty(),
                call = HomeAssistantClient::phoneNotifications,
                ownChoice = { if (local.chosen) local.enabled to local.events else null }
            )
        }

        /** Every Home Assistant this phone is paired with now. */
        fun doors(context: Context): List<PhoneDoor> {
            val app = context.applicationContext
            return PhoneDoor.of(SiteProfileStore.forContext(app).list(), ChargerProfileStore.forContext(app).listProfiles())
        }
    }
}

/**
 * What this phone's own check tells for one paired charger: with a Home Assistant that answers `phone_notifications`,
 * its answer ([PhoneNotifications.on], the events, the chargers it follows); with an older one (or none known yet),
 * the app's own choice as before.
 */
internal data class PhoneChoice(val enabled: Boolean, val events: Set<NotificationEvent>) {
    companion object {
        fun of(answer: PhoneNotifications?, chargerId: String?, ownEnabled: Boolean, ownEvents: Set<NotificationEvent>): PhoneChoice {
            if (answer == null) return PhoneChoice(ownEnabled, if (ownEnabled) ownEvents else emptySet())
            val enabled = answer.on && answer.follows(chargerId)
            return PhoneChoice(enabled, if (enabled) answer.events.mapNotNull { NotificationEvent.of(it) }.toSet() else emptySet())
        }

        /** Whether the site's "not ready in time" is told: Home Assistant's choice of the event, else this phone's own. */
        fun siteAtRisk(answer: PhoneNotifications?, ownEnabled: Boolean, ownEvents: Set<NotificationEvent>): Boolean =
            answer?.wants(NotificationEvent.PLAN_AT_RISK.wire) ?: (ownEnabled && NotificationEvent.PLAN_AT_RISK in ownEvents)

        /** Whether anything is to be checked for a Home Assistant: its answer's switch, else this phone's own. */
        fun wanted(answer: PhoneNotifications?, ownEnabled: Boolean): Boolean = answer?.on ?: ownEnabled
    }
}
