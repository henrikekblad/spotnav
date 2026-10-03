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
    data class Updated(val areas: List<PriceMarket>, val version: Int = RelayContractVersion.V1) : CatalogueRefresh

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

    /**
     * The last valid contract-v2 list. Held beside, never instead of, [KEY_LAST_GOOD]'s shape: adopting
     * one version removes the other, so a relay that goes back to v1 is never shadowed by an old v2 list.
     */
    internal const val KEY_LAST_GOOD_V2 = "last_good_v2"
    internal const val KEY_REFRESHED_AT = "refreshed_at_v1"

    /** The bundled 12-area snapshot, in the relay's own shape. */
    const val SNAPSHOT_ASSET = "areas_snapshot.json"

    const val AREAS_URL = "https://spotnav.sensnology.se/v1/areas.json"
    const val AREAS_V2_URL = "https://spotnav.sensnology.se/v2/areas.json"

    /** One held or fetched list, and the contract version it was read in. */
    internal data class Versioned(val areas: List<PriceMarket>, val version: Int)

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
    internal fun lastGood(store: KeyValueStore, log: RelayLog = RelayLog.NONE): List<PriceMarket>? =
        lastGoodVersioned(store, log)?.areas

    /** [lastGood] with the version it was read in: a held v2 list first, else a held v1 list. */
    internal fun lastGoodVersioned(store: KeyValueStore, log: RelayLog = RelayLog.NONE): Versioned? {
        for ((key, version) in listOf(KEY_LAST_GOOD_V2 to RelayContractVersion.V2, KEY_LAST_GOOD to RelayContractVersion.V1)) {
            val body = store.getString(key) ?: continue
            when (val parsed = RelayAreasParser.parse(body, version)) {
                is CatalogueParse.Invalid ->
                    log.log(LogLevel.WARN, "Stored catalogue is no longer valid (${parsed.reason}); ignoring it")
                is CatalogueParse.Ok -> return Versioned(parsed.catalogue.areas.map(PriceMarket::of), version)
            }
        }
        return null
    }

    /**
     * The areas to start from: the last valid remote catalogue, else the bundled snapshot, else
     * nothing.
     */
    internal fun startupAreas(store: KeyValueStore, snapshot: () -> String?, log: RelayLog = RelayLog.NONE): List<PriceMarket> =
        startup(store, snapshot, log).areas

    /** [startupAreas] with the version they were read in (the bundled snapshot is a v1 list). */
    internal fun startup(store: KeyValueStore, snapshot: () -> String?, log: RelayLog = RelayLog.NONE): Versioned {
        lastGoodVersioned(store, log)?.let { return it }
        val body = snapshot()
        if (body == null) {
            log.log(LogLevel.WARN, "No stored catalogue and no bundled snapshot")
            return Versioned(emptyList(), RelayContractVersion.V1)
        }
        return when (val parsed = RelayAreasParser.parse(body)) {
            is CatalogueParse.Invalid -> {
                log.log(LogLevel.ERROR, "Bundled snapshot is invalid (${parsed.reason})")
                Versioned(emptyList(), RelayContractVersion.V1)
            }
            is CatalogueParse.Ok -> Versioned(parsed.catalogue.areas.map(PriceMarket::of), RelayContractVersion.V1)
        }
    }

    /** Accept a fetched body of [version], or refuse it whole. */
    internal fun adopt(
        store: KeyValueStore,
        body: String,
        nowMillis: Long,
        version: Int = RelayContractVersion.V1
    ): CatalogueRefresh {
        return when (val parsed = RelayAreasParser.parse(body, version)) {
            is CatalogueParse.Invalid -> CatalogueRefresh.Failed(parsed.reason)
            is CatalogueParse.Ok -> {
                val areas = parsed.catalogue.areas.map(PriceMarket::of)
                val (key, other) = keysFor(version)
                store.putString(key, body)
                store.remove(other)
                store.putString(KEY_REFRESHED_AT, nowMillis.toString())
                CatalogueRefresh.Updated(areas, version)
            }
        }
    }

    /** The storage key of a [version]'s list, and the other version's, which adopting it retires. */
    private fun keysFor(version: Int): Pair<String, String> =
        if (version == RelayContractVersion.V2) KEY_LAST_GOOD_V2 to KEY_LAST_GOOD else KEY_LAST_GOOD to KEY_LAST_GOOD_V2

    /** When the app last *attempted* a refresh, or `null` if it never has. */
    internal fun lastRefreshedAt(store: KeyValueStore): Long? =
        store.getString(KEY_REFRESHED_AT)?.toLongOrNull()

    /**
     * Fetch and adopt, if one is due; `null` when none was. The v2 list ([fetchV2]) is asked for first;
     * a relay that has none (404), or a v2 list this client cannot read, falls back to the v1 list
     * ([fetch]). A v2 request that merely failed (no network, a 5xx) keeps a held v2 list rather than
     * stepping back to v1 and losing the areas only v2 lists.
     */
    internal fun refresh(
        store: KeyValueStore,
        fetch: () -> String?,
        nowMillis: Long,
        force: Boolean = false,
        fetchV2: () -> RelayFetch = { RelayFetch.NotFound }
    ): CatalogueRefresh? {
        if (!isRefreshDue(lastRefreshedAt(store), nowMillis, force)) return null
        if (!refreshInFlight.compareAndSet(false, true)) return CatalogueRefresh.AlreadyRefreshing
        try {
            val v2 = fetchV2()
            store.putString(KEY_REFRESHED_AT, nowMillis.toString())
            when (v2) {
                is RelayFetch.Body -> {
                    val previous = store.getString(KEY_LAST_GOOD_V2)
                    val result = adopt(store, v2.text, nowMillis, RelayContractVersion.V2)
                    if (result is CatalogueRefresh.Updated) {
                        return if (previous == v2.text) CatalogueRefresh.Unchanged else result
                    }
                }
                RelayFetch.Failed ->
                    if (store.getString(KEY_LAST_GOOD_V2) != null) return CatalogueRefresh.Failed("no response")
                RelayFetch.NotFound -> Unit
            }
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
        val held = startup(store(context), { readSnapshot(context) }, AndroidRelayLog)
        AndroidRelayLog.log(LogLevel.INFO, "Catalogue: ${held.areas.size} areas loaded (v${held.version})")
        PriceMarkets.replace(held.areas, held.version)
    }

    /** One refresh attempt if due, holding the last-good catalogue on any failure. */
    fun refreshIfDue(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        force: Boolean = false
    ): CatalogueRefresh? {
        val result = refresh(
            store(context),
            { HttpRelayTransport.areas() },
            nowMillis,
            force,
            fetchV2 = { HttpRelayTransport.areasV2() }
        )
        if (result is CatalogueRefresh.Updated) {
            AndroidRelayLog.log(LogLevel.INFO, "Catalogue: ${result.areas.size} areas from the relay (v${result.version})")
            PriceMarkets.replace(result.areas, result.version)
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
