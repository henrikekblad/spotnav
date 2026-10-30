package se.sensnology.spotnav.prices

import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import java.time.Instant
import java.time.LocalDate

/**
 * What one source said about one market day. Every source hands the repository the same thing the
 * relay serves -- a `"v":1` day document as text -- so [RelayDayParser] stays the single validator.
 */
internal sealed interface DayFetch {
    /** A `"v":1` day document, not yet validated. */
    data class Body(val json: String) : DayFetch

    /** The source has no document for that day yet: an ordinary afternoon, not a failure. */
    data object NotPublished : DayFetch

    /** The request failed; [retryAfterMs] is how long the source asked to be left alone, when it said. */
    data class Failed(val code: PriceFailure, val retryAfterMs: Long? = null) : DayFetch
}

/** Why a day could not be fetched. A code, never a body, a URL or a key. */
internal enum class PriceFailure { NETWORK, HTTP, INVALID, ENTSOE_KEY_REJECTED, RATE_LIMITED, NO_RATE, HA_TOO_OLD }

/** Where the repository's market-day files come from: one source per price subject. */
internal interface DaySource {
    /**
     * Whether asking for [marketDay] now is worth a request. [held] says whether a usable file for
     * that day is already held (memory or disk); [force] is a person's own refresh.
     */
    fun worthAsking(area: PriceMarket, marketDay: LocalDate, now: Instant, force: Boolean, held: Boolean): Boolean

    /** One request for one market day. */
    fun fetch(area: PriceMarket, marketDay: LocalDate): DayFetch
}

/**
 * The relay's day files, asked for only when the relay's index lists them. The index is read once per
 * source, on the first question, and a failed read keeps the last valid one held in [store].
 */
internal class RelayDaySource(
    private val store: KeyValueStore,
    private val transport: RelayTransport,
    private val log: RelayLog = RelayLog.NONE,
    private val version: Int = RelayContractVersion.V1
) : DaySource {
    private val index: RelayIndex? by lazy { loadIndex() }

    override fun worthAsking(area: PriceMarket, marketDay: LocalDate, now: Instant, force: Boolean, held: Boolean): Boolean =
        index?.lists(area.id, marketDay.toString()) == true

    override fun fetch(area: PriceMarket, marketDay: LocalDate): DayFetch =
        transport.day(area.id, marketDay.toString())?.let(DayFetch::Body) ?: DayFetch.Failed(PriceFailure.NETWORK)

    /** The index in the catalogue's contract [version], revalidated against the relay. */
    private fun loadIndex(): RelayIndex? {
        val held = heldIndex(store, version)
        val body = transport.index(version) ?: return held
        val parsed = RelayIndexParser.parse(body, version)
        if (parsed == null) {
            log.log(LogLevel.ERROR, "Malformed index; keeping the last valid one")
            return held
        }
        store.putString(indexKey(version), body)
        return parsed
    }

    companion object {
        fun indexKey(version: Int): String =
            if (version == RelayContractVersion.V2) PriceRepository.INDEX_V2_KEY else PriceRepository.INDEX_KEY

        private fun heldIndex(store: KeyValueStore, version: Int): RelayIndex? =
            store.getString(indexKey(version))?.let { RelayIndexParser.parse(it, version) }
    }
}
