package se.sensnology.spotnav.prices

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore

/**
 * Whether this install used the relay's prices before the app fetched them itself: computed once, from
 * what only reaching the relay ever wrote (a held day file, or a catalogue refresh), before anything of
 * this version writes price data, and stored in prefs `price_source`. A fresh install is `false` forever.
 *
 * The flag no longer gives anyone the relay's prices (the owner's hard cut): it only decides who sees the
 * one-time explanation of why a key is needed ([whyShown]/[markWhyShown]), because they had prices before.
 */
internal object LegacyPriceUser {
    internal const val PREFS = "price_source"
    internal const val KEY_LEGACY = "legacy_user"
    internal const val KEY_MIGRATED_AT = "migrated_at"
    internal const val KEY_WHY_SHOWN = "why_shown"

    private val RELAY_DAY = Regex("""${PriceRepository.SCHEMA}:[A-Z0-9-]{1,32}:\d{4}-\d{2}-\d{2}""")

    /** The flag, computed and stored on the first call; later calls read what was stored. */
    @Synchronized
    fun ensure(flags: KeyValueStore, priceCache: KeyValueStore, catalogue: KeyValueStore, nowMillis: Long): Boolean {
        flags.getString(KEY_LEGACY)?.let { return it.toBoolean() }
        val legacy = priceCache.keys().any { RELAY_DAY.matches(it) } ||
            catalogue.getString(RelayLeftovers.KEY_REFRESHED_AT) != null
        flags.putString(KEY_LEGACY, legacy.toString())
        flags.putString(KEY_MIGRATED_AT, nowMillis.toString())
        return legacy
    }

    fun whyShown(flags: KeyValueStore): Boolean = flags.getString(KEY_WHY_SHOWN).toBoolean()

    fun markWhyShown(flags: KeyValueStore) = flags.putString(KEY_WHY_SHOWN, "true")

    @Volatile
    private var known: Boolean? = null

    /** [ensure] over the app's own files: called before the price cache or the catalogue is written. */
    fun ensure(context: Context): Boolean = known ?: ensure(
        SharedPreferencesKeyValueStore(context, PREFS),
        SharedPreferencesKeyValueStore(context, PriceRepository.CACHE_PREFS),
        SharedPreferencesKeyValueStore(context, AreaCatalogue.PREFS),
        System.currentTimeMillis()
    ).also { known = it }
}
