package se.sensnology.spotnav.sites

import se.sensnology.spotnav.chargers.PairAgainMark
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import se.sensnology.spotnav.ha.pairing.SiteCharger

/**
 * One home (a SpotNav site in Home Assistant 3.0) and its webhook, the app's single door: the home's own actions
 * (house, heat, battery, its settings) and, naming `charger_id`, every action of each of its [chargers]. The chargers
 * themselves are [se.sensnology.spotnav.chargers.ChargerProfile]s inside it (their `siteId` is [siteId]), which keep
 * what is a charger's own (its car, its target, its widget, its notifications); [chargers] is the home's own list as
 * Home Assistant last stated it, so a charger added there later is found here without pairing again.
 */
data class SiteProfile(
    val localId: String,
    val baseUrl: String,
    val webhookId: String,
    /** The home's config entry id in Home Assistant. */
    val siteId: String,
    val name: String,
    val chargers: List<SiteCharger> = emptyList(),
    /** Set while Home Assistant no longer recognises [webhookId] (see [se.sensnology.spotnav.chargers.PairAgain]). */
    val pairAgain: PairAgainMark? = null
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && webhookId.isNotBlank()

    val needsPairing: Boolean get() = pairAgain != null

    /** The home's own actions: its webhook, no charger named. */
    fun toHomeAssistantSettings(): HomeAssistantSettings = HomeAssistantSettings(baseUrl, webhookId)

    /** Never the webhook id, which is a secret. */
    override fun toString(): String = "SiteProfile(localId=$localId, baseUrl=$baseUrl, webhookId=<redacted>, " +
        "siteId=$siteId, name=$name, chargers=$chargers, pairAgain=$pairAgain)"
}
