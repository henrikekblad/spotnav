package se.sensnology.spotnav.prices.entsoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.prices.HttpAnswer
import se.sensnology.spotnav.prices.HttpGet
import se.sensnology.spotnav.testing.BundledAreas
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayUpstream
import java.time.Instant

class EntsoeKeyStoreTest {
    private val secret = "SENTINEL-KEY-0123456789abcdef"
    private val prefs = FakeKeyValueStore()

    /** A reversible stand-in for the AndroidKeyStore box, which only a device has. */
    private class FakeBox(var opens: Boolean = true) : SecretBox {
        override fun seal(plain: String): String = "sealed:" + plain.reversed()
        override fun open(sealed: String): String? = if (opens) sealed.removePrefix("sealed:").reversed() else null
    }

    private val box = FakeBox()
    private val keys = EntsoeKeyStore(prefs, box)

    @Before
    fun freshProcess() = EntsoeLimiter.resetSpacingForTest()

    @Test
    fun aSavedKeyReadsBackAndIsStoredOnlySealed() {
        assertEquals(KeyState.NONE, keys.state())
        assertTrue(keys.save(EntsoeKey(" $secret\n"), KeyState.OK))
        assertEquals(EntsoeKey(secret), keys.key())
        assertEquals(KeyState.OK, keys.state())
        for (stored in prefs.keys()) assertFalse("$stored holds the key in the clear", prefs.getString(stored)!!.contains(secret))
        assertEquals("••••cdef", keys.masked())
    }

    @Test
    fun aKeyThatNoLongerOpensReadsAsNoKey() {
        keys.save(EntsoeKey(secret), KeyState.OK)
        box.opens = false
        assertNull(keys.key())
        assertEquals(KeyState.NONE, keys.state())
    }

    @Test
    fun laterAnswersMoveTheState() {
        keys.save(EntsoeKey(secret), KeyState.UNCHECKED)
        keys.markAccepted()
        assertEquals(KeyState.OK, keys.state())
        keys.markRejected()
        assertEquals(KeyState.REJECTED, keys.state())
        keys.markAccepted()
        assertEquals("only an unchecked key is confirmed by an answer", KeyState.REJECTED, keys.state())
        keys.remove()
        assertEquals(KeyState.NONE, keys.state())
        keys.markRejected()
        assertEquals(KeyState.NONE, keys.state())
    }

    @Test
    fun saveCheckedKeepsARefusedKeyOut() {
        assertFalse(keys.saveChecked(EntsoeKey(secret), KeyCheck.REJECTED))
        assertEquals(KeyState.NONE, keys.state())
        assertTrue(keys.saveChecked(EntsoeKey(secret), KeyCheck.UNCHECKED))
        assertEquals(KeyState.UNCHECKED, keys.state())
        assertTrue(keys.saveChecked(EntsoeKey(secret), KeyCheck.ACCEPTED))
        assertEquals(KeyState.OK, keys.state())
    }

    private fun check(reply: HttpAnswer, area: String? = "SE4"): Pair<KeyCheck, List<String>> {
        val urls = mutableListOf<String>()
        val http = HttpGet { url, _, _, _ -> urls += url; reply }
        val client = EntsoeClient(http, EntsoeLimiter(FakeKeyValueStore(), { 0L }, {}))
        val result = EntsoeKeyStore.check(
            EntsoeKey(secret),
            area?.let { BundledAreas.area(it) } ?: BundledAreas.area("GR").copy(entsoe = null),
            BundledAreas.area("DE-LU"),
            client,
            Instant.parse("2026-10-30T09:00:00Z")
        )
        return result to urls
    }

    @Test
    fun theCheckAsksForTodayAndReadsTheAnswer() {
        val (accepted, urls) = check(HttpAnswer.Response(200, RelayUpstream.read("synthetic_ack_999_no_data.xml")))
        assertEquals(KeyCheck.ACCEPTED, accepted)
        assertTrue(urls.single().contains("in_Domain=10Y1001A1001A47J"))
        assertTrue(urls.single().contains("periodStart=202610292300"))

        assertEquals(KeyCheck.REJECTED, check(HttpAnswer.Response(401, "")).first)
        assertEquals(KeyCheck.REJECTED, check(HttpAnswer.Response(403, "")).first)
        assertEquals(KeyCheck.UNCHECKED, check(HttpAnswer.NoAnswer("UnknownHostException")).first)
        assertEquals(KeyCheck.UNCHECKED, check(HttpAnswer.Response(503, "")).first)
        assertEquals(KeyCheck.ACCEPTED, check(HttpAnswer.Response(200, "<html/>")).first)
    }

    @Test
    fun anAreaWithoutARouteIsCheckedAgainstDeLu() {
        val (_, urls) = check(HttpAnswer.Response(200, RelayUpstream.read("synthetic_ack_999_no_data.xml")), area = null)
        assertTrue(urls.single().contains("in_Domain=10Y1001A1001A82H"))
    }
}
