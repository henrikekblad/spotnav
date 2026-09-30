package se.sensnology.spotnav.widget

import android.content.Context
import android.util.Log
import org.json.JSONObject
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.ha.dashboard.Dashboard

/** A charger's last dashboard, as it was read, and when. */
internal data class StoredDashboard(val dashboard: Dashboard, val capturedAt: Long) {
    val status: StoredStatus get() = StoredStatus.of(dashboard, capturedAt)
}

/**
 * The last dashboard Home Assistant answered for one charger, kept whole so a widget can draw from it
 * later. Both a widget's chart and its bottom line are drawn from it; a newer capture is never replaced
 * by an older one. Keyed by `ChargerProfile.localId`; a picture of what Home Assistant said, never an
 * authority.
 *
 * The answer's own JSON is stored and decoded by the same decoder, so an undecodable document is treated
 * as absent. Nothing secret is in it (no URL, no webhook id).
 */
internal class WidgetDashboardStore(
    private val store: KeyValueStore,
    private val logWarning: (String) -> Unit = { message -> Log.w(TAG, message) }
) {
    fun dashboardFor(profileId: String?): StoredDashboard? {
        val id = profileId?.takeIf { it.isNotBlank() } ?: return null
        val held = store.getString(key(id)) ?: return null
        val captured = held.substringBefore('\n', "").toLongOrNull() ?: return unreadable()
        return try {
            StoredDashboard(Dashboard.parse(JSONObject(held.substringAfter('\n'))), captured)
        } catch (failure: Exception) {
            unreadable()
        }
    }

    /** Store the dashboard answer [body] for [profileId], unless what is retained was captured later. */
    fun put(profileId: String, body: String, capturedAt: Long) {
        if (profileId.isBlank()) return
        synchronized(lock) {
            val retained = store.getString(key(profileId))?.substringBefore('\n', "")?.toLongOrNull()
            if (retained != null && retained > capturedAt) return
            // One value with the capture time on the first line, so time and answer never mismatch.
            store.putString(key(profileId), "$capturedAt\n$body")
        }
    }

    fun clear(profileId: String) = synchronized(lock) { store.remove(key(profileId)) }

    private fun <T> unreadable(): T? {
        logWarning("A widget dashboard for one charger could not be read")
        return null
    }

    private fun key(profileId: String) = "$KEY_PREFIX$profileId"

    companion object {
        private const val TAG = "SpotNavWidgetDashboard"
        internal const val PREFS = "spotnav_widget_dashboard"
        private const val KEY_PREFIX = "dashboard."
        private val lock = Any()

        fun forContext(context: Context): WidgetDashboardStore =
            WidgetDashboardStore(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
