package se.sensnology.spotnav.chargers

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.app.optIntOrNull
import se.sensnology.spotnav.app.optStringOrNull
import se.sensnology.spotnav.ha.dashboard.Dashboard
import java.util.UUID

/** Stores SpotNav's internal charger-profile list. */
internal class ChargerProfileStore(
    private val store: KeyValueStore,
    private val logWarning: (String) -> Unit = { message -> Log.w(TAG, message) }
) {
    fun listProfiles(): List<ChargerProfile> {
        return synchronized(lock) { readState().profiles }
    }

    fun getProfile(localId: String): ChargerProfile? = listProfiles().find { it.localId == localId }

    /** Inserts a new profile, or replaces the existing one with the same [ChargerProfile.localId]. */
    fun upsertProfile(profile: ChargerProfile) {
        mutateState { state ->
            val index = state.profiles.indexOfFirst { it.localId == profile.localId }
            val profiles = if (index >= 0) {
                state.profiles.toMutableList().also { it[index] = profile }
            } else {
                state.profiles + profile
            }
            state.copy(profiles = profiles)
        }
    }

    /**
     * Removes a profile. Removing the active profile always clears the active profile ID to `null`
     * — it is never silently reassigned to a remaining profile, since there is no meaningful way to
     * pick which one.
     */
    fun removeProfile(localId: String) {
        mutateState { state ->
            state.copy(
                profiles = state.profiles.filterNot { it.localId == localId },
                activeProfileId = state.activeProfileId.takeUnless { it == localId }
            )
        }
    }

    fun getActiveProfileId(): String? {
        return synchronized(lock) { readState().activeProfileId }
    }

    /** [localId] must be `null` or an existing profile's ID; an unknown ID is rejected. */
    fun setActiveProfileId(localId: String?) {
        mutateState { state ->
            require(localId == null || state.profiles.any { it.localId == localId }) {
                "Unknown charger profile id"
            }
            state.copy(activeProfileId = localId)
        }
    }

    fun getActiveProfile(): ChargerProfile? {
        return synchronized(lock) {
            val state = readState()
            state.activeProfileId?.let { activeId ->
                state.profiles.find { it.localId == activeId }
            }
        }
    }

    /**
     * Atomically replaces an existing profile with `transform(existing)`, reading and writing
     * inside the *same* lock acquisition (see [mutate]).
     */
    fun updateProfile(localId: String, transform: (ChargerProfile) -> ChargerProfile): ChargerProfile? {
        return mutate { state ->
            val existing = state.profiles.find { it.localId == localId }
                ?: return@mutate state to null
            val updated = transform(existing)
            val profiles = state.profiles.map { if (it.localId == localId) updated else it }
            state.copy(profiles = profiles) to updated
        }
    }

    /** Merges Home Assistant's last-reported identity and phase metadata into a stored profile. */
    fun updateFromDashboard(localId: String, dashboard: Dashboard): ChargerProfile? =
        updateProfile(localId) { existing ->
            existing.copy(
                remoteChargerId = dashboard.chargerId,
                remoteChargerName = existing.renamedFromDashboard(dashboard.chargerName)?.remoteChargerName
                    ?: existing.remoteChargerName,
                detectedPhases = dashboard.detectedPhases ?: existing.detectedPhases,
                phaseDetectionSource = dashboard.phaseDetectionSource ?: existing.phaseDetectionSource,
                phaseDetectionConfidence = dashboard.phaseDetectionConfidence ?: existing.phaseDetectionConfidence,
                priceDays = dashboard.capabilities.priceDays
            )
        }

    /**
     * Whether [localId]'s Home Assistant answers `price_days`, from a dashboard read that changes nothing
     * else about the profile (the widget's own reads).
     */
    fun recordPriceDays(localId: String, priceDays: Boolean) {
        updateProfile(localId) { it.copy(priceDays = priceDays) }
    }

    /**
     * Applies a dashboard fetch's outcome to exactly [profileId] — never whichever profile happens
     * to be active by the time this runs.
     */
    fun applyDashboardResult(profileId: String, result: Result<Dashboard>, now: Long = System.currentTimeMillis()) {
        result.onSuccess { dashboard -> updateFromDashboard(profileId, dashboard) }
        recordWebhookAnswer(profileId, result.exceptionOrNull(), now)
    }

    /**
     * Whether [localId]'s webhook is (still, or no longer) one Home Assistant recognises, from one
     * request to it that failed with [failure] (`null`: it answered). See [PairAgain.afterRead].
     */
    fun recordWebhookAnswer(localId: String, failure: Throwable?, now: Long = System.currentTimeMillis()) {
        updateProfile(localId) { PairAgain.afterRead(it, failure, now) }
    }

    /**
     * Whether [localId]'s webhook may be asked now, taken atomically: a charger waiting to be paired
     * again is asked only when its slow check is due, and that check is then counted as made (see
     * [PairAgain]), so two readers never both make it. An unknown profile is not this store's to
     * refuse.
     */
    fun claimRead(localId: String, now: Long = System.currentTimeMillis()): Boolean = mutate { state ->
        val existing = state.profiles.find { it.localId == localId } ?: return@mutate state to true
        val mark = existing.pairAgain ?: return@mutate state to true
        if (!PairAgain.mayRead(existing, now)) return@mutate state to false
        val checked = existing.copy(pairAgain = mark.copy(checkedAt = now))
        state.copy(profiles = state.profiles.map { if (it.localId == localId) checked else it }) to true
    }

    /** Runs [block] under [lock] against the current state and persists the state it returns, skipping the write if nothing changed. */
    private inline fun mutateState(block: (StoreState) -> StoreState) {
        mutate { state -> block(state) to Unit }
    }

    /** As [mutateState], but [block] also returns a value of type [T] to hand back to the caller. */
    private inline fun <T> mutate(block: (StoreState) -> Pair<StoreState, T>): T {
        synchronized(lock) {
            val current = readState()
            val (newState, result) = block(current)
            if (newState != current) writeState(newState)
            return result
        }
    }

    private fun readState(): StoreState {
        val raw = store.getString(STATE_KEY) ?: return StoreState()
        return runCatching {
            val json = JSONObject(raw)
            StoreState(
                activeProfileId = json.optStringOrNull(KEY_ACTIVE_PROFILE_ID),
                profiles = json.optJSONArray(KEY_PROFILES)?.let { parseProfiles(it, logWarning) }.orEmpty()
            )
        }.getOrElse { error ->
            // The whole store is unreadable (corrupt JSON, wrong type, etc.): never crash startup
            // over it. Treat it as freshly installed.
            logWarning("Resetting unreadable charger profile store: ${error.javaClass.simpleName}")
            StoreState()
        }
    }

    private fun writeState(state: StoreState) {
        val json = JSONObject().apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_ACTIVE_PROFILE_ID, state.activeProfileId)
            put(KEY_PROFILES, JSONArray().apply { state.profiles.forEach { put(it.toJson()) } })
        }
        store.putString(STATE_KEY, json.toString())
    }

    private data class StoreState(
        val activeProfileId: String? = null,
        val profiles: List<ChargerProfile> = emptyList()
    )

    companion object {
        private const val TAG = "ChargerProfileStore"

        // Shared by every ChargerProfileStore instance in the process, not just this one:
        // forContext() hands out a fresh instance per call, all backed by the same
        // SharedPreferences file, so synchronizing on `this` would let two independently
        // constructed instances race against each other exactly as if there were no lock at all.
        private val lock = Any()

        private const val PROFILES_PREFS = "charger_profiles"

        private const val STATE_KEY = "state"
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_PROFILES = "profiles"

        internal fun newLocalId(): String = UUID.randomUUID().toString()

        fun forContext(context: Context): ChargerProfileStore = ChargerProfileStore(
            store = SharedPreferencesKeyValueStore(context, PROFILES_PREFS)
        )

        private fun parseProfiles(array: JSONArray, logWarning: (String) -> Unit): List<ChargerProfile> {
            val profiles = mutableListOf<ChargerProfile>()
            for (index in 0 until array.length()) {
                val profile = runCatching { parseProfile(array.getJSONObject(index)) }
                    .getOrElse { error ->
                        // Never let one bad entry take down the whole list or crash startup; skip
                        // just this one, with no secret value read from it before the catch.
                        logWarning("Skipping unreadable charger profile at index $index: ${error.javaClass.simpleName}")
                        null
                    }
                if (profile != null) profiles.add(profile)
            }
            return profiles
        }

        private fun parseProfile(json: JSONObject): ChargerProfile? {
            // local_id is the one field a profile cannot be recovered without: with no stable
            // identity there is nothing to key updates or the active-profile pointer against, so
            // the entry is skipped rather than given a fabricated ID.
            val localId = json.optStringOrNull("local_id")?.takeIf { it.isNotBlank() } ?: return null
            return ChargerProfile(
                localId = localId,
                displayName = json.optString("display_name", ""),
                baseUrl = json.optString("base_url", ""),
                webhookId = json.optString("webhook_id", ""),
                remoteChargerId = json.optStringOrNull("remote_charger_id"),
                remoteChargerName = json.optStringOrNull("remote_charger_name"),
                detectedPhases = json.optIntOrNull("detected_phases")?.takeIf { it in 1..3 },
                phaseDetectionSource = json.optStringOrNull("phase_detection_source"),
                phaseDetectionConfidence = json.optStringOrNull("phase_detection_confidence"),
                selectedVehicleId = json.optStringOrNull("selected_vehicle_id")?.takeIf { it.isNotBlank() },
                targetSocPercent = json.optIntOrNull("target_soc_percent")?.takeIf { it in 0..100 },
                pairAgain = json.optLongOrNull("pair_again_marked_at")?.let { markedAt ->
                    PairAgainMark(markedAt, json.optLongOrNull("pair_again_checked_at") ?: markedAt)
                },
                priceDays = if (json.has("price_days") && !json.isNull("price_days")) json.opt("price_days") as? Boolean else null
            )
        }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) (opt(key) as? Number)?.toLong() else null

        private fun ChargerProfile.toJson(): JSONObject = JSONObject().apply {
            put("local_id", localId)
            put("display_name", displayName)
            put("base_url", baseUrl)
            put("webhook_id", webhookId)
            put("remote_charger_id", remoteChargerId)
            put("remote_charger_name", remoteChargerName)
            put("detected_phases", detectedPhases)
            put("phase_detection_source", phaseDetectionSource)
            put("phase_detection_confidence", phaseDetectionConfidence)
            put("selected_vehicle_id", selectedVehicleId)
            put("target_soc_percent", targetSocPercent)
            pairAgain?.let {
                put("pair_again_marked_at", it.markedAt)
                put("pair_again_checked_at", it.checkedAt)
            }
            priceDays?.let { put("price_days", it) }
        }
    }
}
