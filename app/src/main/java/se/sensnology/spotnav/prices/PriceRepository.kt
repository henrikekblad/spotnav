package se.sensnology.spotnav.prices

import android.content.Context
import se.sensnology.spotnav.app.AndroidRelayLog
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * One quarter-hour point already converted for display: `start` is in the area's own zone, and
 * [pricePerKwh] is in the area's **local major** unit.
 */
data class PricePoint(val start: OffsetDateTime, val pricePerKwh: Double)

/** Where one day's prices came from, so held data is never presented as though it had just arrived. */
enum class PriceSource { RELAY, MEMORY, DISK, NONE }

data class PriceResult(
    val today: List<PricePoint>,
    val tomorrow: List<PricePoint>,
    /**
     * When this result object was assembled. Deliberately that meaning: the in-memory cache ages
     * results by it. It is *not* a claim that either day was fetched now -- see
     * [todaySource]/[tomorrowSource] and the per-day stamps.
     */
    val fetchedAt: Long,
    val todaySource: PriceSource = PriceSource.NONE,
    val tomorrowSource: PriceSource = PriceSource.NONE,
    /** When the relay was last actually reached for this day, if that is known. */
    val todayFetchedAt: Long? = null,
    val tomorrowFetchedAt: Long? = null
)

/**
 * What the relay said about one day, in three outcomes that must stay apart: a body, a genuine "the
 * index does not list this day", and a failure.
 */
internal sealed interface FetchOutcome {
    data class Body(val points: List<PricePoint>) : FetchOutcome

    /** The index does not list this area/date. No request was made. */
    data object NotListed : FetchOutcome

    /** A timeout, a connection error, or a status that is neither. */
    data object Failed : FetchOutcome
}

/** One publication refresh's outcome: what it accepted, and whether that moved the area's prices. */
internal data class PublishedPrices(val result: PriceResult, val changed: Boolean)

/** A day we already hold. `points` is never empty: an empty day is not held data. */
internal data class HeldPrices(val points: List<PricePoint>, val fetchedAt: Long?)

/** One day's resolved prices, and where they came from. */
internal data class DayPrices(
    val points: List<PricePoint>,
    val source: PriceSource,
    val fetchedAt: Long?
)

/** Whether a publication refresh accepted *new* prices for its area. */
internal fun priceDocumentsChanged(before: PriceResult?, after: PriceResult): Boolean {
    if (after.today.isEmpty() && after.tomorrow.isEmpty()) return false
    if (before == null) return true
    return before.today != after.today || before.tomorrow != after.tomorrow
}

/** The relay's three documents, as a seam a test can replace. */
internal interface RelayTransport {
    fun areas(): String?
    fun index(): String?
    fun day(areaId: String, dateKey: String): String?
}

/** The production transport: plain GETs, no cache-buster, no fallback host. */
internal object HttpRelayTransport : RelayTransport {
    const val INDEX_URL = "https://spotnav.sensnology.se/v1/index.json"
    private const val BASE = "https://spotnav.sensnology.se/v1"

    override fun areas(): String? = RelayHttp.get(AreaCatalogue.AREAS_URL)

    override fun index(): String? = RelayHttp.get(INDEX_URL)

    override fun day(areaId: String, dateKey: String): String? = RelayHttp.get(dayUrl(areaId, dateKey))

    /**
     * `v1/{area}/{YYYY}/{MM-DD}.json` from a `YYYY-MM-DD` key, exactly the path the relay
     * publishes. The area id comes from the validated catalogue, never from anything a person
     * typed.
     */
    fun dayUrl(areaId: String, dateKey: String): String =
        "$BASE/$areaId/${dateKey.substring(0, 4)}/${dateKey.substring(5)}.json"
}

/** The app's prices, and the only place a day document is requested. */
object PriceRepository {
    /** A new preferences file, and versioned keys inside it: an old upstream body can never be parsed as a relay document. */
    internal const val CACHE_PREFS = "relay_price_cache"
    internal const val SCHEMA = "v1"
    internal const val INDEX_KEY = "$SCHEMA:index"

    private const val MEMORY_CACHE_MS = 10 * 60 * 1000L
    private const val FORCE_REFRESH_DEDUP_MS = 30 * 1000L

    private val cache = ConcurrentHashMap<String, PriceResult>()

    /**
     * Drop the in-memory result cache. For tests: a unit test that asserts on a fresh request must
     * not inherit the previous test's result, and this process is the only thing that would
     * otherwise share it.
     */
    internal fun clearMemoryCacheForTest() {
        cache.clear()
    }

    /**
     * Rule: what a day's prices are, given what the relay said ([outcome]) and what we already hold
     * ([memory], then [disk]).
     */
    internal fun resolveDay(
        outcome: FetchOutcome,
        memory: HeldPrices?,
        disk: HeldPrices?,
        nowMillis: Long
    ): DayPrices {
        if (outcome is FetchOutcome.Body) return DayPrices(outcome.points, PriceSource.RELAY, nowMillis)
        val held = memory ?: disk ?: return DayPrices(emptyList(), PriceSource.NONE, null)
        return DayPrices(
            points = held.points,
            source = if (memory != null) PriceSource.MEMORY else PriceSource.DISK,
            fetchedAt = held.fetchedAt
        )
    }

    /** A day we hold in memory, for the current result. */
    internal fun holdInMemory(points: List<PricePoint>?, fetchedAt: Long?): HeldPrices? =
        if (points.isNullOrEmpty()) null else HeldPrices(points, fetchedAt)

    /**
     * The whole sequence for one area, as a function of a store, a transport and a clock -- no
     * Android, no socket, no wall clock of its own.
     */
    internal fun load(
        store: KeyValueStore,
        transport: RelayTransport,
        area: PriceMarket?,
        today: LocalDate,
        nowMillis: Long,
        forceRefresh: Boolean = false,
        log: RelayLog = RelayLog.NONE
    ): PriceResult {
        // 1. An id the catalogue does not have is not a fetch, it is a refusal: nothing is
        // requested for it, today or tomorrow.
        if (area == null) return emptyResult(nowMillis)

        val todayKey = today.toString()
        val memoryKey = "${area.id}:$todayKey"
        val previous = cache[memoryKey]
        val age = previous?.let { nowMillis - it.fetchedAt } ?: Long.MAX_VALUE
        if (previous != null &&
            ((!forceRefresh && age < MEMORY_CACHE_MS) || (forceRefresh && age < FORCE_REFRESH_DEDUP_MS))
        ) {
            log.log(LogLevel.INFO, "Memory cache hit ${area.id} today=$todayKey")
            return previous
        }

        // 2 is the caller's, because the settings screen refreshes the catalogue for its own
        // reasons; 3 happens here, and its failure is not fatal.
        val index = loadIndex(store, transport, nowMillis, log)
        val zone = area.zoneId
        val todayPrices = dayFor(store, transport, area, todayKey, index, previous?.today, previous?.todayFetchedAt, zone, nowMillis, log)
        val tomorrowPrices = dayFor(
            store, transport, area, today.plusDays(1).toString(), index,
            previous?.tomorrow, previous?.tomorrowFetchedAt, zone, nowMillis, log
        )

        if (todayPrices.points.isEmpty() && tomorrowPrices.points.isEmpty()) {
            // Nothing at all is available: keep the whole previous result rather than replacing
            // held prices with two empty lists.
            log.log(LogLevel.WARN, "No prices available for ${area.id} today=$todayKey")
            return previous ?: emptyResult(nowMillis)
        }
        return PriceResult(
            today = todayPrices.points,
            tomorrow = tomorrowPrices.points,
            fetchedAt = nowMillis,
            todaySource = todayPrices.source,
            tomorrowSource = tomorrowPrices.source,
            todayFetchedAt = todayPrices.fetchedAt,
            tomorrowFetchedAt = tomorrowPrices.fetchedAt
        ).also { cache[memoryKey] = it }
    }

    /**
     * What is held for [area] right now, and *nothing asked of anyone*: the memory result if there
     * is one, else the days on disk, else an empty result.
     */
    internal fun heldOnly(
        store: KeyValueStore,
        area: PriceMarket?,
        today: LocalDate,
        nowMillis: Long
    ): PriceResult {
        if (area == null) return emptyResult(nowMillis)
        cache["${area.id}:$today"]?.let { return it }
        val zone = area.zoneId
        val todayDay = readCachedDay(store, area, today.toString(), zone, RelayLog.NONE)
        val tomorrowDay = readCachedDay(store, area, today.plusDays(1).toString(), zone, RelayLog.NONE)
        if (todayDay == null && tomorrowDay == null) return emptyResult(nowMillis)
        return PriceResult(
            today = todayDay?.points.orEmpty(),
            tomorrow = tomorrowDay?.points.orEmpty(),
            fetchedAt = nowMillis,
            todaySource = if (todayDay != null) PriceSource.DISK else PriceSource.NONE,
            tomorrowSource = if (tomorrowDay != null) PriceSource.DISK else PriceSource.NONE,
            todayFetchedAt = todayDay?.fetchedAt,
            tomorrowFetchedAt = tomorrowDay?.fetchedAt
        )
    }

    private fun emptyResult(nowMillis: Long) = PriceResult(
        today = emptyList(),
        tomorrow = emptyList(),
        fetchedAt = nowMillis,
        todaySource = PriceSource.NONE,
        tomorrowSource = PriceSource.NONE,
        todayFetchedAt = null,
        tomorrowFetchedAt = null
    )

    /** One day, end to end: ask only when the index lists it, then resolve against what is held. */
    private fun dayFor(
        store: KeyValueStore,
        transport: RelayTransport,
        area: PriceMarket,
        dateKey: String,
        index: RelayIndex?,
        memoryPoints: List<PricePoint>?,
        memoryFetchedAt: Long?,
        zone: ZoneId,
        nowMillis: Long,
        log: RelayLog
    ): DayPrices {
        val memory = holdInMemory(memoryPoints, memoryFetchedAt)
        val disk = if (memory == null) readCachedDay(store, area, dateKey, zone, log) else null
        val outcome = if (index?.lists(area.id, dateKey) != true) {
            FetchOutcome.NotListed
        } else {
            fetchDay(store, transport, area, dateKey, zone, nowMillis, log)
        }
        return resolveDay(outcome, memory, disk, nowMillis)
    }

    private fun fetchDay(
        store: KeyValueStore,
        transport: RelayTransport,
        area: PriceMarket,
        dateKey: String,
        zone: ZoneId,
        nowMillis: Long,
        log: RelayLog
    ): FetchOutcome {
        val body = transport.day(area.id, dateKey) ?: return FetchOutcome.Failed
        return when (val parsed = RelayDayParser.parse(body, area.id, area.tz, area.currency, dateKey)) {
            is DayParse.Invalid -> {
                log.log(LogLevel.ERROR, "Invalid day document ${area.id} $dateKey: ${parsed.reason}")
                FetchOutcome.Failed
            }
            is DayParse.Ok -> {
                val points = RelayDayPoints.points(parsed.document, zone)
                if (points.isEmpty()) return FetchOutcome.Failed
                writeCachedDay(store, area.id, dateKey, body, nowMillis)
                FetchOutcome.Body(points)
            }
        }
    }

    /** The index, revalidated against the relay. */
    private fun loadIndex(
        store: KeyValueStore,
        transport: RelayTransport,
        nowMillis: Long,
        log: RelayLog
    ): RelayIndex? {
        val held = heldIndex(store)
        val body = transport.index() ?: return held
        val parsed = RelayIndexParser.parse(body)
        if (parsed == null) {
            log.log(LogLevel.ERROR, "Malformed index; keeping the last valid one")
            return held
        }
        store.putString(INDEX_KEY, body)
        return parsed
    }

    private fun heldIndex(store: KeyValueStore): RelayIndex? =
        store.getString(INDEX_KEY)?.let { RelayIndexParser.parse(it) }

    // The on-disk day cache

    /** `v1:{area}:{date}`, inside a preferences file of its own. */
    private fun dayKey(areaId: String, dateKey: String): String = "$SCHEMA:$areaId:$dateKey"

    private fun writeCachedDay(
        store: KeyValueStore,
        areaId: String,
        dateKey: String,
        body: String,
        fetchedAt: Long
    ) {
        store.putString(dayKey(areaId, dateKey), body)
        // When the relay was actually reached for this day, so a disk-held day is reported with a
        // real time instead of "now" or "unknown".
        store.putString(dayKey(areaId, dateKey) + ":at", fetchedAt.toString())
    }

    /** The day held on disk, or `null` when nothing usable is cached for it. */
    private fun readCachedDay(
        store: KeyValueStore,
        area: PriceMarket,
        dateKey: String,
        zone: ZoneId,
        log: RelayLog
    ): HeldPrices? {
        val body = store.getString(dayKey(area.id, dateKey)) ?: return null
        val parsed = RelayDayParser.parse(body, area.id, area.tz, area.currency, dateKey)
        if (parsed !is DayParse.Ok) {
            log.log(LogLevel.WARN, "Discarding an unreadable cached day ${area.id} $dateKey")
            return null
        }
        val points = RelayDayPoints.points(parsed.document, zone)
        if (points.isEmpty()) return null
        log.log(LogLevel.INFO, "Disk cache hit ${area.id} $dateKey count=${points.size}")
        val stamp = store.getString(dayKey(area.id, dateKey) + ":at")?.toLongOrNull()
        return HeldPrices(points, stamp?.takeIf { it > 0L })
    }

    // Android-facing wrappers

    /** One price load for [areaId], against the catalogue currently held. */
    /**
     * One publication refresh: what it accepted for [areaId], and whether that moved the area's
     * prices.
     */
    internal fun loadForPublication(context: Context, areaId: String): PublishedPrices {
        val before = held(areaId)
        val result = load(context, areaId, forceRefresh = true)
        return PublishedPrices(result, priceDocumentsChanged(before, result))
    }

    /** What the memory cache holds for an area right now, or `null`. No I/O, no request. */
    private fun held(areaId: String): PriceResult? {
        val market = PriceMarkets.find(areaId) ?: return null
        val todayKey = LocalDate.now(market.zoneId).toString()
        return cache["${market.id}:$todayKey"]
    }

    fun load(context: Context, areaId: String, forceRefresh: Boolean = false): PriceResult {
        val store = SharedPreferencesKeyValueStore(context, CACHE_PREFS)
        val market = PriceMarkets.find(areaId)
        val zone = market?.zoneId ?: ZoneId.systemDefault()
        return load(store, HttpRelayTransport, market, LocalDate.now(zone), System.currentTimeMillis(), forceRefresh, AndroidRelayLog)
    }

    /** [heldOnly] for [areaId], against the catalogue currently held: no request, ever. */
    fun heldOnly(context: Context, areaId: String): PriceResult {
        val store = SharedPreferencesKeyValueStore(context, CACHE_PREFS)
        val market = PriceMarkets.find(areaId)
        val zone = market?.zoneId ?: ZoneId.systemDefault()
        return heldOnly(store, market, LocalDate.now(zone), System.currentTimeMillis())
    }

    /** Whether a usable day is held for tomorrow -- the scheduler's publication check. */
    fun hasCachedTomorrow(context: Context, areaId: String): Boolean {
        val market = PriceMarkets.find(areaId) ?: return false
        val store = SharedPreferencesKeyValueStore(context, CACHE_PREFS)
        val tomorrow = LocalDate.now(market.zoneId).plusDays(1).toString()
        return readCachedDay(store, market, tomorrow, market.zoneId, AndroidRelayLog) != null
    }
}
