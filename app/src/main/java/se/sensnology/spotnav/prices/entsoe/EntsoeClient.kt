package se.sensnology.spotnav.prices.entsoe

import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.LogLevel
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.prices.EntsoeRoute
import se.sensnology.spotnav.prices.HttpAnswer
import se.sensnology.spotnav.prices.HttpGet
import se.sensnology.spotnav.prices.PlainHttp
import se.sensnology.spotnav.prices.PriceFailure
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A person's ENTSO-E security token, wrapped so that its value cannot reach a log line, a message, a
 * crash text or a string template by accident: every rendering says `<redacted>`. Reading the value is
 * one deliberate call, made only where the request URL is built.
 */
internal class EntsoeKey(value: String) {
    private val secret = value.trim()

    /** Whether no key is held at all: a fact, safe to log. */
    val isEmpty: Boolean get() = secret.isEmpty()

    /** The value, for the request and for the encrypted store. Never for a message. */
    fun reveal(): String = secret

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean = other is EntsoeKey && other.secret == secret

    override fun hashCode(): Int = secret.hashCode()

    companion object {
        const val REDACTED = "<redacted>"
    }
}

/** What one request for one zone's market day came to. */
internal sealed interface EntsoeFetch {
    data class Fetched(val day: EntsoeParse.Day) : EntsoeFetch

    /** The platform has no document for that day yet. */
    data object Missing : EntsoeFetch

    /** [reason] is a fixed label with codes and numbers: never a body, a URL or the key. */
    data class Failed(val failure: PriceFailure, val reason: String, val retryAfterMs: Long? = null) : EntsoeFetch
}

/**
 * The one budget every ENTSO-E request passes through, process-wide: at least [MIN_INTERVAL_MS]
 * between two requests, and a penalty (a 429, a 5xx, a rate-limit acknowledgement) that holds every
 * request back until it has passed. The penalty is persisted in [store], so a killed process does not
 * forget it. Port of the relay's `Limiter` without its jitter (one phone, a handful of requests a day).
 */
internal class EntsoeLimiter(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    /** Until when a penalty holds every request back, or `null`. */
    fun notBefore(): Long? = store.getString(KEY_NOT_BEFORE)?.toLongOrNull()

    /** Whether a penalty is in force at [nowMillis]. */
    fun blocked(nowMillis: Long): Boolean = notBefore()?.let { nowMillis < it } == true

    /**
     * Wait for this request's slot, spaced from the previous one; `false` (and no wait) while a penalty
     * is in force, so a request is never sent into one.
     */
    fun acquire(): Boolean {
        val wait = synchronized(LOCK) {
            val now = clock()
            if (blocked(now)) return false
            val slot = maxOf(now, next)
            next = slot + MIN_INTERVAL_MS
            slot - now
        }
        if (wait > 0) sleep(wait)
        return true
    }

    /** Hold every request back for [durationMs], bounded by [MAX_BACKOFF_MS]; a longer penalty in force stands. */
    fun penalize(durationMs: Long) {
        if (durationMs <= 0) return
        synchronized(LOCK) {
            val until = clock() + minOf(durationMs, MAX_BACKOFF_MS)
            if (until > (notBefore() ?: 0L)) store.putString(KEY_NOT_BEFORE, until.toString())
        }
    }

    companion object {
        internal const val PREFS = "entsoe_state"
        internal const val KEY_NOT_BEFORE = "not_before"

        const val MIN_INTERVAL_MS = 250L
        const val RATE_LIMITED_BACKOFF_MS = 2L * 60L * 1000L
        const val SERVER_BACKOFF_MS = 30L * 1000L
        const val MAX_BACKOFF_MS = 15L * 60L * 1000L

        /** One schedule for the whole process, whichever limiter object a caller holds. */
        private val LOCK = Any()

        @Volatile
        private var next = 0L

        /** For tests: a fresh process's spacing. */
        internal fun resetSpacingForTest() {
            synchronized(LOCK) { next = 0L }
        }
    }
}

/**
 * The ENTSO-E Transparency Platform client: a port of the relay's `Entsoe.Fetch`
 * (`spotnav-relay/internal/source/entsoe.go`). The token-bearing URL exists only inside [fetch] and
 * goes to [http] and nowhere else; [http] logs nothing, this logs the area, the date, the status and a
 * failure's class name, and nothing it returns or logs quotes a response body.
 */
internal class EntsoeClient(
    private val http: HttpGet = PlainHttp,
    private val limiter: EntsoeLimiter,
    private val clock: () -> Instant = Instant::now,
    private val log: RelayLog = RelayLog.NONE,
    private val endpoint: String = ENDPOINT
) {
    /** The request URL with the key replaced by a placeholder: the only form that may be logged or shown. */
    fun loggableUrl(route: EntsoeRoute, zone: ZoneId, marketDay: LocalDate): String =
        url(route, zone, marketDay, EntsoeKey.REDACTED)

    /** One request for [route]'s market day [marketDay] in [zone], asked with [key]. */
    fun fetch(key: EntsoeKey, areaId: String, route: EntsoeRoute, zone: ZoneId, marketDay: LocalDate): EntsoeFetch {
        val what = "ENTSO-E $areaId $marketDay"
        if (key.isEmpty) return EntsoeFetch.Failed(PriceFailure.INVALID, "no key")
        if (!limiter.acquire()) {
            val wait = limiter.notBefore()?.let { it - clock().toEpochMilli() }?.takeIf { it > 0 }
            log.log(LogLevel.INFO, "$what: held back by an earlier rate limit")
            return EntsoeFetch.Failed(PriceFailure.RATE_LIMITED, "held back by an earlier rate limit", wait)
        }
        val answer = http.get(url(route, zone, marketDay, key.reveal()), "application/xml", TIMEOUT_MS, MAX_BODY)
        if (answer is HttpAnswer.NoAnswer) {
            log.log(LogLevel.WARN, "$what: no answer (${answer.kind})")
            return EntsoeFetch.Failed(PriceFailure.NETWORK, "no answer (${answer.kind})")
        }
        val response = answer as HttpAnswer.Response
        val status = response.status
        log.log(LogLevel.INFO, "$what: HTTP $status")
        when {
            status == 429 -> {
                val wait = retryAfterMs(response.retryAfter, clock())
                limiter.penalize(wait)
                return EntsoeFetch.Failed(PriceFailure.RATE_LIMITED, "rate limiting this key (HTTP 429)", minOf(wait, EntsoeLimiter.MAX_BACKOFF_MS))
            }
            // Before any body check, and on purpose: a rejected key is a failure whatever body came with it.
            status == 401 || status == 403 ->
                return EntsoeFetch.Failed(PriceFailure.ENTSOE_KEY_REJECTED, "rejected the key (HTTP $status)")
            status >= 500 -> {
                limiter.penalize(EntsoeLimiter.SERVER_BACKOFF_MS)
                return EntsoeFetch.Failed(PriceFailure.HTTP, "answered HTTP $status", EntsoeLimiter.SERVER_BACKOFF_MS)
            }
        }
        EntsoeAck.parse(response.body)?.let { ack ->
            val kind = ack.kind(status)
            val reason = "acknowledgement ${ack.reasonCode()}: ${kind.label}"
            return when (kind) {
                AckKind.NO_DATA -> EntsoeFetch.Missing
                AckKind.RATE_LIMIT -> {
                    limiter.penalize(EntsoeLimiter.RATE_LIMITED_BACKOFF_MS)
                    EntsoeFetch.Failed(PriceFailure.RATE_LIMITED, reason, EntsoeLimiter.RATE_LIMITED_BACKOFF_MS)
                }
                // A credential or permission complaint, whatever status it came with: only a person can fix it.
                AckKind.AUTH -> EntsoeFetch.Failed(PriceFailure.ENTSOE_KEY_REJECTED, reason)
                else -> EntsoeFetch.Failed(PriceFailure.HTTP, reason)
            }.also { log.log(LogLevel.INFO, "$what: $reason") }
        }
        if (status !in 200..299) return EntsoeFetch.Failed(PriceFailure.HTTP, "answered HTTP $status")
        return when (val parsed = EntsoeParser.parse(response.body, route, zone, marketDay)) {
            is EntsoeParse.Day -> EntsoeFetch.Fetched(parsed)
            EntsoeParse.NoData -> EntsoeFetch.Missing
            is EntsoeParse.Refused -> {
                log.log(LogLevel.WARN, "$what: refused (${parsed.reason})")
                EntsoeFetch.Failed(PriceFailure.INVALID, parsed.reason)
            }
        }
    }

    /** The request: the documented parameters, encoded and in sorted order, like the relay's `url.Values.Encode`. */
    private fun url(route: EntsoeRoute, zone: ZoneId, marketDay: LocalDate, token: String): String {
        val start = marketDay.atStartOfDay(zone)
        val end = marketDay.plusDays(1).atStartOfDay(zone)
        val query = sortedMapOf(
            "documentType" to "A44",
            // The day-ahead contract: without it the platform also answers with intraday auctions.
            "contract_MarketAgreement.type" to "A01",
            "periodStart" to period(start),
            "periodEnd" to period(end),
            "in_Domain" to route.eic,
            "out_Domain" to route.eic,
            "securityToken" to token
        )
        route.sequence?.trim()?.takeIf { it.isNotEmpty() }?.let {
            query["classificationSequence_AttributeInstanceComponent.position"] = it
        }
        return endpoint + "?" + query.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    }

    companion object {
        const val ENDPOINT = "https://web-api.tp.entsoe.eu/api"

        /** The platform is markedly slower around the day-ahead publication peak (relay `EntsoeRequestTimeout`). */
        const val TIMEOUT_MS = 30_000

        /** A day document is tens of kilobytes; anything far beyond is not this document type (relay `maxBody`). */
        const val MAX_BODY = 4 * 1024 * 1024

        private val PERIOD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        private fun period(at: ZonedDateTime): String = PERIOD.format(at.withZoneSameInstant(ZoneOffset.UTC))

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /**
         * A `Retry-After` hint: seconds or an HTTP date. A hint in the past, a non-positive one or one that
         * is neither is the default wait; the limiter bounds whatever comes back.
         */
        fun retryAfterMs(header: String?, now: Instant): Long {
            val value = header?.trim().orEmpty()
            if (value.isEmpty()) return EntsoeLimiter.RATE_LIMITED_BACKOFF_MS
            value.toLongOrNull()?.let { seconds ->
                return if (seconds <= 0) EntsoeLimiter.RATE_LIMITED_BACKOFF_MS else seconds * 1000L
            }
            val date = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            val wait = date?.toEpochMilli()?.minus(now.toEpochMilli())
            return if (wait != null && wait > 0) wait else EntsoeLimiter.RATE_LIMITED_BACKOFF_MS
        }
    }
}
