package se.sensnology.spotnav.ha.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantSettings

/** What mDNS found, and what to do about it: */
class InstanceDiscoveryTest {
    private val allowed: (String) -> Boolean = { value -> HomeAssistantSettings.isAllowedBaseUrl(value) }

    private fun found(vararg addresses: String) =
        addresses.map { InstanceDiscovery.Found(it, name = "Home") }

    @Test fun onlyAddressesThisAppMayTalkToAreOffered() {
        val result = InstanceDiscovery.found(
            listOf(
                InstanceDiscovery.Found("http://192.168.1.50:8123", "Home"),
                InstanceDiscovery.Found("http://example.com:8123", "Someone else"),
                InstanceDiscovery.Found("https://ha.example.com", "Remote")
            ),
            allowed
        )

        assertEquals(listOf("http://192.168.1.50:8123", "https://ha.example.com"), result.map { it.baseUrl })
    }

    @Test fun theSameInstanceAnsweringTwiceIsOneInstance() {
        // The same machine advertising twice, or two services resolving to one address.
        val result = InstanceDiscovery.found(
            listOf(
                InstanceDiscovery.Found("http://192.168.1.50:8123", "Home"),
                InstanceDiscovery.Found("http://192.168.1.50:8123/", "Home again")
            ),
            allowed
        )

        assertEquals(1, result.size)
        assertEquals("Home", result.single().name)
        assertEquals("http://192.168.1.50:8123", result.single().baseUrl)
    }

    @Test fun everythingDiscoveredBeingUnusableIsNothingFound() {
        assertTrue(InstanceDiscovery.found(found("http://example.com"), allowed).isEmpty())
    }
}
