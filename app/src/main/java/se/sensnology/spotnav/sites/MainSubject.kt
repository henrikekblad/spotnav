package se.sensnology.spotnav.sites

import se.sensnology.spotnav.chargers.ChargerProfile

/** Whether the main screen shows a charger or a home without one. Pure. */
internal object MainSubject {
    /**
     * The home the main screen shows, or `null` for the charger screen: a charger the screen resolved always wins
     * (a home's chargers are shown on the charger page, with its switcher, as before). With none: the home chosen in
     * Settings ([activeSiteId]), else the first home without a charger the phone holds.
     */
    fun site(resolved: ChargerProfile?, chargers: List<ChargerProfile>, sites: List<SiteProfile>, activeSiteId: String?): SiteProfile? {
        if (resolved?.configured == true) return null
        sites.firstOrNull { it.localId == activeSiteId }?.let { return it }
        return sites.firstOrNull { site -> chargers.none { it.baseUrl == site.baseUrl && it.siteId == site.siteId } }
    }

    /** What the remembered page choices of a home are kept under, apart from any charger profile's. */
    fun pagesKey(site: SiteProfile): String = "site:${site.localId}"
}
