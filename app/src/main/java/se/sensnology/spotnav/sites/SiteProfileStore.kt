package se.sensnology.spotnav.sites

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.app.optStringOrNull
import se.sensnology.spotnav.chargers.PairAgain
import se.sensnology.spotnav.chargers.PairAgainMark
import se.sensnology.spotnav.ha.pairing.SiteCharger
import java.util.UUID

/**
 * The homes this phone holds (see [SiteProfile]), apart from the charger profiles, so that nothing that lists
 * chargers (widgets, notifications, push) ever sees a home. Also which home the main screen shows when no charger is
 * shown ([activeSiteId]).
 */
internal class SiteProfileStore(
    private val store: KeyValueStore,
    private val logWarning: (String) -> Unit = { message -> Log.w(TAG, message) }
) {
    fun list(): List<SiteProfile> = synchronized(lock) { readState().sites }

    fun get(localId: String): SiteProfile? = list().find { it.localId == localId }

    /** The home [siteId] of the instance at [baseUrl]. */
    fun find(baseUrl: String, siteId: String): SiteProfile? = list().find { it.baseUrl == baseUrl && it.siteId == siteId }

    fun upsert(site: SiteProfile) = mutate { state ->
        val index = state.sites.indexOfFirst { it.localId == site.localId }
        val sites = if (index >= 0) state.sites.toMutableList().also { it[index] = site } else state.sites + site
        state.copy(sites = sites) to Unit
    }

    /** Removes a home; when it was the one shown, none is any more. */
    fun remove(localId: String) = mutate { state ->
        state.copy(
            sites = state.sites.filterNot { it.localId == localId },
            activeSiteId = state.activeSiteId.takeUnless { it == localId }
        ) to Unit
    }

    fun update(localId: String, transform: (SiteProfile) -> SiteProfile): SiteProfile? = mutate { state ->
        val existing = state.sites.find { it.localId == localId } ?: return@mutate state to null
        val updated = transform(existing)
        state.copy(sites = state.sites.map { if (it.localId == localId) updated else it }) to updated
    }

    /** The home the main screen shows when it shows no charger, by local id; `null` for none chosen. */
    fun activeSiteId(): String? = synchronized(lock) { readState().activeSiteId }

    fun setActiveSiteId(localId: String?) = mutate { state ->
        require(localId == null || state.sites.any { it.localId == localId }) { "Unknown site profile id" }
        state.copy(activeSiteId = localId) to Unit
    }

    /** Whether [localId]'s webhook is (still, or no longer) recognised, after one request that failed with [failure]. */
    fun recordWebhookAnswer(localId: String, failure: Throwable?, now: Long = System.currentTimeMillis()) {
        update(localId) { site ->
            val mark = PairAgain.markAfter(site.pairAgain, failure, now)
            if (mark == site.pairAgain) site else site.copy(pairAgain = mark)
        }
    }

    /** Whether [localId]'s webhook may be asked now, a marked one only at the slow check's pace (see [PairAgain]). */
    fun claimRead(localId: String, now: Long = System.currentTimeMillis()): Boolean = mutate { state ->
        val existing = state.sites.find { it.localId == localId } ?: return@mutate state to true
        val mark = existing.pairAgain ?: return@mutate state to true
        if (!PairAgain.mayRead(mark, now)) return@mutate state to false
        val checked = existing.copy(pairAgain = mark.copy(checkedAt = now))
        state.copy(sites = state.sites.map { if (it.localId == localId) checked else it }) to true
    }

    private inline fun <T> mutate(block: (State) -> Pair<State, T>): T {
        synchronized(lock) {
            val current = readState()
            val (next, result) = block(current)
            if (next != current) writeState(next)
            return result
        }
    }

    private fun readState(): State {
        val raw = store.getString(STATE_KEY) ?: return State()
        return runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray(KEY_SITES) ?: JSONArray()
            val sites = (0 until array.length()).mapNotNull { index ->
                runCatching { parse(array.getJSONObject(index)) }.getOrElse { error ->
                    logWarning("Skipping unreadable site profile at index $index: ${error.javaClass.simpleName}")
                    null
                }
            }
            State(json.optStringOrNull(KEY_ACTIVE)?.takeIf { id -> sites.any { it.localId == id } }, sites)
        }.getOrElse { error ->
            logWarning("Resetting unreadable site profile store: ${error.javaClass.simpleName}")
            State()
        }
    }

    private fun writeState(state: State) {
        val json = JSONObject().apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_ACTIVE, state.activeSiteId)
            put(KEY_SITES, JSONArray().apply { state.sites.forEach { put(it.toJson()) } })
        }
        store.putString(STATE_KEY, json.toString())
    }

    private data class State(val activeSiteId: String? = null, val sites: List<SiteProfile> = emptyList())

    companion object {
        private const val TAG = "SiteProfileStore"
        private val lock = Any()
        private const val PREFS = "site_profiles"
        private const val STATE_KEY = "state"
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_ACTIVE = "active_site_id"
        private const val KEY_SITES = "sites"

        internal fun newLocalId(): String = UUID.randomUUID().toString()

        fun forContext(context: Context): SiteProfileStore =
            SiteProfileStore(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))

        private fun parse(json: JSONObject): SiteProfile? {
            val localId = json.optStringOrNull("local_id")?.takeIf { it.isNotBlank() } ?: return null
            val siteId = json.optStringOrNull("site_id")?.takeIf { it.isNotBlank() } ?: return null
            val chargers = json.optJSONArray("chargers")?.let { list ->
                (0 until list.length()).mapNotNull { i ->
                    val c = list.optJSONObject(i) ?: return@mapNotNull null
                    val id = c.optStringOrNull("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SiteCharger(id, c.optStringOrNull("name") ?: id)
                }
            }.orEmpty()
            val marked = if (json.has("pair_again_marked_at")) json.optLong("pair_again_marked_at") else null
            return SiteProfile(
                localId = localId,
                baseUrl = json.optString("base_url", ""),
                webhookId = json.optString("webhook_id", ""),
                siteId = siteId,
                name = json.optString("name", ""),
                chargers = chargers,
                pairAgain = marked?.let { PairAgainMark(it, if (json.has("pair_again_checked_at")) json.optLong("pair_again_checked_at") else it) }
            )
        }

        private fun SiteProfile.toJson(): JSONObject = JSONObject().apply {
            put("local_id", localId)
            put("base_url", baseUrl)
            put("webhook_id", webhookId)
            put("site_id", siteId)
            put("name", name)
            put("chargers", JSONArray().apply { chargers.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
            pairAgain?.let {
                put("pair_again_marked_at", it.markedAt)
                put("pair_again_checked_at", it.checkedAt)
            }
        }
    }
}
