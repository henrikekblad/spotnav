package se.sensnology.spotnav.prices

import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.ha.client.PriceDaysAnswer
import se.sensnology.spotnav.ha.client.PriceDaysDecoder
import se.sensnology.spotnav.ha.client.PriceDaysRead

/** One paired charger's prices from Home Assistant: what it answered, when, and whether that was just now. */
internal data class HaPrices(val read: PriceDaysRead, val fetchedAt: Long?, val fresh: Boolean)

/**
 * A paired charger's prices, from its own Home Assistant and nowhere else (design 2.3): one `price_days`
 * request, its whole answer kept in [store] per profile; when the request fails for any reason (no
 * network, a refusal, a status, an unreadable body, a webhook gone) the answer held from before, else
 * nothing. Never another source: two sources for one charger would show two rates and two truths about
 * "now".
 */
internal class HaPriceDaysSource(
    private val store: HaPriceDaysStore,
    private val profileId: String,
    private val fetch: () -> PriceDaysAnswer,
    private val log: RelayLog = RelayLog.NONE
) {
    /** One request; a good answer replaces the held one and is returned as fetched at [nowMillis]. */
    fun load(nowMillis: Long): HaPrices? {
        when (val answer = fetch()) {
            is PriceDaysAnswer.Answered -> {
                store.put(profileId, answer.body, nowMillis)
                return HaPrices(answer.read, nowMillis, fresh = true)
            }
            is PriceDaysAnswer.Refused -> log.log(LogLevel.WARN, "Home Assistant has no prices for the app: ${answer.code}")
            PriceDaysAnswer.Failed -> log.log(LogLevel.WARN, "No price_days answer; the held one stands")
        }
        return held()
    }

    /** The answer held from before, read back through the same decoder; no request. */
    fun held(): HaPrices? {
        val held = store.held(profileId) ?: return null
        val read = PriceDaysDecoder.decode(held.body)
        if (read == null) {
            log.log(LogLevel.WARN, "Discarding an unreadable held price_days answer")
            return null
        }
        return HaPrices(read, held.fetchedAt, fresh = false)
    }
}
