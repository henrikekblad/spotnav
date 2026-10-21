package se.sensnology.spotnav.chargers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.EmptyWebhookAnswerException
import se.sensnology.spotnav.ha.client.WebhookGoneException
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.io.IOException

/** A charger profile whose webhook Home Assistant no longer recognises: the mark, and the slow check. */
class PairAgainTest {
    private val profile = ChargerProfile("local-a", "", "http://192.168.1.50:8123", "hook-a", remoteChargerId = "entry_a")
    private val marked = profile.copy(pairAgain = PairAgainMark(markedAt = 1_000L, checkedAt = 1_000L))

    @Test fun aGoneWebhookMarksTheProfile() {
        val after = PairAgain.afterRead(profile, WebhookGoneException(), now = 5_000L)
        assertEquals(PairAgainMark(5_000L, 5_000L), after.pairAgain)
        assertTrue(after.needsPairing)
    }

    @Test fun beingFoundGoneAgainKeepsWhenItWasFirstFound() {
        assertEquals(PairAgainMark(1_000L, 9_000L), PairAgain.afterRead(marked, WebhookGoneException(), now = 9_000L).pairAgain)
    }

    @Test fun anAnswerClearsTheMark() {
        assertNull(PairAgain.afterRead(marked, failure = null, now = 9_000L).pairAgain)
    }

    @Test fun offlineOrAnOrdinaryErrorNeitherMarksNorClears() {
        for (failure in listOf(IOException("offline"), EmptyWebhookAnswerException(), IllegalStateException("500"))) {
            assertEquals(profile, PairAgain.afterRead(profile, failure, now = 9_000L))
            assertEquals(PairAgainMark(1_000L, 9_000L), PairAgain.afterRead(marked, failure, now = 9_000L).pairAgain)
        }
    }

    @Test fun aMarkedWebhookIsAskedOnlyAtTheSlowChecksPace() {
        assertTrue(PairAgain.mayRead(profile, now = 0L))
        assertFalse(PairAgain.mayRead(marked, now = 1_000L + 60_000L))
        assertFalse(PairAgain.mayRead(marked, now = 1_000L + PairAgain.SLOW_CHECK_MS - 1))
        assertTrue(PairAgain.mayRead(marked, now = 1_000L + PairAgain.SLOW_CHECK_MS))
        // A clock set back is no reason to wait forever.
        assertTrue(PairAgain.mayRead(marked, now = 0L))
    }

    @Test fun theStoreClaimsOneSlowCheckAtATime() {
        val store = ChargerProfileStore(FakeKeyValueStore()) {}
        store.upsertProfile(profile)
        assertTrue(store.claimRead("local-a", now = 0L))
        assertTrue(store.claimRead("local-a", now = 1L))

        store.recordWebhookAnswer("local-a", WebhookGoneException(), now = 10_000L)
        assertTrue(store.getProfile("local-a")!!.needsPairing)
        assertFalse(store.claimRead("local-a", now = 70_000L))
        val due = 10_000L + PairAgain.SLOW_CHECK_MS
        assertTrue(store.claimRead("local-a", now = due))
        // Claimed: the next reader waits for the next one.
        assertFalse(store.claimRead("local-a", now = due + 1))

        // The webhook answered again (a restored backup): read as usual from then on.
        store.recordWebhookAnswer("local-a", null, now = due + 2)
        assertFalse(store.getProfile("local-a")!!.needsPairing)
        assertTrue(store.claimRead("local-a", now = due + 3))
    }

    @Test fun theMarkIsStoredWithTheProfile() {
        val backing = FakeKeyValueStore()
        ChargerProfileStore(backing) {}.upsertProfile(marked)

        val read = ChargerProfileStore(backing) {}.getProfile("local-a")
        assertEquals(marked, read)
        assertEquals(PairAgainMark(1_000L, 1_000L), read?.pairAgain)
    }

    @Test fun theMarkIsNeverPrintedWithTheWebhook() {
        assertFalse(marked.toString().contains("hook-a"))
    }
}
