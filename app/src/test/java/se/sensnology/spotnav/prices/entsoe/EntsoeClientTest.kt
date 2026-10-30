package se.sensnology.spotnav.prices.entsoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.app.RelayLog
import se.sensnology.spotnav.prices.HttpAnswer
import se.sensnology.spotnav.prices.HttpGet
import se.sensnology.spotnav.prices.PlainHttp
import se.sensnology.spotnav.prices.PriceFailure
import se.sensnology.spotnav.testing.BundledAreas
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayUpstream
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The relay's client tests (`entsoe_http_test.go`, `entsoe_redact_test.go`, `ratelimit_test.go`), ported. */
class EntsoeClientTest {
    /** A sentinel key: if it appears anywhere it must not, the test says where, never what. */
    private val secret = "SENTINEL-KEY-0123456789abcdef"
    private val key = EntsoeKey(secret)

    private val store = FakeKeyValueStore()
    private var nowMillis = Instant.parse("2026-09-19T12:00:00Z").toEpochMilli()
    private val sleeps = mutableListOf<Long>()
    private val limiter = EntsoeLimiter(store, { nowMillis }, { sleeps += it; nowMillis += it })
    private val logged = mutableListOf<String>()
    private val log = RelayLog { level, message -> logged += "$level $message" }

    @Before
    fun freshProcess() {
        EntsoeLimiter.resetSpacingForTest()
    }

    /** A scripted platform: one answer for every request, and every URL it was asked. */
    private class Platform(var answer: HttpAnswer) : HttpGet {
        val urls = mutableListOf<String>()
        override fun get(url: String, accept: String, timeoutMs: Int, maxBytes: Int): HttpAnswer {
            urls += url
            return answer
        }
    }

    private fun client(platform: Platform) = EntsoeClient(platform, limiter, { Instant.ofEpochMilli(nowMillis) }, log)

    private fun fetch(platform: Platform, id: String = "DE-LU", date: String = "2026-09-19") =
        client(platform).fetch(key, id, BundledAreas.route(id), BundledAreas.zone(id), LocalDate.parse(date))

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val (name, value) = it.split('=', limit = 2)
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

    private fun answer(status: Int, body: String = "", retryAfter: String? = null) = HttpAnswer.Response(status, body, retryAfter)

    private val document = RelayUpstream.read("synthetic_a44_60m.xml")
    private val noData = RelayUpstream.read("synthetic_ack_999_no_data.xml")

    // the request

    @Test
    fun theRequestShapeIsTheDocumentedOne() {
        val platform = Platform(answer(200, document))
        fetch(platform)
        val sent = platform.urls.single()
        assertTrue(sent.startsWith("https://web-api.tp.entsoe.eu/api?"))
        val query = query(sent)
        assertEquals("A44", query["documentType"])
        assertEquals("A01", query["contract_MarketAgreement.type"])
        assertEquals("202609182200", query["periodStart"])
        assertEquals("202609192200", query["periodEnd"])
        assertEquals("10Y1001A1001A82H", query["in_Domain"])
        assertEquals("10Y1001A1001A82H", query["out_Domain"])
        assertEquals("DE-LU asks for its own sequence", "1", query["classificationSequence_AttributeInstanceComponent.position"])
        assertEquals(secret, query["securityToken"])

        // The loggable form is the request with that one value replaced, and nothing else.
        val loggable = client(platform).loggableUrl(BundledAreas.route("DE-LU"), BundledAreas.zone("DE-LU"), LocalDate.parse("2026-09-19"))
        assertEquals(sent.replace("securityToken=$secret", "securityToken=%3Credacted%3E"), loggable)
        assertFalse(loggable.contains(secret))
    }

    @Test
    fun theIntervalAskedForIsTheZonesOwnLocalDay() {
        for ((id, date, start, end) in listOf(
            listOf("DE-LU", "2026-03-29", "202603282300", "202603292200"),
            listOf("DE-LU", "2025-10-26", "202510252200", "202510262300"),
            listOf("EE", "2026-09-19", "202609182100", "202609192100"),
            listOf("PT", "2026-09-19", "202609182200", "202609192200"),
            listOf("FI", "2026-09-21", "202609202100", "202609212100"),
            listOf("GR", "2025-10-26", "202510252100", "202510262200")
        )) {
            val platform = Platform(answer(200, noData))
            fetch(platform, id, date)
            val query = query(platform.urls.single())
            assertEquals("$id $date", start, query["periodStart"])
            assertEquals("$id $date", end, query["periodEnd"])
        }
    }

    @Test
    fun aZoneWithoutAPolicyAsksForNoSequence() {
        val platform = Platform(answer(200, noData))
        fetch(platform, "PL", "2026-09-21")
        assertNull(query(platform.urls.single())["classificationSequence_AttributeInstanceComponent.position"])
    }

    @Test
    fun anEmptyKeyNeverReachesTheWire() {
        val platform = Platform(answer(200, document))
        val result = client(platform).fetch(EntsoeKey("  "), "DE-LU", BundledAreas.route("DE-LU"), BundledAreas.zone("DE-LU"), LocalDate.parse("2026-09-19"))
        assertTrue(result is EntsoeFetch.Failed)
        assertTrue(platform.urls.isEmpty())
    }

    // outcomes

    @Test
    fun statusesAndAcknowledgementsMapToOutcomes() {
        val otherReason = noData.replaceFirst("<code>999</code>", "<code>9999</code>")
        val auth = RelayUpstream.read("synthetic_ack_999_auth.xml")
        val cases = listOf(
            answer(200, document) to null,
            answer(200, noData) to "missing",
            answer(400, noData) to "missing",
            answer(400, otherReason) to PriceFailure.HTTP,
            answer(401, noData) to PriceFailure.ENTSOE_KEY_REJECTED,
            answer(403, noData) to PriceFailure.ENTSOE_KEY_REJECTED,
            answer(200, auth) to PriceFailure.ENTSOE_KEY_REJECTED,
            answer(429) to PriceFailure.RATE_LIMITED,
            answer(500) to PriceFailure.HTTP,
            answer(503) to PriceFailure.HTTP,
            answer(404, "<html>nginx</html>") to PriceFailure.HTTP,
            answer(200, "<html>maintenance</html>") to PriceFailure.INVALID,
            answer(200, document.replace("10Y1001A1001A82H", "10YPL-AREA-----S")) to PriceFailure.INVALID,
            HttpAnswer.NoAnswer("SocketTimeoutException") to PriceFailure.NETWORK
        )
        for ((reply, want) in cases) {
            EntsoeLimiter.resetSpacingForTest()
            store.remove(EntsoeLimiter.KEY_NOT_BEFORE)
            val result = fetch(Platform(reply))
            when (want) {
                null -> assertEquals(reply.toString(), 24, (result as EntsoeFetch.Fetched).day.rows.size)
                "missing" -> assertEquals(reply.toString(), EntsoeFetch.Missing, result)
                else -> assertEquals(reply.toString(), want, (result as EntsoeFetch.Failed).failure)
            }
            if (result is EntsoeFetch.Failed) assertFalse("the reason quotes the body", result.reason.contains("No matching data found"))
        }
    }

    @Test
    fun aPrePublicationPrefixIsMissingAndAMalformedPartialDayIsNot() {
        val nl = BundledAreas.route("NL")
        val prefix = """<Publication_MarketDocument><type>A44</type><TimeSeries><in_Domain.mRID>${nl.eic}</in_Domain.mRID>
            <out_Domain.mRID>${nl.eic}</out_Domain.mRID><currency_Unit.name>EUR</currency_Unit.name>
            <price_Measure_Unit.name>MWH</price_Measure_Unit.name><curveType>A01</curveType>
            <Period><timeInterval><start>2026-09-20T22:00Z</start><end>2026-09-20T23:00Z</end></timeInterval>
            <resolution>PT60M</resolution><Point><position>1</position><price.amount>42</price.amount></Point></Period>
            </TimeSeries></Publication_MarketDocument>"""
        assertEquals(EntsoeFetch.Missing, fetch(Platform(answer(200, prefix)), "NL", "2026-09-21"))
        val broken = prefix.replace("2026-09-20T23:00Z", "2026-09-21T00:00Z")
        assertEquals(PriceFailure.INVALID, (fetch(Platform(answer(200, broken)), "NL", "2026-09-21") as EntsoeFetch.Failed).failure)
    }

    // the limiter

    private fun waitAfter(platform: Platform): Long {
        val before = nowMillis
        sleeps.clear()
        fetch(platform)
        return nowMillis - before
    }

    @Test
    fun aRateLimitHintIsHonouredAndBoundedAndPersisted() {
        // Each hint is written against the clock at the moment it is sent.
        val httpDate = { seconds: Long ->
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(nowMillis).plusSeconds(seconds).atOffset(ZoneOffset.UTC))
        }
        for ((hint, want) in listOf<Pair<() -> String?, Long>>(
            { "7" } to 7_000L,
            { "3600" } to EntsoeLimiter.MAX_BACKOFF_MS,
            { httpDate(90) } to 90_000L,
            { httpDate(-3600) } to EntsoeLimiter.RATE_LIMITED_BACKOFF_MS,
            { null } to EntsoeLimiter.RATE_LIMITED_BACKOFF_MS,
            { "soon" } to EntsoeLimiter.RATE_LIMITED_BACKOFF_MS
        )) {
            val header = hint()
            store.remove(EntsoeLimiter.KEY_NOT_BEFORE)
            EntsoeLimiter.resetSpacingForTest()
            val start = nowMillis
            val result = fetch(Platform(answer(429, retryAfter = header))) as EntsoeFetch.Failed
            assertEquals(header.toString(), PriceFailure.RATE_LIMITED, result.failure)
            assertEquals(header.toString(), start + want, limiter.notBefore())
            assertEquals(header.toString(), want, result.retryAfterMs)

            // While the penalty holds nothing is sent, and a new limiter over the same store (a restarted
            // process) still knows it.
            val held = Platform(answer(200, document))
            val again = EntsoeClient(held, EntsoeLimiter(store, { nowMillis }), { Instant.ofEpochMilli(nowMillis) })
                .fetch(key, "DE-LU", BundledAreas.route("DE-LU"), BundledAreas.zone("DE-LU"), LocalDate.parse("2026-09-19"))
            assertEquals(PriceFailure.RATE_LIMITED, (again as EntsoeFetch.Failed).failure)
            assertTrue(held.urls.isEmpty())
            nowMillis = start + want
        }
    }

    @Test
    fun aServerErrorBacksOffBrieflyAndARejectedKeyDoesNot() {
        fetch(Platform(answer(503)))
        assertEquals(nowMillis + EntsoeLimiter.SERVER_BACKOFF_MS, limiter.notBefore())

        store.remove(EntsoeLimiter.KEY_NOT_BEFORE)
        fetch(Platform(answer(401)))
        assertNull("a rejected key is reported, not waited out", limiter.notBefore())
    }

    @Test
    fun anAcknowledgementRateLimitBacksTheKeyOff() {
        val rateLimited = noData.replaceFirst(
            "No matching data found for the requested document and interval.", "Too many requests for this token; slow down."
        )
        val result = fetch(Platform(answer(400, rateLimited))) as EntsoeFetch.Failed
        assertEquals(PriceFailure.RATE_LIMITED, result.failure)
        assertEquals(nowMillis + EntsoeLimiter.RATE_LIMITED_BACKOFF_MS, limiter.notBefore())
    }

    @Test
    fun requestsAreSpacedAndAShorterPenaltyNeverShortensALongerOne() {
        val platform = Platform(answer(200, noData))
        assertEquals("the first request is immediate", 0L, waitAfter(platform))
        assertEquals(EntsoeLimiter.MIN_INTERVAL_MS, waitAfter(platform))
        nowMillis += 10_000
        assertEquals("spacing is from the last request, not a queue", 0L, waitAfter(platform))

        limiter.penalize(60_000)
        val until = limiter.notBefore()
        limiter.penalize(1_000)
        assertEquals(until, limiter.notBefore())
        limiter.penalize(0)
        limiter.penalize(-5)
        assertEquals(until, limiter.notBefore())
    }

    // the key never leaves the request

    @Test
    fun noFailurePathLeavesTheKeyAnywhereButTheRequest() {
        val echo = { status: Int -> Platform(answer(status)) }
        val cases = listOf<Platform>(
            Platform(HttpAnswer.NoAnswer("ConnectException")),
            echo(500),
            echo(502),
            echo(401),
            echo(429),
            Platform(answer(200, "<html><body>maintenance</body></html>")),
            Platform(answer(200, document.replace("10Y1001A1001A82H", "10YPL-AREA-----S"))),
            Platform(answer(401, RelayUpstream.read("synthetic_ack_999_auth.xml"))),
            Platform(answer(200, noData)),
            Platform(answer(200, document))
        )
        for (platform in cases) {
            EntsoeLimiter.resetSpacingForTest()
            store.remove(EntsoeLimiter.KEY_NOT_BEFORE)
            // A body that echoes the request it was handed, key and all.
            val echoed = platform.answer
            if (echoed is HttpAnswer.Response && echoed.body.isEmpty()) {
                platform.answer = echoed.copy(body = "<html>upstream error for ${client(platform).loggableUrl(BundledAreas.route("DE-LU"), BundledAreas.zone("DE-LU"), LocalDate.parse("2026-09-19")).replace("%3Credacted%3E", secret)}</html>")
            }
            val result = fetch(platform)
            assertFalse("the result renders the key: $platform", result.toString().contains(secret))
            if (result is EntsoeFetch.Failed) assertFalse(result.reason.contains("upstream error"))
        }
        assertTrue(logged.isNotEmpty())
        logged.forEachIndexed { i, line -> assertFalse("log line $i holds the key", line.contains(secret)) }
        for (stored in store.keys()) assertFalse("stored value $stored holds the key", store.getString(stored)!!.contains(secret))
        assertEquals(EntsoeKey.REDACTED, key.toString())
        assertEquals(EntsoeKey.REDACTED, "$key")
    }

    @Test
    fun aTransportFailureCarriesOnlyTheFailuresClassName() {
        // A closed local port: HttpURLConnection's own exception would name the URL.
        val answer = PlainHttp.get("http://127.0.0.1:1/api?securityToken=$secret", "application/xml", 2_000, 1024)
        assertTrue(answer is HttpAnswer.NoAnswer)
        assertFalse(answer.toString().contains(secret))
        assertFalse((answer as HttpAnswer.NoAnswer).kind.contains("127.0.0.1"))
    }
}
