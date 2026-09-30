package se.sensnology.spotnav.ha.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore

/** The one thing the app remembers about the instance it is connected to. */
class HomeAssistantInstanceStoreTest {
    private val store = FakeKeyValueStore()
    private val instances = HomeAssistantInstanceStore(store)

    @Test fun nothingIsStoredBeforePairing() {
        assertNull(instances.baseUrl())
        assertFalse(store.rawOrNull("base_url") != null)
    }

    @Test fun anInstanceSurvivesARestart() {
        instances.remember("http://192.168.1.50:8123")

        // A fresh instance over the same storage -- what a restart looks like.
        assertEquals("http://192.168.1.50:8123", HomeAssistantInstanceStore(store).baseUrl())
    }

    @Test fun aTrailingSlashIsNotPartOfTheIdentity() {
        instances.remember("http://192.168.1.50:8123/")

        assertEquals("http://192.168.1.50:8123", instances.baseUrl())
    }

    @Test fun removingForgetsIt() {
        instances.remember("http://192.168.1.50:8123")

        instances.forget()

        assertNull(instances.baseUrl())
    }

    @Test fun aBlankAddressIsNotAnInstance() {
        instances.remember("   ")

        assertNull(instances.baseUrl())
    }
}
