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

    /** The chosen events; the defaults (stopped, at risk, complete) until a person changes them. */
    var events: Set<NotificationEvent>
        get() {
            val raw = store.getString(EVENTS) ?: return NotificationEvent.DEFAULTS.toSet()
            return runCatching {
                val list = JSONArray(raw)
                (0 until list.length()).mapNotNull { NotificationEvent.of(list.opt(it)) }.toSet()
            }.getOrDefault(NotificationEvent.DEFAULTS.toSet())
        }
        set(value) = store.putString(EVENTS, JSONArray(NotificationEvent.entries.filter { it in value }.map { it.wire }).toString())

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
    }

    companion object {
        private const val PREFS = "spotnav_local_notifications"
        private const val ENABLED = "enabled"
        private const val EVENTS = "events"
        private const val SNAPSHOT = "snapshot."
        private const val SENT = "sent."

        fun forContext(context: Context): LocalNotificationStore =
            LocalNotificationStore(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))
    }
}
