package se.sensnology.spotnav.prices

import android.content.Context
import se.sensnology.spotnav.app.AndroidRelayLog
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore

/**
 * The area list: the one bundled with the app, which is Home Assistant's own catalogue (contract v2 plus
 * `routes`), copied byte for byte by `scripts/sync_ha_fixtures.sh`. Nothing is asked of the relay for it.
 */
object AreaCatalogue {
    /** The preferences file the relay's area lists were once held in; emptied once (see [RelayLeftovers]). */
    internal const val PREFS = "area_catalogue"

    const val BUNDLED_ASSET = "areas.json"

    /** The bundled list's areas, or none when this build has no readable one. */
    internal fun bundledAreas(body: String?, log: RelayLog = RelayLog.NONE): List<PriceMarket> {
        if (body == null) {
            log.log(LogLevel.WARN, "No bundled area list")
            return emptyList()
        }
        return when (val parsed = RelayAreasParser.parse(body)) {
            is CatalogueParse.Invalid -> {
                log.log(LogLevel.ERROR, "Bundled area list is invalid (${parsed.reason})")
                emptyList()
            }
            is CatalogueParse.Ok -> parsed.catalogue.areas.map(PriceMarket::of)
        }
    }

    // Android-facing wrappers

    /** Load the bundled list and hold it. */
    fun load(context: Context) {
        // Before anything of this version writes price data: whether this install used the relay. Then the
        // relay's lists and index, which nothing reads any longer, are dropped.
        LegacyPriceUser.ensure(context)
        RelayLeftovers.forget(
            catalogue = SharedPreferencesKeyValueStore(context, PREFS),
            priceCache = SharedPreferencesKeyValueStore(context, PriceRepository.CACHE_PREFS)
        )
        val areas = bundledAreas(readBundled(context), AndroidRelayLog)
        AndroidRelayLog.log(LogLevel.INFO, "Catalogue: ${areas.size} areas loaded")
        PriceMarkets.replace(areas)
    }

    /** The bundled area list, or `null` when this build has none. */
    private fun readBundled(context: Context): String? = runCatching {
        context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()
}

/**
 * What the app kept while it read the relay's area lists and index, removed once nothing reads it: the held
 * lists and their refresh time (`area_catalogue`), and the held indexes beside the day files. The day files
 * themselves stay: they are the same documents every source writes, and the cache prunes them by date.
 * [LegacyPriceUser] reads [KEY_REFRESHED_AT] before this runs.
 */
internal object RelayLeftovers {
    internal const val KEY_LAST_GOOD = "last_good_v1"
    internal const val KEY_LAST_GOOD_V2 = "last_good_v2"
    internal const val KEY_REFRESHED_AT = "refreshed_at_v1"
    internal const val INDEX_KEY = "${PriceRepository.SCHEMA}:index"
    internal const val INDEX_V2_KEY = "${PriceRepository.SCHEMA}:index_v2"

    fun forget(catalogue: KeyValueStore, priceCache: KeyValueStore) {
        for (key in listOf(KEY_LAST_GOOD, KEY_LAST_GOOD_V2, KEY_REFRESHED_AT)) {
            if (catalogue.getString(key) != null) catalogue.remove(key)
        }
        for (key in listOf(INDEX_KEY, INDEX_V2_KEY)) {
            if (priceCache.getString(key) != null) priceCache.remove(key)
        }
    }
}
