package se.sensnology.spotnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sponsors link, as a fact rather than a string literal inside a listener. */
class SponsorsTest {
    @Test
    fun theLinkIsOneHttpsSponsorsPageAndCarriesNothingElse() {
        val url = Sponsors.URL

        assertEquals("https://github.com/sponsors/${Sponsors.ACCOUNT}", url)
        assertTrue("the link leaves the app over https", url.startsWith("https://github.com/sponsors/"))
        // No query, fragment or user-info: a donation link must not be a channel for anything else.
        assertFalse("no query", url.contains('?'))
        assertFalse("no fragment", url.contains('#'))
        assertFalse("no credentials", url.contains('@'))
        assertEquals(1, url.split("?").size)
    }

    @Test
    fun theAccountIsTheOneTheRepositoryFunds() {
        // `.github/FUNDING.yml` in this repository carries exactly this account; the URL is built
        // from it so the two cannot drift apart silently.
        assertEquals("henrikekblad", Sponsors.ACCOUNT)
        assertEquals("https://github.com/sponsors/henrikekblad", Sponsors.URL)
    }
}

/** The GitHub build's Settings action is the Sponsors link. */
class GithubStoreActionTest {
    @Test
    fun theActionIsTheSponsorsLink() {
        assertEquals("github", se.sensnology.spotnav.BuildConfig.STORE)
        assertEquals(Sponsors.URL, StoreActions.action.url)
    }
}
