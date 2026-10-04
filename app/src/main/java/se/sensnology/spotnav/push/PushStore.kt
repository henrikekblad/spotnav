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

        fun forContext(context: Context): PushStore = PushStore(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))
    }
}
