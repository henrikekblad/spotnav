package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.FakeRelayTransport
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.TEST_TODAY
import java.time.Instant
import java.time.LocalDate

/** The day-source seam: what the repository asks a source, what it does with each answer, and the cache's pruning. */
class DaySourceTest {
    private val store = FakeKeyValueStore()
    private val todayKey = TEST_TODAY.toString()
    private val tomorrowKey = TEST_TODAY.plusDays(1).toString()

    @Before
    fun resetMemoryCache() {
        PriceRepository.clearMemoryCacheForTest()
    }

    private fun day(date: String, start: String) =
        RelayFixtures.dayBody(RelayFixtures.se4, date, RelayFixtures.prices(96), start, 15)

    private val todayBody = day("2026-09-20", "2026-09-20T00:00:00+02:00")
    private val tomorrowBody = day("2026-09-21", "2026-09-21T00:00:00+02:00")

    /** A scripted source: answers by market day, and records every question and request. */
    private class ScriptedSource(
        private val answers: Map<LocalDate, DayFetch>,
        private val worth: (LocalDate, Boolean, Boolean) -> Boolean = { _, _, _ -> true }
    ) : DaySource {
        val asked = mutableListOf<Triple<LocalDate, Boolean, Boolean>>()
        val fetched = mutableListOf<LocalDate>()

        override fun worthAsking(area: PriceMarket, marketDay: LocalDate, now: Instant, force: Boolean, held: Boolean): Boolean {
            asked += Triple(marketDay, force, held)
            return worth(marketDay, force, held)
        }

        override fun fetch(area: PriceMarket, marketDay: LocalDate): DayFetch {
            fetched += marketDay
            return answers[marketDay] ?: DayFetch.Failed(PriceFailure.NETWORK)
        }
    }

    private fun load(source: DaySource, profileId: String? = null, now: Long = 1_000_000L, force: Boolean = false) =
        PriceRepository.load(store, source, RelayFixtures.se4, TEST_TODAY, now, force, profileId = profileId)

    @Test
    fun aBodyIsValidatedAndCachedLikeARelayFile() {
        val source = ScriptedSource(
            mapOf(TEST_TODAY to DayFetch.Body(todayBody), TEST_TODAY.plusDays(1) to DayFetch.Body(tomorrowBody))
        )

        val result = load(source)

        assertEquals(96, result.today.size)
        assertEquals(96, result.tomorrow.size)
        assertEquals(PriceSource.FETCHED, result.todaySource)
        assertEquals(todayBody, store.rawOrNull("v1:SE4:$todayKey"))
        assertEquals("1000000", store.rawOrNull("v1:SE4:$todayKey:at"))
    }

    @Test
    fun notPublishedAndFailedBothLeaveWhatIsHeld() {
        store.putString("v1:SE4:$todayKey", todayBody)
        val source = ScriptedSource(
            mapOf(TEST_TODAY to DayFetch.Failed(PriceFailure.HTTP), TEST_TODAY.plusDays(1) to DayFetch.NotPublished)
        )

        val result = load(source)

        assertEquals(96, result.today.size)
        assertEquals(PriceSource.DISK, result.todaySource)
        assertTrue(result.tomorrow.isEmpty())
        assertNull(store.rawOrNull("v1:SE4:$tomorrowKey"))
    }

    @Test
    fun anInvalidBodyIsNeverCached() {
        val wrongArea = RelayFixtures.dayBody(RelayFixtures.no1, todayKey, RelayFixtures.prices(96), "2026-09-20T00:00:00+02:00", 15)
        val source = ScriptedSource(mapOf(TEST_TODAY to DayFetch.Body(wrongArea)))

        val result = load(source)

        assertTrue(result.today.isEmpty())
        assertNull(store.rawOrNull("v1:SE4:$todayKey"))
    }

    @Test
    fun theSourceIsToldWhatIsHeldAndWhetherAPersonAsked() {
        store.putString("v1:SE4:$todayKey", todayBody)
        val source = ScriptedSource(emptyMap()) { _, _, held -> !held }

        load(source, force = true)

        assertEquals(
            listOf(Triple(TEST_TODAY, true, true), Triple(TEST_TODAY.plusDays(1), true, false)),
            source.asked
        )
        assertEquals("a held day the source declines is not requested", listOf(TEST_TODAY.plusDays(1)), source.fetched)
    }

    @Test
    fun theMemoryCacheKeepsProfilesApart() {
        val source = ScriptedSource(mapOf(TEST_TODAY to DayFetch.Body(todayBody)))

        load(source, profileId = null)
        load(source, profileId = null, now = 1_000_001L)
        assertEquals("one load per subject within the memory window", 2, source.fetched.size)

        load(source, profileId = "charger-a", now = 1_000_002L)
        assertEquals("another profile is another subject", 4, source.fetched.size)

        assertEquals(96, PriceRepository.heldOnly(store, RelayFixtures.se4, TEST_TODAY, 1_000_003L, "charger-a").today.size)
    }

    @Test
    fun theRelaySourceAsksOnlyForWhatItsIndexLists() {
        val transport = FakeRelayTransport(
            index = RelayFixtures.indexBody(listOf("SE4"), listOf(todayKey)),
            days = mapOf("SE4:$todayKey" to todayBody)
        )
        val source = RelayDaySource(store, transport)
        val now = Instant.ofEpochMilli(1L)

        assertTrue(source.worthAsking(RelayFixtures.se4, TEST_TODAY, now, force = false, held = true))
        assertFalse(source.worthAsking(RelayFixtures.se4, TEST_TODAY.plusDays(1), now, force = true, held = false))
        assertEquals(DayFetch.Body(todayBody), source.fetch(RelayFixtures.se4, TEST_TODAY))
        assertEquals(DayFetch.Failed(PriceFailure.NETWORK), source.fetch(RelayFixtures.se4, TEST_TODAY.plusDays(1)))
        assertNotNull("the index read is held", store.rawOrNull(PriceRepository.INDEX_KEY))
    }

    // pruning

    @Test
    fun writingADayPrunesEveryAreasOldDays() {
        val old = TEST_TODAY.minusDays(3).toString()
        val kept = TEST_TODAY.minusDays(2).toString()
        store.putString("v1:SE4:$old", "x")
        store.putString("v1:SE4:$old:at", "1")
        store.putString("v1:NO1:$old", "x")
        store.putString("v1:GB-C:$kept", "x")
        store.putString("v1:GB-C:$kept:at", "1")
        store.putString(PriceRepository.INDEX_KEY, "index")
        store.putString("v1:SE4:not-a-date", "x")

        load(ScriptedSource(mapOf(TEST_TODAY to DayFetch.Body(todayBody))))

        assertNull(store.rawOrNull("v1:SE4:$old"))
        assertNull(store.rawOrNull("v1:SE4:$old:at"))
        assertNull(store.rawOrNull("v1:NO1:$old"))
        assertEquals("x", store.rawOrNull("v1:GB-C:$kept"))
        assertEquals("1", store.rawOrNull("v1:GB-C:$kept:at"))
        assertEquals("index", store.rawOrNull(PriceRepository.INDEX_KEY))
        assertEquals("x", store.rawOrNull("v1:SE4:not-a-date"))
        assertEquals(todayBody, store.rawOrNull("v1:SE4:$todayKey"))
    }

    @Test
    fun nothingIsPrunedWithoutAWrite() {
        val old = TEST_TODAY.minusDays(10).toString()
        store.putString("v1:SE4:$old", "x")

        load(ScriptedSource(emptyMap()))

        assertEquals("a load that writes nothing removes nothing", "x", store.rawOrNull("v1:SE4:$old"))
    }
}
