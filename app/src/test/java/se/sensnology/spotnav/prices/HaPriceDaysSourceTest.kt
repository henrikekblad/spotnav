package se.sensnology.spotnav.prices

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.ha.client.PriceDaysAnswer
import se.sensnology.spotnav.ha.client.PriceDaysDecoder
import se.sensnology.spotnav.testing.BundledAreas
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.HaFixtures
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** A paired charger's prices from its Home Assistant: the answer, the store per profile, and nothing else. */
class HaPriceDaysSourceTest {
    private fun body(name: String): String = File(HaFixtures.root, "webhook/$name.json").readText()

    private fun answered(name: String): PriceDaysAnswer =
        PriceDaysDecoder.answer(200, body(name)).also { assertTrue(it is PriceDaysAnswer.Answered) }

    /** A fetch that answers [answers] in turn and counts its calls. */
    private class Fetch(vararg answers: PriceDaysAnswer) {
        private val queue = ArrayDeque(answers.toList())
        var calls = 0
        fun next(): PriceDaysAnswer {
            calls++
            return queue.removeFirstOrNull() ?: PriceDaysAnswer.Failed
        }
    }

    private val backing = FakeKeyValueStore()
    private val store = HaPriceDaysStore(backing)

    private fun source(profileId: String, fetch: Fetch) = HaPriceDaysSource(store, profileId, fetch::next)

    private val fiNow = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private val fiToday = LocalDate.parse("2026-10-30")
    private val fiSubject = PriceSubject("charger-fi", "FI")

    @Before @After
    fun clearMemory() {
        PriceRepository.clearMemoryCacheForTest()
        PriceMarkets.forgetLearnedForTest()
    }

    @Test fun finlandsBrusselsFilesAreComposedIntoHelsinkiDays() {
        val result = PriceRepository.loadFromHomeAssistant(
            source("charger-fi", Fetch(answered("price_days_nordpool_fi"))), fiSubject, fiToday, fiNow, ask = true
        )
        val helsinki = ZoneId.of("Europe/Helsinki")
        assertEquals(96, result.today.size)
        assertEquals(96, result.tomorrow.size)
        assertEquals(OffsetDateTime.parse("2026-10-30T00:00+02:00"), result.today.first().start)
        assertTrue(result.today.all { it.start.atZoneSameInstant(helsinki).toLocalDate() == fiToday })
        assertTrue(result.tomorrow.all { it.start.atZoneSameInstant(helsinki).toLocalDate() == fiToday.plusDays(1) })
        // Helsinki's midnight is 23:00 in Brussels the day before: the last hour of the 29th's file.
        val file29 = JSONObject(body("price_days_nordpool_fi")).getJSONObject("files").getJSONObject("2026-10-29").getJSONArray("prices")
        assertEquals(file29.getDouble(92), result.today.first().pricePerKwh, 1e-9)
        assertEquals(PriceSource.FETCHED, result.todaySource)
        assertEquals(fiNow, result.todayFetchedAt)
    }

    @Test fun theListsOwnFinnishCalendarIsNeverUsedForHomeAssistantsFiles() {
        // The bundled Finland is on its own calendar; the answer's is Brussels. Composed on the list's, the
        // files would be refused or shifted by an hour.
        assertEquals("Europe/Helsinki", BundledAreas.area("FI").marketTz)
        val result = PriceRepository.loadFromHomeAssistant(
            source("charger-fi", Fetch(answered("price_days_nordpool_fi"))), fiSubject, fiToday, fiNow, ask = true
        )
        assertEquals(OffsetDateTime.parse("2026-10-30T00:00+02:00"), result.today.first().start)
    }

    @Test fun aGoodAnswerIsStoredWholeAndReadBackAsHeld() {
        PriceRepository.loadFromHomeAssistant(
            source("charger-fi", Fetch(answered("price_days_nordpool_fi"))), fiSubject, fiToday, fiNow, ask = true
        )
        assertEquals(body("price_days_nordpool_fi"), store.held("charger-fi")?.body)
        assertEquals(fiNow, store.held("charger-fi")?.fetchedAt)

        PriceRepository.clearMemoryCacheForTest()
        val later = fiNow + 60_000
        val held = PriceRepository.heldFromHomeAssistant(source("charger-fi", Fetch()), fiSubject, fiToday, later)
        assertEquals(96, held.today.size)
        assertEquals(PriceSource.DISK, held.todaySource)
        assertEquals(fiNow, held.todayFetchedAt)
    }

    @Test fun anUnreachableHomeAssistantShowsTheHeldAnswerWithItsStampAndNothingElse() {
        store.put("charger-fi", body("price_days_nordpool_fi"), fiNow - 3_600_000)
        for (failure in listOf(PriceDaysAnswer.Failed, PriceDaysAnswer.Refused("spotnav_no_prices"))) {
            PriceRepository.clearMemoryCacheForTest()
            val fetch = Fetch(failure)
            val result = PriceRepository.loadFromHomeAssistant(source("charger-fi", fetch), fiSubject, fiToday, fiNow, ask = true)
            assertEquals(1, fetch.calls)
            assertEquals(96, result.today.size)
            assertEquals(PriceSource.DISK, result.todaySource)
            assertEquals(fiNow - 3_600_000, result.todayFetchedAt)
            // A failed request never replaces what is held.
            assertEquals(body("price_days_nordpool_fi"), store.held("charger-fi")?.body)
        }
    }

    @Test fun anUnreachableHomeAssistantWithNothingHeldIsEmpty() {
        val result = PriceRepository.loadFromHomeAssistant(source("charger-fi", Fetch()), fiSubject, fiToday, fiNow, ask = true)
        assertTrue(result.today.isEmpty() && result.tomorrow.isEmpty())
        assertEquals(PriceSource.NONE, result.todaySource)
    }

    @Test fun theMemoryResultStandsForTenMinutesAndAForcedRefreshAsksAgain() {
        val fetch = Fetch(answered("price_days_nordpool_fi"), answered("price_days_nordpool_fi"))
        val ha = source("charger-fi", fetch)
        PriceRepository.loadFromHomeAssistant(ha, fiSubject, fiToday, fiNow, ask = true)
        PriceRepository.loadFromHomeAssistant(ha, fiSubject, fiToday, fiNow + 5 * 60_000, ask = true)
        assertEquals(1, fetch.calls)
        PriceRepository.loadFromHomeAssistant(ha, fiSubject, fiToday, fiNow + 6 * 60_000, ask = true, forceRefresh = true)
        assertEquals(2, fetch.calls)
    }

    @Test fun anOldHomeAssistantIsNeverAskedAndHeldDaysAreNotKeptInMemory() {
        store.put("charger-fi", body("price_days_nordpool_fi"), fiNow)
        val fetch = Fetch(answered("price_days_nordpool_fi"))
        val held = PriceRepository.loadFromHomeAssistant(source("charger-fi", fetch), fiSubject, fiToday, fiNow, ask = false)
        assertEquals(0, fetch.calls)
        assertEquals(PriceSource.DISK, held.todaySource)
        // Once it can answer, the next load asks at once rather than standing on the held days.
        PriceRepository.loadFromHomeAssistant(source("charger-fi", fetch), fiSubject, fiToday, fiNow + 1_000, ask = true)
        assertEquals(1, fetch.calls)
    }

    @Test fun eachProfileHasItsOwnAnswer() {
        PriceRepository.loadFromHomeAssistant(
            source("charger-fi", Fetch(answered("price_days_nordpool_fi"))), fiSubject, fiToday, fiNow, ask = true
        )
        val seNow = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()
        val seSubject = PriceSubject("charger-se", "SE4")
        PriceRepository.loadFromHomeAssistant(
            source("charger-se", Fetch(answered("price_days_relay_se4"))), seSubject, LocalDate.parse("2026-09-22"), seNow, ask = true
        )
        assertEquals(body("price_days_nordpool_fi"), store.held("charger-fi")?.body)
        assertEquals(body("price_days_relay_se4"), store.held("charger-se")?.body)

        store.clear("charger-fi")
        assertNull(store.held("charger-fi"))
        assertEquals(body("price_days_relay_se4"), store.held("charger-se")?.body)
        // Nothing of it reached the standalone day cache's keys.
        assertTrue(backing.keys().none { it.startsWith(PriceRepository.SCHEMA + ":") })
    }

    @Test fun aStandaloneResultForTheSameAreaIsNeverTakenForAPairedOne() {
        val seNow = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()
        val today = LocalDate.parse("2026-09-22")
        // A standalone SE4 load leaves its result in memory under no profile.
        val dayCache = FakeKeyValueStore()
        PriceRepository.writeCachedDay(
            dayCache, "SE4", "2026-09-22",
            JSONObject(body("price_days_relay_se4")).getJSONObject("files").getJSONObject("2026-09-22").toString(), seNow, today
        )
        val standalone = PriceRepository.heldOnly(dayCache, BundledAreas.area("SE4"), today, seNow)
        assertEquals(96, standalone.today.size)

        val paired = PriceRepository.heldFromHomeAssistant(source("charger-se", Fetch()), PriceSubject("charger-se", "SE4"), today, seNow)
        assertTrue("the paired charger holds nothing of its own", paired.today.isEmpty())
    }

    @Test fun aSwedishAnswerIsPricedInKronorWithTheFilesRate() {
        val seNow = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()
        val result = PriceRepository.loadFromHomeAssistant(
            source("charger-se", Fetch(answered("price_days_relay_se4"))), PriceSubject("charger-se", "SE4"),
            LocalDate.parse("2026-09-22"), seNow, ask = true
        )
        assertEquals(96, result.today.size)
        assertEquals(96, result.tomorrow.size)
        assertEquals(0.18511 * 11.275, result.today.first().pricePerKwh, 1e-9)
    }

    @Test fun aRegionOnlyHomeAssistantPricesStaysKnownByItsAnswer() {
        val bundled = BundledAreas.all.filterNot { it.id == "GB-C" }
        PriceMarkets.replace(bundled)
        try {
            assertNull(PriceMarkets.find("GB-C"))
            val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
            val result = PriceRepository.loadFromHomeAssistant(
                source("charger-gb", Fetch(answered("price_days_relay_gb"))), PriceSubject("charger-gb", "GB-C"),
                LocalDate.parse("2026-10-04"), now, ask = true
            )
            assertEquals(96, result.today.size)
            assertEquals("Europe/London", PriceMarkets.find("GB-C")?.tz)
            assertEquals("GBP", PriceMarkets.find("GB-C")?.currency)
            // The list's own area always comes first.
            assertEquals(bundled.first(), PriceMarkets.find(bundled.first().id))
        } finally {
            PriceMarkets.replace(emptyList())
        }
    }

    @Test fun tomorrowIsHeldWhenItsMainFileIsInTheAnswer() {
        store.put("charger-fi", body("price_days_nordpool_fi"), fiNow)
        val held = checkNotNull(source("charger-fi", Fetch()).held())
        assertTrue(PriceRepository.holdsTomorrow(held, fiNow))
        // A day later the answer holds nothing for the day after.
        assertFalse(PriceRepository.holdsTomorrow(held, fiNow + 86_400_000))
        assertEquals("12:45", held.read.area.publication.time.toString())
    }

    @Test fun anUnreadableHeldAnswerIsNothing() {
        backing.setRaw("charger-fi", "{not json")
        assertNull(source("charger-fi", Fetch()).held())
    }
}
