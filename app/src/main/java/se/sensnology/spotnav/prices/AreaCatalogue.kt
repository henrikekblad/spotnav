package se.sensnology.spotnav.prices

import android.content.Context
import se.sensnology.spotnav.app.AndroidRelayLog
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import java.util.concurrent.atomic.AtomicBoolean

/** What a refresh did, for the caller's log and the settings screen's own state. */
sealed interface CatalogueRefresh {
    /** A newly fetched, valid document replaced the persisted one. */
    data class Updated(val areas: List<PriceMarket>) : CatalogueRefresh

    /** Fetched and valid, but nothing about it changed. */
    data object Unchanged : CatalogueRefresh

    /** Refused: the last-good catalogue (or the snapshot) stands. */
    data class Failed(val reason: String) : CatalogueRefresh

    /** Another refresh is already in flight, so this caller did nothing. */
    data object AlreadyRefreshing : CatalogueRefresh
}

/** Where the area catalogue comes from, in priority order, and what a failed refresh does. */
object AreaCatalogue {
    internal const val PREFS = "area_catalogue"

    /**
     * The storage key carries a schema version, so a future change of shape is a new key rather
     * than a re-parse of something written by an older build.
     */
    internal const val KEY_LAST_GOOD = "last_good_v1"
    internal const val KEY_REFRESHED_AT = "refreshed_at_v1"

    /** The bundled 12-area snapshot, in the relay's own shape. */
    const val SNAPSHOT_ASSET = "areas_snapshot.json"

    const val AREAS_URL = "https://spotnav.sensnology.se/v1/areas.json"

    /**
     * An hour, matching the relay's own `Cache-Control: max-age=3600`: asking more often than the
     * server is willing to change an answer buys nothing, and asking less often would leave a newly
     * served area unselectable for longer than the server asked to be believed.
     */
    internal const val FRESH_FOR_MS = 60L * 60L * 1000L

    /** The process-wide "one refresh at a time" gate. */
    private val refreshInFlight = AtomicBoolean(false)

    /**
     * Is a refresh due? Pure, so "how old is old enough" is a test and not a thing discovered by
     * watching logs.
     */
    internal fun isRefreshDue(lastRefreshedAt: Long?, nowMillis: Long, force: Boolean): Boolean {
        if (force) return true
        if (lastRefreshedAt == null || lastRefreshedAt <= 0L) return true
        return nowMillis - lastRefreshedAt >= FRESH_FOR_MS
    }

    /** The last completely valid remote catalogue, or `null`. */
    internal fun lastGood(store: KeyValueStore, log: RelayLog = RelayLog.NONE): List<PriceMarket>? {
        val body = store.getString(KEY_LAST_GOOD) ?: return null
        return when (val parsed = RelayAreasParser.parse(body)) {
            is CatalogueParse.Invalid -> {
                log.log(LogLevel.WARN, "Stored catalogue is no longer valid (${parsed.reason}); ignoring it")
                null
            }
            is CatalogueParse.Ok -> parsed.catalogue.areas.map(PriceMarket::of)
        }
    }

    /**
     * The areas to start from: the last valid remote catalogue, else the bundled snapshot, else
     * nothing.
     */
    internal fun startupAreas(store: KeyValueStore, snapshot: () -> String?, log: RelayLog = RelayLog.NONE): List<PriceMarket> {
        lastGood(store, log)?.let { return it }
        val body = snapshot()
        if (body == null) {
            log.log(LogLevel.WARN, "No stored catalogue and no bundled snapshot")
            return emptyList()
        }
        return when (val parsed = RelayAreasParser.parse(body)) {
            is CatalogueParse.Invalid -> {
                log.log(LogLevel.ERROR, "Bundled snapshot is invalid (${parsed.reason})")
                emptyList()
            }
            is CatalogueParse.Ok -> parsed.catalogue.areas.map(PriceMarket::of)
        }
    }

    /** Accept a fetched body, or refuse it whole. */
    internal fun adopt(store: KeyValueStore, body: String, nowMillis: Long): CatalogueRefresh {
        return when (val parsed = RelayAreasParser.parse(body)) {
            is CatalogueParse.Invalid -> CatalogueRefresh.Failed(parsed.reason)
            is CatalogueParse.Ok -> {
                val areas = parsed.catalogue.areas.map(PriceMarket::of)
                store.putString(KEY_LAST_GOOD, body)
                store.putString(KEY_REFRESHED_AT, nowMillis.toString())
                CatalogueRefresh.Updated(areas)
            }
        }
    }

    /** When the app last *attempted* a refresh, or `null` if it never has. */
    internal fun lastRefreshedAt(store: KeyValueStore): Long? =
        store.getString(KEY_REFRESHED_AT)?.toLongOrNull()

    /** Fetch and adopt, if one is due; `null` when none was. */
    internal fun refresh(
        store: KeyValueStore,
        fetch: () -> String?,
        nowMillis: Long,
        force: Boolean = false
    ): CatalogueRefresh? {
        if (!isRefreshDue(lastRefreshedAt(store), nowMillis, force)) return null
        if (!refreshInFlight.compareAndSet(false, true)) return CatalogueRefresh.AlreadyRefreshing
        try {
            val body = fetch()
            store.putString(KEY_REFRESHED_AT, nowMillis.toString())
            if (body == null) return CatalogueRefresh.Failed("no response")
            val previous = store.getString(KEY_LAST_GOOD)
            val result = adopt(store, body, nowMillis)
            // Identical bytes are not a change: the picker must not be rebuilt, and the caller must
            // not log an update that did not happen.
            if (result is CatalogueRefresh.Updated && previous == body) return CatalogueRefresh.Unchanged
            return result
        } finally {
            refreshInFlight.set(false)
        }
    }

    // Android-facing wrappers

    /** Load the catalogue the app will start from, and hold it. */
    fun load(context: Context) {
        val areas = startupAreas(store(context), { readSnapshot(context) }, AndroidRelayLog)
        AndroidRelayLog.log(LogLevel.INFO, "Catalogue: ${areas.size} areas loaded")
        PriceMarkets.replace(areas)
    }

    /** One refresh attempt if due, holding the last-good catalogue on any failure. */
    fun refreshIfDue(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): CatalogueRefresh? {
        val result = refresh(store(context), { HttpRelayTransport.areas() }, nowMillis, force)
        if (result is CatalogueRefresh.Updated) {
            AndroidRelayLog.log(LogLevel.INFO, "Catalogue: ${result.areas.size} areas from the relay")
            PriceMarkets.replace(result.areas)
        }
        return result
    }

    private fun store(context: Context): KeyValueStore =
        SharedPreferencesKeyValueStore(context, PREFS)

    /** The bundled snapshot, or `null` when this build has none. */
    private fun readSnapshot(context: Context): String? = runCatching {
        context.assets.open(SNAPSHOT_ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()
}
