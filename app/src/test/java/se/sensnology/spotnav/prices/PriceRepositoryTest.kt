package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.FakeDaySource
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.TEST_TODAY

/**
 * The price path over a day source: which days are requested, when a held day survives, and what a day
 * document means once it is parsed.
 */
class PriceRepositoryTest {
    private val store = FakeKeyValueStore()

    @org.junit.Before
    fun resetMemoryCache() {
        // The repository's result cache is process-wide; without this, one test would answer the
        // next one's request.
        PriceRepository.clearMemoryCacheForTest()
    }
    private val todayKey = "2026-09-20"
    private val tomorrowKey = "2026-09-21"
    private val todayDay = "SE4:2026-09-20"
    private val tomorrowDay = "SE4:2026-09-21"

    private fun load(
        area: PriceMarket?,
        source: DaySource,
        nowMillis: Long = 1_000_000L,
        force: Boolean = false
    ) = PriceRepository.load(store, source, area, TEST_TODAY, nowMillis, force)

    private fun day(area: PriceMarket, date: String, count: Int, start: String, res: Int = 15) =
        RelayFixtures.dayBody(area, date, RelayFixtures.prices(count), start, res)

    // the source decides what is worth a request

    @Test
    fun tomorrowTheSourceDoesNotOfferIsNeverRequested() {
        val source = FakeDaySource(
            days = mapOf("SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"))
        )

        val result = load(RelayFixtures.se4, source)

        assertEquals(listOf(todayDay), source.dayRequests)
        assertEquals(96, result.today.size)
        assertTrue(result.tomorrow.isEmpty())
        assertEquals(PriceSource.NONE, result.tomorrowSource)
    }

    @Test
    fun tomorrowOfferedIsRequested() {
        val source = FakeDaySource(
            days = mapOf(
                "SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"),
                "SE4:$tomorrowKey" to day(RelayFixtures.se4, tomorrowKey, 96, "2026-09-21T00:00:00+02:00")
            )
        )

        val result = load(RelayFixtures.se4, source)

        assertEquals(listOf(todayDay, tomorrowDay), source.dayRequests.sorted())
        assertEquals(96, result.tomorrow.size)
        assertEquals(PriceSource.FETCHED, result.tomorrowSource)
    }

    @Test
    fun anAreaTheSourceDoesNotOfferIsNotRequestedAtAll() {
        val source = FakeDaySource(
            listed = setOf("NO1:$todayKey"),
            days = mapOf("SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"))
        )

        val result = load(RelayFixtures.se4, source)

        assertTrue(source.dayRequests.isEmpty())
        assertTrue(result.today.isEmpty())
    }

    @Test
    fun anUnknownAreaIsAnsweredWithoutTouchingTheNetwork() {
        val source = FakeDaySource(listed = setOf(todayDay))

        val result = load(null, source)

        assertTrue(source.questions.isEmpty())
        assertTrue(source.dayRequests.isEmpty())
        assertTrue(result.today.isEmpty())
    }

    @Test
    fun aFailedRequestKeepsTheHeldDayAndSaysSo() {
        val good = FakeDaySource(
            days = mapOf("SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"))
        )
        assertEquals(96, load(RelayFixtures.se4, good).today.size)

        // The source fails. Nothing may be erased.
        val dead = FakeDaySource(days = mapOf("SE4:$todayKey" to "unused"), failDays = true)
        val result = load(RelayFixtures.se4, dead, nowMillis = 1_000_000L + 60_000L, force = true)

        assertEquals(96, result.today.size)
        assertTrue("held prices must not be presented as freshly fetched", result.todaySource != PriceSource.FETCHED)
        // The URLs it asked for may have been tried; what matters is what it kept.
        assertTrue(result.todayFetchedAt != null)
    }

    @Test
    fun anOldProviderBodyInTheCacheIsNotParsedAsARelayDocument() {
        // The old upstream shape, at the key a relay day would live at.
        store.setRaw(
            "v1:SE4:$todayKey",
            "[{\"time_start\":\"2026-09-20T00:00:00+02:00\",\"SEK_per_kWh\":1.25}]"
        )
        val result = load(RelayFixtures.se4, FakeDaySource())

        assertTrue(result.today.isEmpty())
    }

    // the day document, parsed and converted

    private fun parseDay(body: String, area: PriceMarket = RelayFixtures.se4, date: String = "2026-09-20"): DayParse =
        RelayDayParser.parse(body, area.id, area.tz, area.currency, date)

    private fun pointsFor(body: String, area: PriceMarket = RelayFixtures.se4, date: String = "2026-09-20"): List<PricePoint> {
        val parsed = parseDay(body, area, date)
        assertTrue("expected a valid document, got $parsed", parsed is DayParse.Ok)
        return RelayDayPoints.points((parsed as DayParse.Ok).document, area.zoneId)
    }

    @Test
    fun sekIsConvertedFromEurWithTheMatchingIsoKey() {
        val body = RelayFixtures.dayBody(
            RelayFixtures.se4, "2026-09-20", listOf(0.00162),
            "2026-09-20T00:00:00+02:00", fxRate = 11.2915
        )

        val points = pointsFor(body)

        assertEquals(0.00162 * 11.2915, points.first().pricePerKwh, 1e-12)
    }

    @Test
    fun norwegianPricesUseTheNokRateAndNotTheSwedishOne() {
        val body = RelayFixtures.dayBody(
            RelayFixtures.no1, "2026-09-20", listOf(0.01037),
            "2026-09-20T00:00:00+02:00", fxRate = 10.8095, fxCurrency = "NOK"
        )

        val points = pointsFor(body, RelayFixtures.no1)

        assertEquals(0.01037 * 10.8095, points.first().pricePerKwh, 1e-12)
    }

    @Test
    fun eurNeedsNoFxObjectAtAll() {
        val body = RelayFixtures.dayBody(
            RelayFixtures.fi, "2026-09-20", listOf(0.05),
            "2026-09-20T00:00:00+03:00", fxRate = null
        )

        val points = pointsFor(body, RelayFixtures.fi)

        assertEquals(0.05, points.first().pricePerKwh, 1e-12)
    }

    @Test
    fun aNonEurAreaWithNoUsableRateIsRejected() {
        for (rate in listOf(null, 0.0, -1.0)) {
            val body = RelayFixtures.dayBody(
                RelayFixtures.se4, "2026-09-20", listOf(0.01),
                "2026-09-20T00:00:00+02:00", fxRate = rate
            )
            val parsed = parseDay(body)
            assertTrue("rate=$rate must be refused, got $parsed", parsed is DayParse.Invalid)
        }
    }

    @Test
    fun aDocumentThatDisagreesWithTheRequestOrTheCatalogueIsRejected() {
        fun body(tz: String = RelayFixtures.se4.tz, unit: String = "EUR/kWh", date: String = "2026-09-20", id: String = "SE4") =
            RelayFixtures.dayBody(
                RelayFixtures.se4, "2026-09-20", listOf(0.01), "2026-09-20T00:00:00+02:00",
                tz = tz, unit = unit, dateField = date, idField = id
            )

        assertTrue(parseDay(body(id = "NO1")) is DayParse.Invalid)
        assertTrue(parseDay(body(date = "2026-09-19")) is DayParse.Invalid)
        assertTrue(parseDay(body(tz = "Europe/Oslo")) is DayParse.Invalid)
        assertTrue(parseDay(body(unit = "SEK/kWh")) is DayParse.Invalid)
        // Contract v2 allows any whole divisor of an hour; this client plans 15, 30 and 60.
        assertTrue(parseDay(RelayFixtures.dayBody(RelayFixtures.se4, "2026-09-20", listOf(0.01), "2026-09-20T00:00:00+02:00", res = 20)) is DayParse.Invalid)
        assertTrue(parseDay(RelayFixtures.dayBody(RelayFixtures.se4, "2026-09-20", listOf(0.01), "2026-09-20T00:00:00+02:00", res = 30)) is DayParse.Ok)
        assertTrue(parseDay(RelayFixtures.dayBody(RelayFixtures.se4, "2026-09-20", emptyList(), "2026-09-20T00:00:00+02:00")) is DayParse.Invalid)
        assertTrue(parseDay("not json at all") is DayParse.Invalid)
        assertTrue(parseDay("{\"v\":2}") is DayParse.Invalid)
    }

    @Test
    fun anOldProviderBodyIsRejectedByTheNewParser() {
        val providerBody = "[{\"time_start\":\"2026-09-20T00:00:00+02:00\",\"SEK_per_kWh\":1.25}]"

        assertTrue(parseDay(providerBody) is DayParse.Invalid)
    }

    @Test
    fun hourlyRowsExpandIntoQuarterHourPoints() {
        val hourly = RelayFixtures.dayBody(
            RelayFixtures.no1, "2026-09-20", RelayFixtures.prices(24),
            "2026-09-20T00:00:00+02:00", res = 60
        )

        val points = pointsFor(hourly, RelayFixtures.no1)

        assertEquals(96, points.size)
        // The first four quarter-hours all carry the first hour's own price.
        val first = points.first().pricePerKwh
        assertEquals(first, points[1].pricePerKwh, 0.0)
        assertEquals(first, points[3].pricePerKwh, 0.0)
        assertEquals(15L, java.time.Duration.between(points[0].start, points[1].start).toMinutes())
    }

    @Test
    fun aSpringClockChangeDayHas92QuarterHours() {
        val date = "2026-03-29"
        val body = RelayFixtures.dayBody(
            RelayFixtures.se4, date, RelayFixtures.prices(92),
            "2026-03-29T00:00:00+01:00", res = 15, dateField = date
        )

        val points = pointsFor(body, date = date)

        assertEquals(92, points.size)
        // The absolute sequence is continuous: 92 points span 91 quarter-hour gaps, with no hole
        // where the hour was removed.
        assertEquals(
            91L,
            java.time.Duration.between(points.first().start, points.last().start).toMinutes() / 15
        )
    }

    @Test
    fun anAutumnClockChangeDayHas100QuarterHours() {
        val date = "2026-10-25"
        val body = RelayFixtures.dayBody(
            RelayFixtures.se4, date, RelayFixtures.prices(100),
            "2026-10-25T00:00:00+02:00", res = 15, dateField = date
        )

        val points = pointsFor(body, date = date)

        assertEquals(100, points.size)
        // Two consecutive points around the repeated hour must still be 15 minutes apart in
        // absolute time, which is what "the start instant plus n x res" guarantees and a local-time
        // reconstruction does not.
        val gaps = points.zipWithNext { a, b ->
            java.time.Duration.between(a.start, b.start).toMinutes()
        }.toSet()
        assertEquals(setOf(15L), gaps)
    }

    @Test
    fun anHourlyDocumentOnAnAutumnDayStillHas100QuarterHours() {
        val date = "2026-10-25"
        val body = RelayFixtures.dayBody(
            RelayFixtures.se4, date, RelayFixtures.prices(25),
            "2026-10-25T00:00:00+02:00", res = 60, dateField = date
        )

        assertEquals(100, pointsFor(body, date = date).size)
    }

    @Test
    fun anAreaTheCatalogueNoLongerHasIsNotASecondSourceOfPrices() {
        // The retired id resolves to nothing, so there is nowhere for a fallback to come from --
        // the point of `find` being nullable.
        assertNull(PriceMarkets.find("RETIRED"))
    }

    // --- what counts as accepted new prices, and what the screen's own read costs ---

    /** A publication refresh that re-read the very same documents accepts nothing new. */
    @Test
    fun aForcedRefreshOfTheSameDocumentsAcceptsNothingNew() {
        val source = FakeDaySource(
            days = mapOf("SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"))
        )

        val first = load(RelayFixtures.se4, source, force = true)
        // Nothing was held before it, and it accepted a whole day: that *is* new prices -- a screen
        // showing nothing has just gained something to show (see the rule's own KDoc).
        assertTrue(priceDocumentsChanged(null, first))

        // A later forced refresh, past the memory cache's own dedup window, so the source really is
        // asked again -- and answers exactly what it answered before.
        val again = load(RelayFixtures.se4, source, nowMillis = 2_000_000L, force = true)
        assertEquals(listOf(todayDay, todayDay), source.dayRequests)
        assertEquals(first.today, again.today)
        assertFalse(priceDocumentsChanged(first, again))
    }

    /** Tomorrow appearing is exactly what the event exists for. */
    @Test
    fun tomorrowAppearingIsNewPrices() {
        val todayOnly = FakeDaySource(
            days = mapOf("SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"))
        )
        val before = load(RelayFixtures.se4, todayOnly)
        assertTrue(before.tomorrow.isEmpty())

        val published = FakeDaySource(
            days = mapOf(
                "SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"),
                "SE4:$tomorrowKey" to day(RelayFixtures.se4, tomorrowKey, 96, "2026-09-21T00:00:00+02:00")
            )
        )
        val after = load(RelayFixtures.se4, published, nowMillis = 2_000_000L, force = true)

        assertEquals(96, after.tomorrow.size)
        assertTrue(priceDocumentsChanged(before, after))
    }

    /** A first publication, from a process that held nothing, is a publication. */
    @Test
    fun aFirstAcceptedResultIsAPublicationAndAnEmptyOneIsNot() {
        val todayOnly = FakeDaySource(
            days = mapOf("SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"))
        )
        val firstToday = load(RelayFixtures.se4, todayOnly)
        assertTrue(priceDocumentsChanged(null, firstToday))

        val both = FakeDaySource(
            days = mapOf(
                "SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"),
                "SE4:$tomorrowKey" to day(RelayFixtures.se4, tomorrowKey, 96, "2026-09-21T00:00:00+02:00")
            )
        )
        val firstBoth = load(RelayFixtures.se4, both, nowMillis = 2_000_000L)
        assertTrue(priceDocumentsChanged(null, firstBoth))

        val empty = PriceResult(today = emptyList(), tomorrow = emptyList(), fetchedAt = 0L)
        assertFalse(priceDocumentsChanged(null, empty))
        assertFalse(priceDocumentsChanged(firstToday, empty))
    }

    @Test
    fun aFailedRefreshAndAnEmptyResultAreNeverNewPrices() {
        val source = FakeDaySource(
            days = mapOf("SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00")),
            failDays = true
        )
        val held = load(RelayFixtures.se4, source)

        // A refresh that failed keeps what was held: the same prices, and therefore no event.
        val afterFailure = load(RelayFixtures.se4, source, nowMillis = 2_000_000L, force = true)
        assertFalse(priceDocumentsChanged(held, afterFailure))

        // And an empty result is "no prices", which is never "new prices".
        val empty = PriceResult(today = emptyList(), tomorrow = emptyList(), fetchedAt = 0L)
        assertFalse(priceDocumentsChanged(held, empty))
        assertFalse(priceDocumentsChanged(null, empty))
    }

    /**
     * The propagation itself costs nothing: once the widget's forced refresh has stored its result,
     * the screen's own read is a memory-cache hit with no request of any kind.
     */
    @Test
    fun theScreensOwnReadAfterAPublicationRequestsNothing() {
        val source = FakeDaySource(
            days = mapOf(
                "SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"),
                "SE4:$tomorrowKey" to day(RelayFixtures.se4, tomorrowKey, 96, "2026-09-21T00:00:00+02:00")
            )
        )

        // The widget's alarm: forced, and this is the request the source answers.
        val published = load(RelayFixtures.se4, source, force = true)
        val dayCalls = source.dayRequests.size
        val questions = source.questions.size
        assertTrue(dayCalls > 0)

        // The screen's read afterwards, at the same clock: held prices, no fetch, no index, no day.
        val rendered = load(RelayFixtures.se4, source)

        assertEquals(published.today, rendered.today)
        assertEquals(published.tomorrow, rendered.tomorrow)
        assertEquals(dayCalls, source.dayRequests.size)
        assertEquals(questions, source.questions.size)
    }

    // held only: the widget's boundary redraw asks nothing of anyone

    private fun heldOnly(area: PriceMarket?, nowMillis: Long = 2_000_000L) =
        PriceRepository.heldOnly(store, area, TEST_TODAY, nowMillis)

    private fun bothDays() = FakeDaySource(
            days = mapOf(
            "SE4:$todayKey" to day(RelayFixtures.se4, todayKey, 96, "2026-09-20T00:00:00+02:00"),
            "SE4:$tomorrowKey" to day(RelayFixtures.se4, tomorrowKey, 96, "2026-09-21T00:00:00+02:00")
        )
    )

    @Test
    fun heldOnlyOfNothingIsAnEmptyResultAndAnUnknownAreaIsToo() {
        assertTrue(heldOnly(RelayFixtures.se4).today.isEmpty())
        assertEquals(PriceSource.NONE, heldOnly(RelayFixtures.se4).todaySource)
        assertTrue(heldOnly(null).today.isEmpty())
    }

    @Test
    fun heldOnlyReadsTheDaysTheDiskHoldsWithoutAnyTransport() {
        load(RelayFixtures.se4, bothDays())
        PriceRepository.clearMemoryCacheForTest()

        val held = heldOnly(RelayFixtures.se4)

        assertEquals(96, held.today.size)
        assertEquals(96, held.tomorrow.size)
        assertEquals(PriceSource.DISK, held.todaySource)
        assertEquals(PriceSource.DISK, held.tomorrowSource)
    }

    @Test
    fun heldOnlyPrefersTheMemoryResultAtAnyAge() {
        val loaded = load(RelayFixtures.se4, bothDays())

        val held = heldOnly(RelayFixtures.se4, nowMillis = 1_000_000L + 24 * 60 * 60_000L)

        assertEquals(loaded, held)
    }

    @Test
    fun heldOnlyNeverMakesAnOrdinaryLoadBelieveThePricesAreFresh() {
        load(RelayFixtures.se4, bothDays())
        PriceRepository.clearMemoryCacheForTest()
        heldOnly(RelayFixtures.se4)

        // The ordinary load after a held-only read still owes the source its question.
        val source = bothDays()
        load(RelayFixtures.se4, source)

        assertTrue(source.questions.isNotEmpty())
    }
}
