package se.sensnology.spotnav.testing

import se.sensnology.spotnav.app.KeyValueStore

/** In-memory [KeyValueStore] fake so storage/migration logic can be unit tested without Android. */
class FakeKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    /** Lets a test corrupt or hand-craft raw storage content directly. */
    fun setRaw(key: String, value: String) {
        values[key] = value
    }

    fun rawOrNull(key: String): String? = values[key]
}
