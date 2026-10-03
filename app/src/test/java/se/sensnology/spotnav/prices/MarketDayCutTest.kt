package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.FakeRelayTransport
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.RelayV2Fixtures
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Display days cut from market days, through the repository: a London day from two Paris files, a
 * Lisbon day from two Madrid files, both clock changes, and tomorrow only once its main file is held.
 */
class MarketDayCutTest {
    private val store = FakeKeyValueStore()
    private val gb = RelayV2Fixtures.area("GB-C")
    private val pt = RelayV2Fixtures.area("PT")
    private val london = ZoneId.of("Europe/London")

    @Before
    fun reset() {
        PriceRepository.clearMemoryCacheForTest()
    }

    private fun load(area: PriceMarket, today: String, transport: FakeRelayTransport, now: Long = 1_000_000L) =
        PriceRepository.load(store, transport, area, LocalDate.parse(today), now, forceRefresh = true, version = RelayContractVersion.V2)

    private fun instant(text: String) = OffsetDateTime.parse(text).toInstant()

    private fun assertQuarterSteps(points: List<PricePoint>) {
        val steps = points.zipWithNext { a, b -> Duration.between(a.start.toInstant(), b.start.toInstant()).toMinutes() }.toSet()
        assertEquals(setOf(15L), steps)
    }

    @Test
    fun theKeysOfADisplayDay() {
        val day = LocalDate.parse("2026-10-04")
        assertEquals(listOf(day, day.plusDays(1)), MarketDays.keysFor(day, "Europe/London", "Europe/Paris"))
        assertEquals(listOf(day, day.plusDays(1)), MarketDays.keysFor(day, "Europe/Lisbon", "Europe/Madrid"))
        assertEquals(listOf(day), MarketDays.keysFor(day, "Europe/Stockholm", "Europe/Stockholm"))
        assertEquals(day, MarketDays.principal(day, "Europe/London", "Europe/Paris"))
        // Both clock changes keep the same two files.
        val spring = LocalDate.parse("2026-03-29")
        assertEquals(listOf(spring, spring.plusDays(1)), MarketDays.keysFor(spring, "Europe/London", "Europe/Paris"))
        val autumn = LocalDate.parse("2026-10-25")
        assertEquals(listOf(autumn, autumn.plusDays(1)), MarketDays.keysFor(autumn, "Europe/London", "Europe/Paris"))
    }

    @Test
    fun theLondonEveningComesFromTheNextParisFile() {
        val transport = RelayV2Fixtures.transport(listOf("GB-C_2026-10-04.json", "GB-C_2026-10-05.json"))
        val result = load(gb, "2026-10-04", transport)

        assertEquals(96, result.today.size)
        assertEquals("00:00", result.today.first().start.toLocalTime().toString())
        assertEquals("23:45", result.today.last().start.toLocalTime().toString())
        assertEquals(london.rules.getOffset(result.today.first().start.toInstant()), result.today.first().start.offset)
        // 23:00 to 24:00 London is the first hour of the Paris file of the 5th.
        assertEquals(instant("2026-10-05T00:00:00+02:00"), result.today[92].start.toInstant())
        assertQuarterSteps(result.today)
        // Tomorrow is its main file (23 hours); its last hour waits for the file of the 6th.
        assertEquals(92, result.tomorrow.size)
        assertEquals(instant("2026-10-05T00:00:00+01:00"), result.tomorrow.first().start.toInstant())
        // Only the v2 index was read, and only listed files were asked for.
        assertEquals(listOf(HttpRelayTransport.INDEX_V2_URL), transport.indexRequests)
        assertEquals(
            listOf(HttpRelayTransport.dayUrl("GB-C", "2026-10-04"), HttpRelayTransport.dayUrl("GB-C", "2026-10-05")),
            transport.dayRequests.distinct()
        )
        assertTrue(PriceRepository.holdsTomorrow(store, gb, LocalDate.parse("2026-10-04")))
    }

    @Test
    fun theAfternoonBeforePublicationHasNoTomorrowAndTodayEndsAt2300() {
        val index = RelayFixtures.indexBody(listOf("GB-C"), listOf("2026-10-03", "2026-10-04"), v = 2, res = 30)
        val transport = RelayV2Fixtures.transport(listOf("GB-C_2026-10-04.json"), indexV2 = index)
        val result = load(gb, "2026-10-04", transport)

        assertEquals(92, result.today.size)
        assertEquals("22:45", result.today.last().start.toLocalTime().toString())
        assertTrue(result.tomorrow.isEmpty())
        assertEquals(PriceSource.NONE, result.tomorrowSource)
        assertFalse(PriceRepository.holdsTomorrow(store, gb, LocalDate.parse("2026-10-04")))
    }

    @Test
    fun eachPieceKeepsItsOwnFilesRate() {
        val raised = RelayV2Fixtures.read("GB-C_2026-10-05.json").replace("\"GBP\":0.8712", "\"GBP\":0.9")
        val transport = RelayV2Fixtures.transport(
            listOf("GB-C_2026-10-04.json", "GB-C_2026-10-05.json"),
            replace = mapOf("GB-C_2026-10-05.json" to raised)
        )
        val today = load(gb, "2026-10-04", transport).today
        // The writer's file of the 5th starts at 0.19513 EUR; the 4th's 02:00 Paris (01:00 London) is 0.11478.
        assertEquals(0.19513 * 0.9, today[92].pricePerKwh, 1e-12)
        assertEquals(0.11478 * 0.8712, today[4].pricePerKwh, 1e-12)
    }

    @Test
    fun theSpringLondonDayIs23HoursAndTheAutumnOne25() {
        val springIndex = RelayFixtures.indexBody(listOf("GB-C"), listOf("2026-03-29"), v = 2, res = 30)
        val spring = load(gb, "2026-03-29", RelayV2Fixtures.transport(listOf("GB-C_2026-03-29.json"), indexV2 = springIndex))
        // 46 half-hours, less the first hour (London's 28th), end at 23:00 BST; the last hour is the 30th's file.
        assertEquals(88, spring.today.size)
        assertEquals(instant("2026-03-29T00:00:00Z"), spring.today.first().start.toInstant())
        assertEquals(instant("2026-03-29T23:00:00+01:00"), spring.today.last().start.toInstant().plusSeconds(900))
        assertQuarterSteps(spring.today)

        PriceRepository.clearMemoryCacheForTest()
        val autumnIndex = RelayFixtures.indexBody(listOf("GB-C"), listOf("2026-10-25", "2026-10-26"), v = 2, res = 30)
        val autumn = load(
            gb, "2026-10-25",
            RelayV2Fixtures.transport(listOf("GB-C_2026-10-25.json", "GB-C_2026-10-26.json"), indexV2 = autumnIndex)
        )
        assertEquals(100, autumn.today.size)
        assertEquals(instant("2026-10-25T00:00:00+01:00"), autumn.today.first().start.toInstant())
        assertEquals(instant("2026-10-26T00:00:00Z"), autumn.today.last().start.toInstant().plusSeconds(900))
        assertQuarterSteps(autumn.today)
        // The repeated 01:00 hour is there twice, with its two offsets.
        assertEquals(8, autumn.today.count { it.start.hour == 1 })
    }

    @Test
    fun aLisbonDayIsCutAnHourOffTheMadridCalendar() {
        val result = load(pt, "2026-10-04", RelayV2Fixtures.transport(listOf("PT_2026-10-04.json", "PT_2026-10-05.json")))
        assertEquals(96, result.today.size)
        assertEquals(instant("2026-10-04T00:00:00+01:00"), result.today.first().start.toInstant())
        assertEquals("+01:00", result.today.first().start.offset.toString())
        assertQuarterSteps(result.today)
    }

    @Test
    fun aDayHeldBeforeContractV2IsStillHeldAfterIt() {
        // A Portugal file the app stored while it read v1 (`tz: Europe/Madrid`), read under the v2 area.
        val v1Store = FakeKeyValueStore()
        val v1Pt = PriceMarket(
            id = "PT", countries = listOf("PT"), name = "Portugal", tz = "Europe/Madrid", currency = "EUR",
            majorUnit = "€", minorUnit = "cent"
        )
        val index = RelayFixtures.indexBody(listOf("PT"), listOf("2026-10-04", "2026-10-05"), res = 15)
        val v1Transport = FakeRelayTransport(
            index = index,
            days = mapOf(
                "PT:2026-10-04" to RelayV2Fixtures.read("PT_2026-10-04.json"),
                "PT:2026-10-05" to RelayV2Fixtures.read("PT_2026-10-05.json")
            )
        )
        PriceRepository.load(v1Store, v1Transport, v1Pt, LocalDate.parse("2026-10-04"), 1_000_000L, forceRefresh = true)
        PriceRepository.clearMemoryCacheForTest()

        val offline = PriceRepository.load(
            v1Store, FakeRelayTransport(), pt, LocalDate.parse("2026-10-04"), 2_000_000L,
            forceRefresh = true, version = RelayContractVersion.V2
        )
        assertEquals(96, offline.today.size)
        assertEquals(PriceSource.DISK, offline.todaySource)
        assertEquals(instant("2026-10-04T00:00:00+01:00"), offline.today.first().start.toInstant())
        val held = PriceRepository.heldOnly(v1Store, pt, LocalDate.parse("2026-10-04"), 3_000_000L)
        assertEquals(96, held.today.size)
    }

    @Test
    fun aHeldOnlyLondonDayIsCutFromTheDisk() {
        load(gb, "2026-10-04", RelayV2Fixtures.transport(listOf("GB-C_2026-10-04.json", "GB-C_2026-10-05.json")))
        PriceRepository.clearMemoryCacheForTest()
        val held = PriceRepository.heldOnly(store, gb, LocalDate.parse("2026-10-04"), 5_000_000L)
        assertEquals(96, held.today.size)
        assertEquals(92, held.tomorrow.size)
        assertEquals(PriceSource.DISK, held.todaySource)
    }

    @Test
    fun aFailedRefreshKeepsTheHeldLondonDayFromMemory() {
        load(gb, "2026-10-04", RelayV2Fixtures.transport(listOf("GB-C_2026-10-04.json", "GB-C_2026-10-05.json")))
        val dead = load(gb, "2026-10-04", FakeRelayTransport(failDays = true), now = 1_000_000L + 60_000L)
        assertEquals(96, dead.today.size)
        assertEquals(92, dead.tomorrow.size)
    }
}
