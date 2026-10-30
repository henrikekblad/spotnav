package se.sensnology.spotnav.prices

import android.content.Context
import se.sensnology.spotnav.app.AndroidRelayLog
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chargers.toHomeAssistantSettings
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.PriceDaysAnswer
import se.sensnology.spotnav.widget.WidgetDashboardStore
import se.sensnology.spotnav.prices.entsoe.EntsoeClient
import se.sensnology.spotnav.prices.entsoe.EntsoeKey
import se.sensnology.spotnav.prices.entsoe.EntsoeDaySource
import se.sensnology.spotnav.prices.entsoe.EntsoeKeyStore
import se.sensnology.spotnav.prices.entsoe.EntsoeLimiter
import se.sensnology.spotnav.prices.entsoe.KeyCheck
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * One quarter-hour point already converted for display: `start` is in the area's own zone, and
 * [pricePerKwh] is in the area's **local major** unit.
 *
 * [minutes] is the length of the published interval this quarter belongs to (15, 30 or 60): planning
 * reads the quarter-hour grid, while a graph or table draws each published interval as one mark.
 */
data class PricePoint(val start: OffsetDateTime, val pricePerKwh: Double, val minutes: Int = 15)

/**
 * Where one day's prices came from, so held data is never presented as though it had just arrived:
 * [FETCHED] from the subject's source just now (ENTSO-E, or Home Assistant), else held in memory or on disk.
 */
enum class PriceSource { FETCHED, MEMORY, DISK, NONE }

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
    /** When the source was last actually reached for this day, if that is known. */
    val todayFetchedAt: Long? = null,
    val tomorrowFetchedAt: Long? = null
)

/**
 * What a source said about one day, in three outcomes that must stay apart: a body, a genuine "not
 * there yet (or not worth asking now)", and a failure.
 */
internal sealed interface FetchOutcome {
    data class Body(val points: List<PricePoint>) : FetchOutcome

    /** Not published yet, or not worth asking now; when the source's schedule said so, no request was made. */
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

/**
 * Who one price load is for: the charger profile it belongs to (`null` for this phone's own area) and
 * the area. The profile is part of the subject because a paired charger's prices may come from another
 * source than the same area's on an unpaired phone.
 */
data class PriceSubject(val profileId: String?, val areaId: String)

/** The app's prices, and the only place a day document is requested. */
object PriceRepository {
    /** A new preferences file, and versioned keys inside it: an old upstream body can never be parsed as a relay document. */
    internal const val CACHE_PREFS = "relay_price_cache"
    internal const val SCHEMA = "v1"

    private const val MEMORY_CACHE_MS = 10 * 60 * 1000L
    private const val FORCE_REFRESH_DEDUP_MS = 30 * 1000L

    /** Market days older than this many days before today are pruned from the disk cache. */
    internal const val KEEP_DAYS_BEFORE_TODAY = 2L

    private val DAY_KEY = Regex("""$SCHEMA:[A-Z0-9-]{1,32}:(\d{4}-\d{2}-\d{2})(:at)?""")

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
     * Rule: what a day's prices are, given what the source said ([outcome]) and what we already hold
     * ([memory], then [disk]).
     */
    internal fun resolveDay(
        outcome: FetchOutcome,
        memory: HeldPrices?,
        disk: HeldPrices?,
        nowMillis: Long
    ): DayPrices {
        if (outcome is FetchOutcome.Body) return DayPrices(outcome.points, PriceSource.FETCHED, nowMillis)
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

    /** The memory cache's key: one result per profile, area and display day. */
    private fun memoryKey(profileId: String?, areaId: String, today: LocalDate): String = "${profileId.orEmpty()}:$areaId:$today"

    /**
     * The whole sequence for one area, as a function of a store, a day source and a clock -- no
     * Android, no socket, no wall clock of its own.
     */
    internal fun load(
        store: KeyValueStore,
        source: DaySource,
        area: PriceMarket?,
        today: LocalDate,
        nowMillis: Long,
        forceRefresh: Boolean = false,
        log: RelayLog = RelayLog.NONE,
        profileId: String? = null
    ): PriceResult {
        // 1. An id the catalogue does not have is not a fetch, it is a refusal: nothing is
        // requested for it, today or tomorrow.
        if (area == null) return emptyResult(nowMillis)

        val todayKey = today.toString()
        val memoryKey = memoryKey(profileId, area.id, today)
        val previous = cache[memoryKey]
        val age = previous?.let { nowMillis - it.fetchedAt } ?: Long.MAX_VALUE
        if (previous != null &&
            ((!forceRefresh && age < MEMORY_CACHE_MS) || (forceRefresh && age < FORCE_REFRESH_DEDUP_MS))
        ) {
            log.log(LogLevel.INFO, "Memory cache hit ${area.id} today=$todayKey")
            return previous
        }

        // 2 is the source's: its own schedule decides which days are worth a request, and a failure
        // is not fatal.
        val zone = area.zoneId
        val asked = Asked(source, today, forceRefresh)
        val todayPrices = displayDay(
            store, asked, area, today, previous?.today, previous?.todayFetchedAt, zone, nowMillis, log,
            requirePrincipal = false
        )
        // Tomorrow exists once its main market file is held, as Home Assistant and the web page say.
        val tomorrowPrices = displayDay(
            store, asked, area, today.plusDays(1),
            previous?.tomorrow, previous?.tomorrowFetchedAt, zone, nowMillis, log,
            requirePrincipal = true
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
        nowMillis: Long,
        profileId: String? = null
    ): PriceResult {
        if (area == null) return emptyResult(nowMillis)
        cache[memoryKey(profileId, area.id, today)]?.let { return it }
        return daysHeldIn(store, area, today, nowMillis)
    }

    /** The display days [store] holds for [area], composed from its market-day files; no memory, no request. */
    private fun daysHeldIn(store: KeyValueStore, area: PriceMarket, today: LocalDate, nowMillis: Long): PriceResult {
        val zone = area.zoneId
        val todayDay = heldDisplayDay(store, area, today, zone, requirePrincipal = false)
        val tomorrowDay = heldDisplayDay(store, area, today.plusDays(1), zone, requirePrincipal = true)
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

    /**
     * One display day, end to end: the market-day files that cover it, each asked for only when the
     * source's schedule says it is worth it and resolved against what is held, then cut to the display day. Each piece keeps
     * its own file's rate. With one calendar this is exactly one file, resolved as it always was.
     */
    private fun displayDay(
        store: KeyValueStore,
        asked: Asked,
        area: PriceMarket,
        date: LocalDate,
        memoryPoints: List<PricePoint>?,
        memoryFetchedAt: Long?,
        zone: ZoneId,
        nowMillis: Long,
        log: RelayLog,
        requirePrincipal: Boolean
    ): DayPrices {
        val keys = MarketDays.keysFor(date, area.tz, area.marketTz)
        if (keys.size == 1 && keys[0] == date) {
            return dayFor(store, asked, area, date, memoryPoints, memoryFetchedAt, zone, nowMillis, log)
        }
        val marketZone = area.marketZoneId
        val pieces = keys.associateWith { key ->
            val memory = memoryPoints?.let { MarketDays.within(it, key, marketZone) }
            dayFor(store, asked, area, key, memory, memoryFetchedAt, zone, nowMillis, log)
        }
        return compose(pieces, MarketDays.principal(date, area.tz, area.marketTz), date, zone, requirePrincipal)
    }

    /** A display day from the market-day [pieces] that cover it, named by its [principal] file. */
    private fun compose(
        pieces: Map<LocalDate, DayPrices>,
        principal: LocalDate,
        date: LocalDate,
        zone: ZoneId,
        requirePrincipal: Boolean
    ): DayPrices {
        val main = pieces[principal]
        if (requirePrincipal && main?.points.isNullOrEmpty()) return DayPrices(emptyList(), PriceSource.NONE, null)
        val points = MarketDays.cut(pieces.values.flatMap { it.points }, date, zone)
        if (points.isEmpty()) return DayPrices(emptyList(), PriceSource.NONE, null)
        val named = main?.takeIf { it.points.isNotEmpty() } ?: pieces.values.first { it.points.isNotEmpty() }
        return DayPrices(points, named.source, named.fetchedAt)
    }

    /** One display day from the disk alone, or `null` when nothing of it is held. */
    private fun heldDisplayDay(
        store: KeyValueStore,
        area: PriceMarket,
        date: LocalDate,
        zone: ZoneId,
        requirePrincipal: Boolean
    ): HeldPrices? {
        val keys = MarketDays.keysFor(date, area.tz, area.marketTz)
        if (keys.size == 1 && keys[0] == date) return readCachedDay(store, area, date.toString(), zone, RelayLog.NONE)
        val pieces = keys.associateWith { key ->
            readCachedDay(store, area, key.toString(), zone, RelayLog.NONE)
                ?.let { DayPrices(it.points, PriceSource.DISK, it.fetchedAt) }
                ?: DayPrices(emptyList(), PriceSource.NONE, null)
        }
        val composed = compose(pieces, MarketDays.principal(date, area.tz, area.marketTz), date, zone, requirePrincipal)
        return if (composed.points.isEmpty()) null else HeldPrices(composed.points, composed.fetchedAt)
    }

    /** One load's questions to its source: the source, the display day the load is for, and whether a person forced it. */
    private class Asked(val source: DaySource, val today: LocalDate, val force: Boolean)

    /** One market-day file, end to end: ask only when the source says it is worth it, then resolve against what is held. */
    private fun dayFor(
        store: KeyValueStore,
        asked: Asked,
        area: PriceMarket,
        marketDay: LocalDate,
        memoryPoints: List<PricePoint>?,
        memoryFetchedAt: Long?,
        zone: ZoneId,
        nowMillis: Long,
        log: RelayLog
    ): DayPrices {
        val dateKey = marketDay.toString()
        val memory = holdInMemory(memoryPoints, memoryFetchedAt)
        val disk = if (memory == null) readCachedDay(store, area, dateKey, zone, log) else null
        val worth = asked.source.worthAsking(
            area, marketDay, Instant.ofEpochMilli(nowMillis), asked.force, held = memory != null || disk != null
        )
        val outcome = if (!worth) FetchOutcome.NotListed else fetchDay(store, asked, area, marketDay, zone, nowMillis, log)
        return resolveDay(outcome, memory, disk, nowMillis)
    }

    private fun fetchDay(
        store: KeyValueStore,
        asked: Asked,
        area: PriceMarket,
        marketDay: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
        log: RelayLog
    ): FetchOutcome {
        val dateKey = marketDay.toString()
        val body = when (val fetched = asked.source.fetch(area, marketDay)) {
            is DayFetch.Body -> fetched.json
            DayFetch.NotPublished -> return FetchOutcome.NotListed
            is DayFetch.Failed -> {
                log.log(LogLevel.WARN, "No day document ${area.id} $dateKey: ${fetched.code}")
                return FetchOutcome.Failed
            }
        }
        return when (val parsed = RelayDayParser.parse(body, area.id, area.marketTz, area.currency, dateKey)) {
            is DayParse.Invalid -> {
                log.log(LogLevel.ERROR, "Invalid day document ${area.id} $dateKey: ${parsed.reason}")
                FetchOutcome.Failed
            }
            is DayParse.Ok -> {
                val points = RelayDayPoints.points(parsed.document, zone)
                if (points.isEmpty()) return FetchOutcome.Failed
                writeCachedDay(store, area.id, dateKey, body, nowMillis, asked.today)
                FetchOutcome.Body(points)
            }
        }
    }

    // Home Assistant's prices (paired chargers)

    /**
     * A paired charger's prices from its Home Assistant (design 2.3): the memory result while it is fresh,
     * else one `price_days` request when [ask] (else none), else the answer held from before. The display
     * days are cut from the answer's files on the **answer's** area -- its market calendar is Home
     * Assistant's provider's, not the list's -- and the result is kept in memory under [subject].
     */
    internal fun loadFromHomeAssistant(
        source: HaPriceDaysSource,
        subject: PriceSubject,
        subjectToday: LocalDate,
        nowMillis: Long,
        ask: Boolean,
        forceRefresh: Boolean = false,
        log: RelayLog = RelayLog.NONE
    ): PriceResult {
        val memoryKey = memoryKey(subject.profileId, subject.areaId, subjectToday)
        val previous = cache[memoryKey]
        val age = previous?.let { nowMillis - it.fetchedAt } ?: Long.MAX_VALUE
        if (previous != null &&
            ((!forceRefresh && age < MEMORY_CACHE_MS) || (forceRefresh && age < FORCE_REFRESH_DEDUP_MS))
        ) {
            log.log(LogLevel.INFO, "Memory cache hit ${subject.areaId} today=$subjectToday (Home Assistant)")
            return previous
        }
        val prices = if (ask) source.load(nowMillis) else source.held()
        // The answer's area stays known for an id the list lacks (a region only Home Assistant prices).
        prices?.let { PriceMarkets.learn(it.read.area) }
        val result = prices?.let { fromHomeAssistant(it, nowMillis) }
        if (result == null || (result.today.isEmpty() && result.tomorrow.isEmpty())) {
            log.log(LogLevel.WARN, "No prices from Home Assistant for ${subject.areaId} today=$subjectToday")
            return previous ?: emptyResult(nowMillis)
        }
        if (prices.read.area.id != subject.areaId) {
            log.log(LogLevel.INFO, "Home Assistant prices ${prices.read.area.id} for a charger set to ${subject.areaId}")
        }
        // Only an answer just fetched is kept in memory: a held one must not stand in for a request for ten minutes.
        if (prices.fresh) cache[memoryKey] = result
        return result
    }

    /** What a paired charger's Home Assistant answer holds for it, with nothing asked; memory first. */
    internal fun heldFromHomeAssistant(source: HaPriceDaysSource, subject: PriceSubject, subjectToday: LocalDate, nowMillis: Long): PriceResult {
        cache[memoryKey(subject.profileId, subject.areaId, subjectToday)]?.let { return it }
        val prices = source.held() ?: return emptyResult(nowMillis)
        PriceMarkets.learn(prices.read.area)
        return fromHomeAssistant(prices, nowMillis)
    }

    /**
     * Today and tomorrow, on the answer's own area and in its zone, cut from the answer's files exactly as
     * held days are: through [RelayDayParser] and [MarketDays]. Days of an answer fetched just now are
     * [PriceSource.FETCHED] ("fetched now"); a held answer's are [PriceSource.DISK] with its own stamp.
     */
    internal fun fromHomeAssistant(prices: HaPrices, nowMillis: Long): PriceResult {
        val area = prices.read.area
        val today = Instant.ofEpochMilli(nowMillis).atZone(area.zoneId).toLocalDate()
        val files = AnswerFiles()
        for ((date, text) in prices.read.files) {
            val key = dayKey(area.id, date.toString())
            files.putString(key, text)
            prices.fetchedAt?.let { files.putString("$key:at", it.toString()) }
        }
        val held = daysHeldIn(files, area, today, nowMillis)
        if (!prices.fresh) return held
        return held.copy(
            todaySource = if (held.today.isEmpty()) PriceSource.NONE else PriceSource.FETCHED,
            tomorrowSource = if (held.tomorrow.isEmpty()) PriceSource.NONE else PriceSource.FETCHED
        )
    }

    /** One answer's files, keyed as the day cache keys them, for the composition that reads that cache. */
    private class AnswerFiles : KeyValueStore {
        private val values = HashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    /** Whether tomorrow's main file is in a paired charger's held answer, on that answer's calendar. */
    internal fun holdsTomorrow(prices: HaPrices, nowMillis: Long): Boolean {
        val area = prices.read.area
        val today = Instant.ofEpochMilli(nowMillis).atZone(area.zoneId).toLocalDate()
        return MarketDays.principal(today.plusDays(1), area.tz, area.marketTz) in prices.read.files
    }

    // The on-disk day cache

    /** `v1:{area}:{date}`, inside a preferences file of its own. */
    private fun dayKey(areaId: String, dateKey: String): String = "$SCHEMA:$areaId:$dateKey"

    internal fun writeCachedDay(
        store: KeyValueStore,
        areaId: String,
        dateKey: String,
        body: String,
        fetchedAt: Long,
        today: LocalDate
    ) {
        store.putString(dayKey(areaId, dateKey), body)
        // When the source was actually reached for this day, so a disk-held day is reported with a
        // real time instead of "now" or "unknown".
        store.putString(dayKey(areaId, dateKey) + ":at", fetchedAt.toString())
        prune(store, today)
    }

    /**
     * Drop every held market day older than [KEEP_DAYS_BEFORE_TODAY] days before [today], in every
     * area: the app shows today and tomorrow only, and a display day reaches at most one market day
     * back. Without this the cache grows by a day file per area per day for the life of the install.
     */
    internal fun prune(store: KeyValueStore, today: LocalDate) {
        val oldest = today.minusDays(KEEP_DAYS_BEFORE_TODAY)
        for (key in store.keys()) {
            val date = DAY_KEY.matchEntire(key)?.groupValues?.get(1) ?: continue
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: continue
            if (day.isBefore(oldest)) store.remove(key)
        }
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
        val parsed = RelayDayParser.parse(body, area.id, area.marketTz, area.currency, dateKey)
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

    /**
     * One publication refresh: what it accepted for [subject], and whether that moved the area's
     * prices.
     */
    internal fun loadForPublication(context: Context, subject: PriceSubject): PublishedPrices {
        val before = held(subject)
        val result = load(context, subject, forceRefresh = true)
        return PublishedPrices(result, priceDocumentsChanged(before, result))
    }

    /** What the memory cache holds for a subject right now, or `null`. No I/O, no request. */
    private fun held(subject: PriceSubject): PriceResult? {
        val market = PriceMarkets.find(subject.areaId) ?: return null
        return cache[memoryKey(subject.profileId, market.id, LocalDate.now(market.zoneId))]
    }

    /** One price load for [subject], against the catalogue currently held, from the source [choose] names. */
    fun load(context: Context, subject: PriceSubject, forceRefresh: Boolean = false): PriceResult {
        val store = SharedPreferencesKeyValueStore(context, CACHE_PREFS)
        val market = PriceMarkets.find(subject.areaId)
        val zone = market?.zoneId ?: ZoneId.systemDefault()
        val chosen = choose(context, subject, market)
        // A paired charger's prices are its Home Assistant's: asked for when it answers `price_days`, else
        // only what it answered before. Never the relay, never ENTSO-E, never the standalone day cache.
        homeAssistantProfile(subject, chosen)?.let { profileId ->
            AndroidRelayLog.log(LogLevel.INFO, "Prices for ${subject.areaId} from Home Assistant: $chosen")
            return loadFromHomeAssistant(
                haSource(context, profileId), subject, LocalDate.now(zone), System.currentTimeMillis(),
                ask = chosen is ChosenSource.HomeAssistant, forceRefresh = forceRefresh, log = AndroidRelayLog
            )
        }
        val source = when (chosen) {
            ChosenSource.Entsoe -> entsoeSource(context)
            is ChosenSource.HomeAssistant, is ChosenSource.None -> {
                AndroidRelayLog.log(LogLevel.INFO, "No price source for ${subject.areaId}: $chosen; held days only")
                HeldOnlySource
            }
        }
        return load(
            store, source, market, LocalDate.now(zone), System.currentTimeMillis(), forceRefresh, AndroidRelayLog,
            subject.profileId
        )
    }

    /**
     * The source that prices [subject] now (design section 1). A paired charger is its Home Assistant's
     * when its last dashboard says it answers `price_days`, and has no source otherwise; a standalone phone
     * uses its own ENTSO-E key, else nothing. Never the relay. The screens say what is missing through
     * [missing].
     */
    internal fun choose(context: Context, subject: PriceSubject, market: PriceMarket?): ChosenSource {
        val profile = subject.profileId?.let { ChargerProfileStore.forContext(context).getProfile(it) }
        // A profile stored before the app read this capability: the widget's last dashboard may already say.
        val canPriceDays = profile?.let {
            it.priceDays ?: WidgetDashboardStore.forContext(context).dashboardFor(it.localId)?.dashboard?.capabilities?.priceDays
        }
        return PriceSourceChoice.choose(
            subject = subject,
            profileConfigured = profile != null,
            haCanPriceDays = canPriceDays,
            key = EntsoeKeyStore.forContext(context).state(),
            area = market
        )
    }

    /** The profile whose Home Assistant alone prices [subject] under [chosen], or `null` for a standalone subject. */
    private fun homeAssistantProfile(subject: PriceSubject, chosen: ChosenSource): String? = when (chosen) {
        is ChosenSource.HomeAssistant -> chosen.profileId
        is ChosenSource.None -> subject.profileId?.takeIf { chosen.reason.paired }
        else -> null
    }

    private fun haSource(context: Context, profileId: String): HaPriceDaysSource {
        val profiles = ChargerProfileStore.forContext(context)
        return HaPriceDaysSource(
            store = HaPriceDaysStore.forContext(context),
            profileId = profileId,
            fetch = {
                val profile = profiles.getProfile(profileId)
                // A charger waiting to be paired again is left to the dashboard's slow check: its prices are the held ones.
                if (profile == null || !profile.configured || profile.needsPairing) PriceDaysAnswer.Failed
                else HomeAssistantClient.priceDays(profile.toHomeAssistantSettings())
            },
            log = AndroidRelayLog
        )
    }


    /**
     * Why no source can price [subject] now, or `null` when one can: what the screens and the widget say
     * in place of a chart (step A8). Reads only what is stored; no request.
     */
    internal fun missing(context: Context, subject: PriceSubject): MissingSource? =
        (choose(context, subject, PriceMarkets.find(subject.areaId)) as? ChosenSource.None)?.reason

    /**
     * Forget every result held in memory: a key saved or removed changes which source prices a subject,
     * and the next load must ask that source rather than show the previous answer for ten minutes.
     */
    fun clearMemoryCache() {
        cache.clear()
    }

    /**
     * Check [key] with one ENTSO-E request for today's market day of [areaId] (or DE-LU when that area has
     * no route), before it is saved. Network; never on the main thread.
     */
    internal fun checkEntsoeKey(context: Context, key: EntsoeKey, areaId: String?): KeyCheck =
        EntsoeKeyStore.check(
            key, areaId?.let { PriceMarkets.find(it) }, PriceMarkets.find(KEY_CHECK_FALLBACK_AREA), entsoeClient(context), Instant.now()
        )

    /** The area a key is checked against when the phone's own area has no ENTSO-E route. */
    private const val KEY_CHECK_FALLBACK_AREA = "DE-LU"

    private fun entsoeLimiter(context: Context) = EntsoeLimiter(SharedPreferencesKeyValueStore(context, EntsoeLimiter.PREFS))

    private fun entsoeClient(context: Context, limiter: EntsoeLimiter = entsoeLimiter(context)) =
        EntsoeClient(PlainHttp, limiter, log = AndroidRelayLog)

    /** The ENTSO-E source over the app's own key, limiter state and ECB rates. */
    private fun entsoeSource(context: Context): DaySource {
        val keys = EntsoeKeyStore.forContext(context)
        val limiter = entsoeLimiter(context)
        return EntsoeDaySource(
            key = keys::key,
            client = entsoeClient(context, limiter),
            limiter = limiter,
            rates = EcbRates.forContext(context),
            log = AndroidRelayLog,
            onKeyRejected = keys::markRejected,
            onKeyAccepted = keys::markAccepted
        )
    }

    /** A source that asks for nothing: the repository shows what it holds. */
    private object HeldOnlySource : DaySource {
        override fun worthAsking(area: PriceMarket, marketDay: LocalDate, now: Instant, force: Boolean, held: Boolean) = false

        override fun fetch(area: PriceMarket, marketDay: LocalDate): DayFetch = DayFetch.Failed(PriceFailure.INVALID)
    }

    /** [heldOnly] for [subject], against the catalogue currently held: no request, ever. */
    fun heldOnly(context: Context, subject: PriceSubject): PriceResult {
        val store = SharedPreferencesKeyValueStore(context, CACHE_PREFS)
        val market = PriceMarkets.find(subject.areaId)
        val zone = market?.zoneId ?: ZoneId.systemDefault()
        pairedProfile(context, subject)?.let { profileId ->
            return heldFromHomeAssistant(haSource(context, profileId), subject, LocalDate.now(zone), System.currentTimeMillis())
        }
        return heldOnly(store, market, LocalDate.now(zone), System.currentTimeMillis(), subject.profileId)
    }

    /** [subject]'s profile when it is a paired charger's (its prices are Home Assistant's), else `null`. */
    private fun pairedProfile(context: Context, subject: PriceSubject): String? =
        subject.profileId?.takeIf { ChargerProfileStore.forContext(context).getProfile(it) != null }

    /**
     * Whether tomorrow is held for [subject]: for a paired charger, in its Home Assistant's held answer;
     * else tomorrow's main file in the day cache.
     */
    fun hasCachedTomorrow(context: Context, subject: PriceSubject): Boolean {
        val profileId = pairedProfile(context, subject) ?: return hasCachedTomorrow(context, subject.areaId)
        val held = haSource(context, profileId).held() ?: return false
        return holdsTomorrow(held, System.currentTimeMillis())
    }

    /**
     * When [subject]'s tomorrow is expected: a paired charger's from its Home Assistant's held answer (the
     * provider it uses now, Nord Pool 12:45 or ENTSO-E 13:00), else the list's for the area.
     */
    fun publicationFor(context: Context, subject: PriceSubject): AreaPublication? {
        val held = pairedProfile(context, subject)?.let { haSource(context, it).held() }
        return held?.read?.area?.publication ?: PriceMarkets.find(subject.areaId)?.publication
    }

    /**
     * Whether a usable day is held for tomorrow -- the scheduler's publication check: tomorrow's main
     * market file (see [MarketDays.principal]).
     */
    private fun hasCachedTomorrow(context: Context, areaId: String): Boolean {
        val market = PriceMarkets.find(areaId) ?: return false
        val store = SharedPreferencesKeyValueStore(context, CACHE_PREFS)
        return holdsTomorrow(store, market, LocalDate.now(market.zoneId), AndroidRelayLog)
    }

    /** Whether tomorrow's main market file (after [today] on the display calendar) is held. */
    internal fun holdsTomorrow(store: KeyValueStore, market: PriceMarket, today: LocalDate, log: RelayLog = RelayLog.NONE): Boolean {
        val principal = MarketDays.principal(today.plusDays(1), market.tz, market.marketTz)
        return readCachedDay(store, market, principal.toString(), market.zoneId, log) != null
    }
}
