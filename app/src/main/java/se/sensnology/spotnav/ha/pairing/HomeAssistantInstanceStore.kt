package se.sensnology.spotnav.ha.pairing

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.chargers.ChargerProfile

/** Which Home Assistant instance this app is connected to. */
internal class HomeAssistantInstanceStore(private val store: KeyValueStore) {
    /** The connected instance's address, or `null` when there is none. */
    fun baseUrl(): String? = store.getString(KEY_BASE_URL)?.takeIf { it.isNotBlank() }

    /** What this instance calls itself, when discovery heard a name. */
    fun name(): String? = store.getString(KEY_NAME)?.takeIf { it.isNotBlank() }

    /**
     * Remember [baseUrl] as the connected instance. Trailing slashes are dropped so the same
     * instance always compares equal to itself -- the reconciliation matches profiles by this
     * value.
     */
    fun remember(baseUrl: String, name: String? = null) {
        store.putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
        if (name.isNullOrBlank()) store.remove(KEY_NAME) else store.putString(KEY_NAME, name.trim())
    }

    /** Forget the instance. Callers decide what happens to its chargers. */
    fun forget() {
        store.remove(KEY_BASE_URL)
        store.remove(KEY_NAME)
    }

    companion object {
        private const val PREFS = "home_assistant_instance"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_NAME = "name"

        fun forContext(context: Context): HomeAssistantInstanceStore =
            HomeAssistantInstanceStore(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
