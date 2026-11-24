package se.sensnology.spotnav.push

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore

/**
 * Instant notifications on this phone: whether they are on, the relay's opaque push reference, and
 * per charger profile what Home Assistant was last told. Off by default.
 */
internal class PushStore(private val store: KeyValueStore) {
    var enabled: Boolean
        get() = store.getString(ENABLED) == "true"
        set(value) = store.putString(ENABLED, value.toString())

    var pushRef: String?
        get() = store.getString(REF)
        set(value) = if (value == null) store.remove(REF) else store.putString(REF, value)

    /** What one profile's Home Assistant holds: the reference and events it was given, or `null` (none). */
    fun sent(localId: String): PushRegistration.Sent? {
        val raw = store.getString(SENT + localId) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val events = json.getJSONArray("events")
            PushRegistration.Sent(json.getString("ref"), (0 until events.length()).map { events.getString(it) })
        }.getOrNull()
    }

    fun remember(localId: String, sent: PushRegistration.Sent?) {
        if (sent == null) {
            store.remove(SENT + localId)
        } else {
            store.putString(SENT + localId, JSONObject().put("ref", sent.ref).put("events", JSONArray(sent.events)).toString())
        }
    }

    /**
     * What one Home Assistant that answers `phone_notifications` was last told of the wake-up reference ([key]: its
     * address), or `null` when it was never told.
     */
    fun doorSent(key: String): PushRegistration.DoorSent? {
        val raw = store.getString(DOOR + key) ?: return null
        return runCatching { PushRegistration.DoorSent(JSONObject(raw).opt("ref") as? String) }.getOrNull()
    }

    fun rememberDoor(key: String, sent: PushRegistration.DoorSent?) {
        if (sent == null) store.remove(DOOR + key) else store.putString(DOOR + key, JSONObject().put("ref", sent.ref ?: JSONObject.NULL).toString())
    }

    /** The Home Assistants told something through `phone_notifications`. */
    fun doorsTold(): Set<String> = store.keys().filter { it.startsWith(DOOR) }.map { it.removePrefix(DOOR) }.toSet()

    /** The profiles that were told something, so a profile unpaired since is forgotten too. */
    var known: Set<String>
        get() = store.getString(KNOWN)?.let { raw ->
            runCatching { JSONArray(raw).let { list -> (0 until list.length()).map { list.getString(it) }.toSet() } }.getOrNull()
        } ?: emptySet()
        set(value) = store.putString(KNOWN, JSONArray(value.sorted()).toString())

    companion object {
        private const val PREFS = "spotnav_push"
        private const val ENABLED = "enabled"
        private const val REF = "push_ref"
        private const val SENT = "sent."
        private const val KNOWN = "known"
        private const val DOOR = "door."

        fun forContext(context: Context): PushStore = PushStore(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))
    }
}
