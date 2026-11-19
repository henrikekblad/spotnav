package se.sensnology.spotnav.sites

import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.pairing.SiteCharger

/**
 * What one statement of a home (a pairing's `sites` element, `get_site_access`'s `site`, or `get_site` with the
 * webhook already held) means for the profiles this phone holds. Pure: the caller writes what it hands back.
 *
 * - The home becomes (or stays) a [SiteProfile], with the webhook and the chargers stated.
 * - Each charger it lists is reached through the home's webhook from now on: a charger profile already held (the
 *   same instance and charger id) keeps everything it remembers and only changes its door; one not held yet (added
 *   to the home in Home Assistant after pairing) becomes a new charger profile, with no pairing.
 * - A charger profile that used this home's webhook but is no longer listed goes back to its own webhook when it has
 *   one; without one it is left as it is (the home then refuses it, which the reconciliation shows).
 */
internal object SiteSync {
    data class Applied(
        val site: SiteProfile,
        /** The charger profiles to write: the home's chargers, and any that left it. */
        val chargers: List<ChargerProfile>,
        /** Charger profiles made for chargers the phone did not hold. */
        val added: List<String>,
        /** Charger profiles whose door changed: Home Assistant is told this phone's push reference again through it. */
        val moved: List<String>
    )

    fun apply(
        baseUrl: String,
        siteId: String,
        name: String,
        webhookId: String,
        listed: List<SiteCharger>,
        sites: List<SiteProfile>,
        chargers: List<ChargerProfile>,
        newSiteLocalId: () -> String,
        newChargerLocalId: () -> String
    ): Applied {
        val held = sites.firstOrNull { it.baseUrl == baseUrl && it.siteId == siteId }
        val site = held?.copy(webhookId = webhookId, name = name, chargers = listed, pairAgain = null)
            ?: SiteProfile(newSiteLocalId(), baseUrl, webhookId, siteId, name, listed)
        val written = mutableListOf<ChargerProfile>()
        val added = mutableListOf<String>()
        val moved = mutableListOf<String>()
        for (charger in listed) {
            val profile = chargers.firstOrNull { it.baseUrl == baseUrl && it.remoteChargerId == charger.id }
            if (profile == null) {
                val localId = newChargerLocalId()
                written += ChargerProfile(
                    localId = localId,
                    displayName = "",
                    baseUrl = baseUrl,
                    webhookId = "",
                    remoteChargerId = charger.id,
                    remoteChargerName = charger.name,
                    siteId = siteId,
                    siteWebhookId = webhookId
                )
                added += localId
                continue
            }
            if (profile.siteId == siteId && profile.siteWebhookId == webhookId) continue
            // A new door: whether the old one was gone says nothing about this one.
            written += profile.copy(siteId = siteId, siteWebhookId = webhookId, pairAgain = null)
            moved += profile.localId
        }
        val listedIds = listed.map { it.id }.toSet()
        chargers.filter { it.baseUrl == baseUrl && it.siteId == siteId && it.remoteChargerId !in listedIds && it.webhookId.isNotBlank() }
            .forEach { left ->
                written += left.copy(siteId = null, siteWebhookId = null)
                moved += left.localId
            }
        return Applied(site, written, added, moved)
    }
}
