package se.sensnology.spotnav.prices

import java.time.Instant
import java.time.LocalDate

/**
 * What one source said about one market day. Every source hands the repository the same thing the
 * relay once served -- a `"v":1` day document as text -- so [RelayDayParser] stays the single validator.
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
     * Whether asking for [marketDay] now is worth a request (the source's own schedule). [held] says whether a usable file for
     * that day is already held (memory or disk); [force] is a person's own refresh.
     */
    fun worthAsking(area: PriceMarket, marketDay: LocalDate, now: Instant, force: Boolean, held: Boolean): Boolean

    /** One request for one market day. */
    fun fetch(area: PriceMarket, marketDay: LocalDate): DayFetch
}
