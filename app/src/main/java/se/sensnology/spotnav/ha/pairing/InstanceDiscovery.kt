package se.sensnology.spotnav.ha.pairing

import se.sensnology.spotnav.ha.client.HomeAssistantSettings

/** What mDNS found when the app went looking for Home Assistant. */
object InstanceDiscovery {
    /** One instance, as discovered. [name] is the advertised service name. */
    data class Found(val baseUrl: String, val name: String)

    /**
     * The instances worth showing: addresses this app may talk to, in discovery order, with
     * duplicates collapsed.
     */
    fun found(discovered: List<Found>, isAllowedBaseUrl: (String) -> Boolean): List<Found> =
        discovered
            .map { Found(it.baseUrl.trimEnd('/'), it.name) }
            .filter { isAllowedBaseUrl(it.baseUrl) }
            .distinctBy { it.baseUrl }
}
