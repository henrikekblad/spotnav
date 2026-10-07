package se.sensnology.spotnav.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.ha.settings.NotificationEvent
import java.time.Instant

/**
 * This phone's own notifications (without the Companion app): whether they are on, which events, and
 * per charger profile the last snapshot and when each kind was last posted. Off by default.
 */
internal class LocalNotificationStore(private val store: KeyValueStore) {
    var enabled: Boolean
        get() = store.getString(ENABLED) == "true"
        set(value) = store.putString(ENABLED, value.toString())

    /**
     * The chosen events; the defaults (stopped, at risk, complete, which car) until a person changes
     * them. A choice stored before the question which car is plugged in existed has it on, as Home
     * Assistant does with its own.
     */
    var events: Set<NotificationEvent>
        get() {
            val raw = store.getString(EVENTS) ?: return NotificationEvent.LOCAL_DEFAULTS.toSet()
            val chosen = runCatching {
                val list = JSONArray(raw)
                (0 until list.length()).mapNotNull { NotificationEvent.of(list.opt(it)) }.filter { it in NotificationEvent.LOCAL }.toSet()
            }.getOrDefault(NotificationEvent.LOCAL_DEFAULTS.toSet())
            return if (store.getString(EVENT_SET) == CURRENT_EVENT_SET) chosen else chosen + NotificationEvent.VEHICLE_IDENTIFY
        }
        set(value) {
            store.putString(EVENTS, JSONArray(NotificationEvent.LOCAL.filter { it in value }.map { it.wire }).toString())
            store.putString(EVENT_SET, CURRENT_EVENT_SET)
        }

    /** Whether one profile's Home Assistant was last read identifying cars (its question can be posted). */
    fun identifies(localId: String): Boolean = store.getString(IDENTIFIES + localId) == "true"

    /** Remember [identifies] for [localId]; whether it changed. */
    fun setIdentifies(localId: String, identifies: Boolean): Boolean {
        if (identifies(localId) == identifies) return false
        if (identifies) store.putString(IDENTIFIES + localId, "true") else store.remove(IDENTIFIES + localId)
        return true
    }

    /** The profiles of [localIds] whose Home Assistant identifies cars. */
    fun identifying(localIds: List<String>): Set<String> = localIds.filter { identifies(it) }.toSet()

    /** The question (its key, see [IdentifyNotice]) this phone shows for [localId], or `null` with none. */
    fun identifyPosted(localId: String): String? = store.getString(POSTED + localId)

    fun setIdentifyPosted(localId: String, key: String?) =
        if (key == null) store.remove(POSTED + localId) else store.putString(POSTED + localId, key)

    fun snapshot(localId: String): NotificationSnapshot? =
        store.getString(SNAPSHOT + localId)?.let { raw -> runCatching { NotificationSnapshot.fromJson(JSONObject(raw)) }.getOrNull() }

    fun lastSent(localId: String): Map<NotificationEvent, Instant> {
        val raw = store.getString(SENT + localId) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                val event = NotificationEvent.of(key) ?: return@mapNotNull null
                val at = (json.opt(key) as? Number)?.toLong() ?: return@mapNotNull null
                event to Instant.ofEpochMilli(at)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun remember(localId: String, snapshot: NotificationSnapshot, lastSent: Map<NotificationEvent, Instant>) {
        store.putString(SNAPSHOT + localId, snapshot.toJson().toString())
        store.putString(SENT + localId, JSONObject().apply {
            lastSent.forEach { (event, at) -> put(event.wire, at.toEpochMilli()) }
        }.toString())
    }

    /** Forget one profile's snapshot: a profile paired again starts from a fresh baseline. */
    fun forget(localId: String) {
        store.remove(SNAPSHOT + localId)
        store.remove(SENT + localId)
        store.remove(IDENTIFIES + localId)
        store.remove(POSTED + localId)
    }

    companion object {
        private const val PREFS = "spotnav_local_notifications"
        private const val ENABLED = "enabled"
        private const val EVENTS = "events"
        private const val SNAPSHOT = "snapshot."
        private const val SENT = "sent."
        private const val IDENTIFIES = "identifies."
        private const val POSTED = "identify_posted."
        private const val EVENT_SET = "event_set"
        /** The events a stored choice was made among: 2 has the question which car is plugged in. */
        private const val CURRENT_EVENT_SET = "2"

        fun forContext(context: Context): LocalNotificationStore =
            LocalNotificationStore(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))
    }
}
