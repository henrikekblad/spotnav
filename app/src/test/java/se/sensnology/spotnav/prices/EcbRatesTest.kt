package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayUpstream
import java.time.Instant
import java.time.LocalDate

class EcbRatesTest {
    private val daily = RelayUpstream.read("ecb_daily.xml")
    private val store = FakeKeyValueStore()

    private fun table(date: String, vararg values: Pair<String, Double>) =
        EcbTable(LocalDate.parse(date), mapOf(*values).ifEmpty { mapOf("SEK" to 11.0, "NOK" to 10.0, "DKK" to 7.4) })

    /** A scripted ECB: answers in turn, the last one repeated; records every request. */
    private class FakeEcb(vararg answers: HttpAnswer) : HttpGet {
        private val queue = answers.toMutableList()
        val urls = mutableListOf<String>()
        override fun get(url: String, accept: String, timeoutMs: Int, maxBytes: Int): HttpAnswer {
            urls += url
            return if (queue.size > 1) queue.removeAt(0) else queue.single()
        }
    }

    // parsing

    @Test
    fun theDailyCaptureIsReadWithTheEcbsOwnDate() {
        val table = EcbParser.daily(daily)!!
        assertEquals(LocalDate.parse("2026-09-18"), table.day)
        assertEquals(11.2915, table.values.getValue("SEK"), 0.0)
        assertEquals(10.8095, table.values.getValue("NOK"), 0.0)
        assertEquals(7.4754, table.values.getValue("DKK"), 0.0)
        for (currency in listOf("PLN", "CZK", "HUF", "CHF", "RON")) assertTrue(currency, currency in table.values)
    }

    @Test
    fun aTableMissingANeededRateOrJunkIsRefused() {
        val truncated = "<?xml version=\"1.0\"?><Envelope><Cube><Cube time='2026-09-18'>" +
            "<Cube currency='SEK' rate='11.2915'/></Cube></Cube></Envelope>"
        assertNull(EcbParser.daily(truncated))
        assertNull(EcbParser.daily("not xml at all"))
        assertNull(EcbParser.daily("<x/>"))
    }

    @Test
    fun aWrongEntryRefusesTheWholeBody() {
        for (bad in listOf("abc", "-1", "0", "NaN", "Infinity", "")) {
            assertNull(bad, EcbParser.daily(daily.replace("rate='11.2915'", "rate='$bad'")))
        }
        assertNull("a table date that is not a date", EcbParser.daily(daily.replace("time='2026-09-18'", "time='18/09/2026'")))
    }

    @Test
    fun aDocumentTypeDeclarationIsRefusedBeforeParsing() {
        val withEntity = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" +
            daily.substringAfter("?>")
        assertNull(EcbParser.daily(withEntity))
    }

    // the rule

    @Test
    fun aDayGetsTheNewestTableOnOrBeforeItWithinAWeek() {
        val tables = listOf(table("2026-10-02"), table("2026-10-01"), table("2026-09-25"), table("2024-02-09"))
        fun on(date: String) = EcbRates.tableFor(tables, LocalDate.parse(date))?.day?.toString() ?: "none"

        assertEquals("tomorrow, priced the afternoon before", "2026-10-02", on("2026-10-04"))
        assertEquals("2026-10-01", on("2026-10-01"))
        assertEquals("a Monday after a gap falls back to the last table", "2026-09-25", on("2026-09-28"))
        assertEquals("nothing on or before it within a week", "none", on("2026-09-24"))
        assertEquals("2024-02-09", on("2024-02-10"))
        assertEquals("more than a week after the last table", "none", on("2024-02-20"))
        assertEquals("seven days is still inside", "2026-10-02", on("2026-10-09"))
        assertEquals("eight is not", "none", on("2026-10-10"))
    }

    @Test
    fun theExpectedTableIsTheLatestWeekdayAfterItsPublication() {
        // 2026-09-18 is a Friday; 16:20 Frankfurt is 14:20 UTC in September.
        assertEquals(LocalDate.parse("2026-09-17"), EcbRates.expectedTable(Instant.parse("2026-09-18T14:19:00Z")))
        assertEquals(LocalDate.parse("2026-09-18"), EcbRates.expectedTable(Instant.parse("2026-09-18T14:20:00Z")))
        assertEquals("a weekend expects Friday's", LocalDate.parse("2026-09-18"), EcbRates.expectedTable(Instant.parse("2026-09-20T12:00:00Z")))
        assertEquals("a Monday morning too", LocalDate.parse("2026-09-18"), EcbRates.expectedTable(Instant.parse("2026-09-21T08:00:00Z")))
    }

    @Test
    fun aFetchIsMadeOnlyWhenNothingConvertsTheDayOrTheHeldTableIsBehind() {
        val friday = listOf(table("2026-09-18"))
        val day = LocalDate.parse("2026-09-21")
        val mondayMorning = Instant.parse("2026-09-21T08:00:00Z")
        val mondayEvening = Instant.parse("2026-09-21T15:00:00Z")

        assertFalse("held and current", EcbRates.shouldFetch(friday, "SEK", day, mondayMorning, null))
        assertTrue("behind, never asked", EcbRates.shouldFetch(friday, "SEK", day, mondayEvening, null))
        assertFalse(
            "behind, asked within the hour",
            EcbRates.shouldFetch(friday, "SEK", day, mondayEvening, mondayEvening.toEpochMilli() - 59 * 60_000L)
        )
        assertTrue(
            "behind, asked an hour ago",
            EcbRates.shouldFetch(friday, "SEK", day, mondayEvening, mondayEvening.toEpochMilli() - 60 * 60_000L)
        )
        assertTrue("nothing held", EcbRates.shouldFetch(emptyList(), "SEK", day, mondayMorning, null))
        assertFalse(
            "nothing held, asked a minute ago",
            EcbRates.shouldFetch(emptyList(), "SEK", day, mondayMorning, mondayMorning.toEpochMilli() - 60_000L)
        )
        assertTrue(
            "nothing held, asked five minutes ago",
            EcbRates.shouldFetch(emptyList(), "SEK", day, mondayMorning, mondayMorning.toEpochMilli() - 5 * 60_000L)
        )
    }

    // the holder

    @Test
    fun aFetchedTableIsHeldAndConvertsTheDay() {
        val ecb = FakeEcb(HttpAnswer.Response(200, daily))
        val rates = EcbRates(store, ecb)

        val rate = rates.rateFor("SEK", LocalDate.parse("2026-09-19"), Instant.parse("2026-09-18T15:00:00Z"))

        assertEquals(FxRate(11.2915, LocalDate.parse("2026-09-18")), rate)
        assertEquals(listOf(EcbRates.DAILY_URL), ecb.urls)
        assertEquals(LocalDate.parse("2026-09-18"), EcbRates(store, FakeEcb(HttpAnswer.NoAnswer("x"))).held().single().day)

        // Held and current: no second request.
        rates.rateFor("NOK", LocalDate.parse("2026-09-19"), Instant.parse("2026-09-18T15:05:00Z"))
        assertEquals(1, ecb.urls.size)
    }

    @Test
    fun aHeldTableSurvivesAFailedFetch() {
        EcbRates(store, FakeEcb(HttpAnswer.Response(200, daily)))
            .rateFor("SEK", LocalDate.parse("2026-09-18"), Instant.parse("2026-09-18T15:00:00Z"))

        for (failure in listOf(HttpAnswer.NoAnswer("SocketTimeoutException"), HttpAnswer.Response(503, ""), HttpAnswer.Response(200, "<html/>"))) {
            // Monday evening: the held Friday table is behind, so the ECB is asked, and fails.
            val ecb = FakeEcb(failure)
            store.remove(EcbRates.KEY_ATTEMPT_AT)
            val rate = EcbRates(store, ecb).rateFor("SEK", LocalDate.parse("2026-09-22"), Instant.parse("2026-09-21T15:00:00Z"))
            assertEquals(1, ecb.urls.size)
            assertEquals(FxRate(11.2915, LocalDate.parse("2026-09-18")), rate)
        }
    }

    @Test
    fun noTableCloseEnoughIsNoRate() {
        EcbRates(store, FakeEcb(HttpAnswer.Response(200, daily)))
            .rateFor("SEK", LocalDate.parse("2026-09-18"), Instant.parse("2026-09-18T15:00:00Z"))

        val ecb = FakeEcb(HttpAnswer.NoAnswer("UnknownHostException"))
        val rate = EcbRates(store, ecb).rateFor("SEK", LocalDate.parse("2026-09-26"), Instant.parse("2026-09-25T15:00:00Z"))

        assertNull(rate)
        assertEquals(1, ecb.urls.size)
    }

    @Test
    fun theNewestTenTablesAreKept() {
        var tables = emptyList<EcbTable>()
        var day = LocalDate.parse("2026-09-01")
        repeat(15) {
            tables = EcbRates.adopt(tables, table(day.toString()))
            day = day.plusDays(1)
        }
        assertEquals(EcbRates.MAX_TABLES, tables.size)
        assertEquals(LocalDate.parse("2026-09-15"), tables.first().day)
        assertEquals(LocalDate.parse("2026-09-06"), tables.last().day)
        // A newer read of a held date replaces it.
        val replaced = EcbRates.adopt(tables, table("2026-09-15", "SEK" to 12.0, "NOK" to 10.0, "DKK" to 7.0))
        assertEquals(12.0, replaced.first().values.getValue("SEK"), 0.0)
        assertEquals(EcbRates.MAX_TABLES, replaced.size)
    }

    @Test
    fun aDamagedStoreReadsAsEmpty() {
        store.putString(EcbRates.KEY_TABLES, "not json")
        assertTrue(EcbRates(store).held().isEmpty())
        store.putString(EcbRates.KEY_TABLES, """[{"date":"2026-09-18","values":{"SEK":-1}},{"date":"x","values":{"SEK":1}},{"date":"2026-09-17","values":{"SEK":11.0}}]""")
        assertEquals(listOf(LocalDate.parse("2026-09-17")), EcbRates(store).held().map { it.day })
    }
}
