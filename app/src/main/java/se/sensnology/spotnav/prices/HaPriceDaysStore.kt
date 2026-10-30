package se.sensnology.spotnav.prices

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore

/**
 * The last good `price_days` answer of each paired charger, kept whole: key `{localId}` holds the answer's
 * text, `{localId}:at` when it was fetched. Each good answer replaces the last, so the store never grows.
 *
 * Apart from the day cache on purpose (design 2.2): Home Assistant dates the files of the eastern areas
 * (FI, EE, LV, LT, BG, RO, GR) on the Brussels calendar, while the standalone path dates them locally, so
 * one `v1:FI:{date}` key would mean two different files. Nothing here is ever composed with that cache.
 */
internal class HaPriceDaysStore(private val store: KeyValueStore) {
    /** A held answer's text and when it was fetched (`null` when that stamp is unreadable). */
    data class Held(val body: String, val fetchedAt: Long?)

    fun put(localId: String, body: String, fetchedAt: Long) {
        if (localId.isBlank()) return
        store.putString(localId, body)
        store.putString("$localId$AT", fetchedAt.toString())
    }

    fun held(localId: String): Held? {
        if (localId.isBlank()) return null
        val body = store.getString(localId) ?: return null
        return Held(body, store.getString("$localId$AT")?.toLongOrNull()?.takeIf { it > 0L })
    }

    fun clear(localId: String) {
        store.remove(localId)
        store.remove("$localId$AT")
    }

    companion object {
        internal const val PREFS = "ha_price_days"
        private const val AT = ":at"

        fun forContext(context: Context) = HaPriceDaysStore(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
