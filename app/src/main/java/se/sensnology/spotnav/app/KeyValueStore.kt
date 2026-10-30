package se.sensnology.spotnav.app

import android.content.Context
import se.sensnology.spotnav.chargers.ChargerProfileStore

/** A minimal string key/value store. */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)

    /** Every key held, for a store that prunes itself; a store that cannot list keys holds none. */
    fun keys(): Set<String> = emptySet()
}

/** [KeyValueStore] backed by one Android SharedPreferences file. */
internal class SharedPreferencesKeyValueStore(
    context: Context,
    /** The preference file this store reads and writes, as a readable value. */
    internal val prefsName: String
) : KeyValueStore {
    private val preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = preferences.all.keys.toSet()
}
