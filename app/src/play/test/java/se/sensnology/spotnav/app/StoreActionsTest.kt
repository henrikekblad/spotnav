package se.sensnology.spotnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.BuildConfig
import se.sensnology.spotnav.R

/** The Play build's Settings action: the source-code row, and nothing that asks for money. */
class StoreActionsTest {
    @Test
    fun theBuildIsThePlayStore() {
        assertEquals("play", BuildConfig.STORE)
    }

    @Test
    fun theActionIsTheRepositoryLinkWithItsNeutralGlyph() {
        val action = StoreActions.action

        assertEquals("https://github.com/henrikekblad/spotnav", action.url)
        assertEquals(R.string.store_action, action.labelRes)
        assertEquals(R.drawable.ic_store_action, action.iconRes)
        assertNull("the glyph takes the screen's accent, not a brand colour", action.iconTint)
    }

    @Test
    fun theAddressIsPlainHttpsWithNothingAppended() {
        val url = StoreActions.action.url

        assertTrue(url.startsWith("https://github.com/"))
        assertFalse(url.contains("sponsor", ignoreCase = true))
        assertFalse(url.contains('?') || url.contains('#') || url.contains('@'))
    }
}
