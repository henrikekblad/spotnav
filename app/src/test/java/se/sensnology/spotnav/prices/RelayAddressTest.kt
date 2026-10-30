package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayAddressTest {
    private val stub = "http://10.0.2.2:8130"

    @Test
    fun `a release build keeps the official relay whatever was configured`() {
        assertEquals(RelayAddress.OFFICIAL, RelayAddress.baseFor(debug = false, configured = stub))
        assertEquals(RelayAddress.OFFICIAL, RelayAddress.baseFor(debug = false, configured = ""))
    }

    @Test
    fun `a debug build uses the configured relay, or the official one without it`() {
        assertEquals(stub, RelayAddress.baseFor(debug = true, configured = "$stub/"))
        assertEquals(RelayAddress.OFFICIAL, RelayAddress.baseFor(debug = true, configured = ""))
    }

    @Test
    fun `relay addresses move onto the base, others stay`() {
        val register = se.sensnology.spotnav.push.PushRelay.REGISTER_URL
        assertEquals(stub + register.removePrefix(RelayAddress.OFFICIAL), RelayAddress.resolve(register, stub))
        assertEquals(register, RelayAddress.resolve(register, RelayAddress.OFFICIAL))
        assertEquals("https://example.com/x", RelayAddress.resolve("https://example.com/x", stub))
    }

    @Test
    fun `the push registration starts with the official base`() {
        listOf(se.sensnology.spotnav.push.PushRelay.REGISTER_URL)
            .forEach { assertEquals(it, true, it.startsWith(RelayAddress.OFFICIAL + "/")) }
    }
}
