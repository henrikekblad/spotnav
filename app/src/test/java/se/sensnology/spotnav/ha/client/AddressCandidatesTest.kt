package se.sensnology.spotnav.ha.client

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressCandidatesTest {
    private fun candidates(typed: String) = HomeAssistantSettings.addressCandidates(typed)

    @Test fun aBareIpTriesHomeAssistantsPortFirstThenTheSchemesOwn() {
        assertEquals(
            listOf(
                "http://192.168.1.20:8123", "https://192.168.1.20:8123",
                "https://192.168.1.20", "http://192.168.1.20"
            ),
            candidates(" 192.168.1.20/ ")
        )
    }

    @Test fun aTypedPortIsKeptWithBothSchemes() {
        assertEquals(listOf("http://homeassistant.local:8124", "https://homeassistant.local:8124"), candidates("homeassistant.local:8124"))
    }

    @Test fun aTypedSchemeIsKept() {
        assertEquals(listOf("https://ha.example.com:8123", "https://ha.example.com"), candidates("https://ha.example.com"))
        assertEquals(listOf("http://10.0.0.5:8123"), candidates("http://10.0.0.5:8123"))
    }

    @Test fun aPublicHostIsNeverTriedOverPlainHttp() {
        assertEquals(listOf("https://ha.example.com:8123", "https://ha.example.com"), candidates("ha.example.com"))
    }

    @Test fun nothingTypedIsNothingToTry() {
        assertEquals(emptyList<String>(), candidates("   "))
        assertEquals(emptyList<String>(), candidates("ftp://10.0.0.5"))
    }
}
