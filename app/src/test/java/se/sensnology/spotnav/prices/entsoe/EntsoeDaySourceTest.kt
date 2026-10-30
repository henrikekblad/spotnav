package se.sensnology.spotnav.prices.entsoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.prices.DayFetch
import se.sensnology.spotnav.prices.EcbRates
import se.sensnology.spotnav.prices.HttpAnswer
import se.sensnology.spotnav.prices.HttpGet
import se.sensnology.spotnav.prices.PriceFailure
import se.sensnology.spotnav.prices.PriceRepository
import se.sensnology.spotnav.prices.PriceSource
import se.sensnology.spotnav.testing.BundledAreas
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayUpstream
import java.time.Instant
import java.time.LocalDate

/** The ENTSO-E day source: the schedule authority, the rate, and the whole path through the repository. */
class EntsoeDaySourceTest {
    private val cache = FakeKeyValueStore()
    private val state = FakeKeyValueStore()
    private val ecbStore = FakeKeyValueStore()
    private var now = Instant.parse("2026-09-21T08:00:00Z")
    private var rejected = 0

    @Before
    fun freshProcess() {
        PriceRepository.clearMemoryCacheForTest()
        EntsoeDaySource.forgetNotPublishedForTest()
        EntsoeLimiter.resetSpacingForTest()
    }

    /** One fake for both upstreams: ENTSO-E by the requested day, the ECB by its address. */
    private class Upstream : HttpGet {
        val entsoe = mutableMapOf<String, HttpAnswer>()
        var ecb: HttpAnswer = HttpAnswer.Response(200, RelayUpstream.read("ecb_daily.xml"))
        val asked = mutableListOf<String>()

        override fun get(url: String, accept: String, timeoutMs: Int, maxBytes: Int): HttpAnswer {
            if (url == EcbRates.DAILY_URL) {
                asked += "ecb"
                return ecb
            }
            val start = Regex("periodStart=(\\d{8})").find(url)!!.groupValues[1]
            asked += "entsoe:$start"
            return entsoe[start] ?: HttpAnswer.Response(200, RelayUpstream.read("synthetic_ack_999_no_data.xml"))
        }
    }

    private val upstream = Upstream()
    private val limiter = EntsoeLimiter(state, { now.toEpochMilli() }, {})

    private fun source(key: EntsoeKey? = EntsoeKey("SENTINEL-KEY-0123456789abcdef")) = EntsoeDaySource(
        key = { key },
        client = EntsoeClient(upstream, limiter, { now }),
        limiter = limiter,
        rates = EcbRates(ecbStore, upstream),
        clock = { now },
        onKeyRejected = { rejected++ }
    )

    private val se4 = BundledAreas.area("SE4")
    private val nl = BundledAreas.area("NL")
    private val today = LocalDate.parse("2026-09-21")
    private val tomorrow = today.plusDays(1)

    // the schedule authority

    @Test
    fun heldDaysAndPenaltiesAreNeverAskedFor() {
        val source = source()
        assertTrue(source.worthAsking(se4, today, now, force = false, held = false))
        assertFalse(source.worthAsking(se4, today, now, force = true, held = true))
        limiter.penalize(60_000)
        assertFalse(source.worthAsking(se4, today, now, force = true, held = false))
    }

    @Test
    fun tomorrowIsNotAskedForBeforeItsPublication() {
        val source = source()
        // 13:00 Brussels is 11:00 UTC in September; ten minutes early is 10:50.
        assertFalse(source.worthAsking(se4, tomorrow, Instant.parse("2026-09-21T10:49:59Z"), force = true, held = false))
        assertTrue(source.worthAsking(se4, tomorrow, Instant.parse("2026-09-21T10:50:00Z"), force = false, held = false))
    }

    @Test
    fun aDayJustSaidToBeUnpublishedRestsFiveMinutesUnlessAPersonAsks() {
        val source = source()
        now = Instant.parse("2026-09-21T11:00:00Z")
        assertEquals(DayFetch.NotPublished, source.fetch(se4, tomorrow))
        now = now.plusSeconds(299)
        assertFalse(source.worthAsking(se4, tomorrow, now, force = false, held = false))
        assertTrue(source.worthAsking(se4, tomorrow, now, force = true, held = false))
        now = now.plusSeconds(1)
        assertTrue(source.worthAsking(se4, tomorrow, now, force = false, held = false))
    }

    @Test
    fun anAreaWithoutARouteIsNeverAskedFor() {
        assertFalse(source().worthAsking(se4.copy(entsoe = null), today, now, force = true, held = false))
    }

    // fetching

    @Test
    fun aNonEurDayIsBuiltWithTheEcbRate() {
        upstream.entsoe["20260920"] = HttpAnswer.Response(200, RelayUpstream.read("entsoe_SE4_2026-09-21.xml"))
        val body = (source().fetch(se4, today) as DayFetch.Body).json
        assertTrue(body.contains("\"fx\":{\"SEK\":11.2915},\"fx_date\":\"2026-09-18\",\"fx_src\":\"ECB\""))
        assertEquals(listOf("entsoe:20260920", "ecb"), upstream.asked)
    }

    @Test
    fun noRateIsNoDocument() {
        upstream.entsoe["20260920"] = HttpAnswer.Response(200, RelayUpstream.read("entsoe_SE4_2026-09-21.xml"))
        upstream.ecb = HttpAnswer.NoAnswer("UnknownHostException")
        assertEquals(DayFetch.Failed(PriceFailure.NO_RATE), source().fetch(se4, today))
    }

    @Test
    fun aRejectedKeyIsReported() {
        upstream.entsoe["20260920"] = HttpAnswer.Response(401, "")
        assertEquals(PriceFailure.ENTSOE_KEY_REJECTED, (source().fetch(se4, today) as DayFetch.Failed).code)
        assertEquals(1, rejected)
    }

    @Test
    fun noKeyIsNoRequest() {
        assertTrue(source(key = null).fetch(se4, today) is DayFetch.Failed)
        assertTrue(upstream.asked.isEmpty())
    }

    // through the repository

    private fun load(force: Boolean = false) =
        PriceRepository.load(cache, source(), nl, today, now.toEpochMilli(), force)

    @Test
    fun theMorningAsksForTodayOnlyAndThenNothingAgain() {
        upstream.entsoe["20260920"] = HttpAnswer.Response(200, RelayUpstream.read("entsoe_NL_2026-09-21.xml"))

        val first = load()
        assertEquals(96, first.today.size)
        assertEquals(PriceSource.RELAY, first.todaySource)
        assertTrue(first.tomorrow.isEmpty())
        assertEquals("no request for tomorrow before its publication", listOf("entsoe:20260920"), upstream.asked)

        PriceRepository.clearMemoryCacheForTest()
        val again = load(force = true)
        assertEquals(96, again.today.size)
        assertEquals(PriceSource.DISK, again.todaySource)
        assertEquals("a held day is never asked for again", listOf("entsoe:20260920"), upstream.asked)
    }

    @Test
    fun theAfternoonAsksForTomorrowOnceThenRests() {
        upstream.entsoe["20260920"] = HttpAnswer.Response(200, RelayUpstream.read("entsoe_NL_2026-09-21.xml"))
        now = Instant.parse("2026-09-21T11:05:00Z")
        load()
        assertEquals(listOf("entsoe:20260920", "entsoe:20260921"), upstream.asked)

        PriceRepository.clearMemoryCacheForTest()
        now = now.plusSeconds(60)
        load()
        assertEquals("tomorrow said 'not yet' a minute ago", 2, upstream.asked.size)

        PriceRepository.clearMemoryCacheForTest()
        now = now.plusSeconds(300)
        load()
        assertEquals(listOf("entsoe:20260920", "entsoe:20260921", "entsoe:20260921"), upstream.asked)
    }

    @Test
    fun aFileHeldFromTheRelayIsReadWithNoRequest() {
        // An upgrading phone: the relay's own SE4 file, in the cache under the same key.
        cache.putString("v1:SE4:2026-09-21", RelayUpstream.golden("v1_day_SE4.json"))
        val result = PriceRepository.load(cache, source(), se4, today, now.toEpochMilli())
        assertEquals(96, result.today.size)
        assertEquals(PriceSource.DISK, result.todaySource)
        assertTrue(upstream.asked.isEmpty())
    }

    @Test
    fun portugalIsComposedFromMadridDays() {
        val pt = BundledAreas.area("PT")
        now = Instant.parse("2026-09-21T15:00:00Z")
        val result = PriceRepository.load(cache, source(), pt, LocalDate.parse("2026-09-21"), now.toEpochMilli())
        // Lisbon's day reaches into two Madrid market days: both are asked for.
        assertEquals(listOf("entsoe:20260920", "entsoe:20260921"), upstream.asked.distinct().sorted())
        assertTrue(result.today.isEmpty())
    }
}
