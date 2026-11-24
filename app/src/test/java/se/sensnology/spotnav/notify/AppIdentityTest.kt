package se.sensnology.spotnav.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.util.Base64

class AppIdentityTest {
    @Test
    fun theIdIsMadeOnceAndKept() {
        val store = FakeKeyValueStore()
        val first = AppIdentity(store).appId()
        assertEquals(first, AppIdentity(store).appId())
        assertNotEquals(first, AppIdentity(FakeKeyValueStore()).appId())
    }

    @Test
    fun theIdIsThirtyTwoBytesOfBase64Url() {
        val id = AppIdentity(FakeKeyValueStore()).appId()
        assertTrue(AppIdentity.VALID.matches(id))
        assertFalse(id.contains('='))
        assertEquals(32, Base64.getUrlDecoder().decode(id).size)
    }

    @Test
    fun aDamagedStoredIdIsReplaced() {
        val store = FakeKeyValueStore()
        store.putString("app_id", "short")
        assertTrue(AppIdentity.VALID.matches(AppIdentity(store).appId()))
    }

    @Test
    fun theIdIsNeverPrinted() {
        val identity = AppIdentity(FakeKeyValueStore())
        assertFalse(identity.toString().contains(identity.appId()))
    }
}
