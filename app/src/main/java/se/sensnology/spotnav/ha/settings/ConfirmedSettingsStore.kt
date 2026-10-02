package se.sensnology.spotnav.ha.settings

import android.content.Context
import android.util.Log
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.dashboard.Dashboard

/** The last **server-confirmed** canonical settings record per [ChargerProfile.localId]. */
internal class ConfirmedSettingsStore(
    private val store: KeyValueStore,
    private val logWarning: (String) -> Unit = { message -> Log.w(TAG, message) }
) {
    /**
     * What one observation did to the cache — the revision rule as a value, rather than a silent
     * write.
     */
    sealed interface Merge {
        data class Stored(val settings: HaPlanningSettings) : Merge

        data object Unchanged : Merge

        data class Stale(val kept: HaPlanningSettings) : Merge

        data class EqualRevisionMismatch(val kept: HaPlanningSettings) : Merge
    }

    /** The confirmed record for one profile, or `null` when there is nothing readable. */
    fun confirmed(localId: String): HaPlanningSettings? = read(localId)

    /** Folds one dashboard in: its settings record when it stated one, and nothing at all otherwise. */
    fun observeDashboard(localId: String, dashboard: Dashboard): Merge? =
        dashboard.settings?.let { record(localId, it) }

    /**
     * Folds one write answer in: only the outcomes that carry a confirmed record, which is exactly
     * the set the user's edit actually happened against or produced.
     */
    fun recordOutcome(localId: String, outcome: SettingsUpdate.Outcome): Merge? =
        outcome.record?.let { confirmedRecord -> record(localId, confirmedRecord) }

    /** Merges one server-confirmed record, under the revision rule. */
    fun record(localId: String, settings: HaPlanningSettings): Merge = synchronized(lock) {
        val existing = read(localId)
        when {
            existing == null -> store(localId, settings)
            settings.revision > existing.revision -> store(localId, settings)
            settings.revision < existing.revision -> Merge.Stale(existing)
            canonical(settings) == canonical(existing) -> Merge.Unchanged
            else -> {
                logWarning("Confirmed settings for one profile disagree at revision ${settings.revision}")
                Merge.EqualRevisionMismatch(existing)
            }
        }
    }

    /** Forgets one profile's cache and nothing else — a deletion is not a rename. */
    fun removeProfile(localId: String) {
        synchronized(lock) { store.remove(key(localId)) }
    }

    private fun store(localId: String, settings: HaPlanningSettings): Merge {
        store.putString(key(localId), document(settings).toString())
        return Merge.Stored(settings)
    }

    /**
     * One profile's document, or `null` when it is absent, unreadable, or not exactly this schema's
     * shape.
     */
    private fun read(localId: String): HaPlanningSettings? {
        val raw = store.getString(key(localId)) ?: return null
        return try {
            val document = JSONObject(raw)
            val schema = document.opt("schema") as? Number
            val settings = document.opt("settings") as? JSONObject
            if (document.keys().asSequence().toSet() != DOCUMENT_KEYS ||
                schema?.toDouble() != SCHEMA_VERSION.toDouble() || settings == null
            ) {
                logWarning("Confirmed settings for one profile are not a readable document")
                null
            } else {
                HaSettingsCodec.parseStored(settings)
            }
        } catch (failure: Exception) {
            logWarning("Confirmed settings for one profile could not be read: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun document(settings: HaPlanningSettings): JSONObject = JSONObject().apply {
        put("schema", SCHEMA_VERSION)
        put("settings", HaSettingsCodec.encode(settings))
    }

    private fun canonical(settings: HaPlanningSettings): String = HaSettingsCodec.encode(settings).toString()

    private fun key(localId: String): String = "$KEY_PREFIX$localId"

    companion object {
        private const val TAG = "SpotNavSettings"

        /** The preference file this cache lives in. */
        internal const val PREFS = "spotnav_confirmed_settings"
        private const val SCHEMA_VERSION = 1
        private const val KEY_PREFIX = "confirmed."

        /** The whole document: the schema stamp and the canonical record. */
        private val DOCUMENT_KEYS = setOf("schema", "settings")

        /** Shared by every instance, so two writers can never interleave a merge. */
        private val lock = Any()

        fun forContext(context: Context): ConfirmedSettingsStore =
            ConfirmedSettingsStore(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
