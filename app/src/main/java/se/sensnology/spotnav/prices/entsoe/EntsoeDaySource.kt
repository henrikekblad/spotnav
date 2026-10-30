package se.sensnology.spotnav.prices.entsoe

import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.prices.DayBuild
import se.sensnology.spotnav.prices.DayDocumentBuilder
import se.sensnology.spotnav.prices.DayFetch
import se.sensnology.spotnav.prices.DaySource
import se.sensnology.spotnav.prices.EcbRates
import se.sensnology.spotnav.prices.PriceFailure
import se.sensnology.spotnav.prices.PriceMarket
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Day files from ENTSO-E with the person's own key, built into the relay's day document on the phone.
 *
 * The market day asked for is the area's market calendar (`market_tz`, else `tz`), exactly the relay's,
 * so the files already held from the relay stay valid. Without an index, [worthAsking] is the schedule
 * authority: a held file is never asked for again; nothing is asked while a penalty holds; tomorrow's file
 * not before ten minutes ahead of its expected publication; a day the platform just said is not published
 * is left alone for five minutes unless a person asked.
 */
internal class EntsoeDaySource(
    private val key: () -> EntsoeKey?,
    private val client: EntsoeClient,
    private val limiter: EntsoeLimiter,
    private val rates: EcbRates,
    private val clock: () -> Instant = Instant::now,
    private val log: RelayLog = RelayLog.NONE,
    /** Told when ENTSO-E rejects the key, so the key's state can say so. */
    private val onKeyRejected: () -> Unit = {}
) : DaySource {
    override fun worthAsking(area: PriceMarket, marketDay: LocalDate, now: Instant, force: Boolean, held: Boolean): Boolean {
        if (area.entsoe == null || held) return false
        if (limiter.blocked(now.toEpochMilli())) return false
        // A day after today's is published the afternoon before it.
        val expected = area.publication.on(marketDay.minusDays(1))
        if (now.isBefore(expected.minus(EARLY))) return false
        if (!force) {
            val missing = notPublished[noteKey(area, marketDay)]
            if (missing != null && Duration.between(missing, now) < QUIET) return false
        }
        return true
    }

    override fun fetch(area: PriceMarket, marketDay: LocalDate): DayFetch {
        val route = area.entsoe ?: return DayFetch.Failed(PriceFailure.INVALID)
        val held = key()?.takeUnless { it.isEmpty } ?: return DayFetch.Failed(PriceFailure.INVALID)
        return when (val fetched = client.fetch(held, area.id, route, area.marketZoneId, marketDay)) {
            EntsoeFetch.Missing -> {
                notPublished[noteKey(area, marketDay)] = clock()
                DayFetch.NotPublished
            }
            is EntsoeFetch.Failed -> {
                if (fetched.failure == PriceFailure.ENTSOE_KEY_REJECTED) onKeyRejected()
                DayFetch.Failed(fetched.failure, fetched.retryAfterMs)
            }
            is EntsoeFetch.Fetched -> {
                notPublished.remove(noteKey(area, marketDay))
                build(area, marketDay, fetched.day)
            }
        }
    }

    private fun build(area: PriceMarket, marketDay: LocalDate, day: EntsoeParse.Day): DayFetch {
        val now = clock()
        val fx = if (area.currency == EUR) null else rates.rateFor(area.currency, marketDay, now)
        return when (val built = DayDocumentBuilder.build(area, marketDay, day, fx, now)) {
            is DayBuild.Built -> DayFetch.Body(built.json)
            DayBuild.NoRate -> {
                log.log(LogLevel.WARN, "ENTSO-E ${area.id} $marketDay: no ECB ${area.currency} rate; the day is not built")
                DayFetch.Failed(PriceFailure.NO_RATE)
            }
            is DayBuild.Invalid -> {
                log.log(LogLevel.ERROR, "ENTSO-E ${area.id} $marketDay: ${built.reason}")
                DayFetch.Failed(PriceFailure.INVALID)
            }
        }
    }

    companion object {
        private const val EUR = "EUR"

        /** How long before the expected publication the next day is first asked for. */
        val EARLY: Duration = Duration.ofMinutes(10)

        /** How long a "not published yet" is believed before the day is asked for again. */
        val QUIET: Duration = Duration.ofMinutes(5)

        /** The last "not published yet" per area and market day, process-wide. */
        private val notPublished = ConcurrentHashMap<String, Instant>()

        private fun noteKey(area: PriceMarket, marketDay: LocalDate) = "${area.id}:$marketDay"

        /** For tests: a fresh process. */
        internal fun forgetNotPublishedForTest() = notPublished.clear()
    }
}
