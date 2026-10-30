package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.dashboard.DashboardPriceProvider

/**
 * What a paired phone's Electricity price card says about Home Assistant's `prices.provider` (design 5.4): the
 * source line ("Prices from Nord Pool (via Home Assistant)"), and Home Assistant's notice when the area has no
 * source of its own, in the card's words. The notice's one action here is to open Home Assistant: the fix (an
 * integration, the ENTSO-E key) is made there, where it lives. Pure: the card hands in the block and the area.
 */
internal object HaPriceProviderText {
    /** One sentence: a string resource and its arguments, in order. */
    data class Sentence(val text: Int, val args: List<String> = emptyList())

    private val LINES = mapOf(
        "nordpool" to R.string.ha_price_provider_nordpool,
        "entsoe" to R.string.ha_price_provider_entsoe,
        "pvpc" to R.string.ha_price_provider_pvpc,
        "octopus" to R.string.ha_price_provider_octopus,
        "relay" to R.string.ha_price_provider_relay
    )

    /** The source line, or `null` for no source or one this app has no words for. */
    fun line(provider: DashboardPriceProvider?): Int? = provider?.key?.let(LINES::get)

    /**
     * Home Assistant's notice in words, with [area] (the area as the card names it) filled in, or `null` for no
     * notice or a code this app has no words for (the card shows none for it either).
     */
    fun notice(provider: DashboardPriceProvider?, area: String): Sentence? {
        val notice = provider?.notice ?: return null
        val region = notice.params["region"].orEmpty()
        return when (notice.code) {
            "add_nordpool" -> Sentence(R.string.ha_price_notice_add_nordpool, listOf(area))
            "nordpool_not_loaded" -> Sentence(R.string.ha_price_notice_nordpool_not_loaded)
            "add_entsoe_key" -> Sentence(R.string.ha_price_notice_add_entsoe_key, listOf(area))
            "entsoe_key_rejected" -> Sentence(R.string.ha_price_notice_entsoe_key_rejected)
            "add_pvpc" -> Sentence(R.string.ha_price_notice_add_pvpc)
            "pvpc_not_loaded" -> Sentence(R.string.ha_price_notice_pvpc_not_loaded)
            "add_octopus" -> Sentence(R.string.ha_price_notice_add_octopus, listOf(area))
            "octopus_not_loaded" -> Sentence(R.string.ha_price_notice_octopus_not_loaded)
            "octopus_no_agile" -> Sentence(R.string.ha_price_notice_octopus_no_agile, listOf(area))
            "octopus_region_mismatch" -> Sentence(R.string.ha_price_notice_octopus_region_mismatch, listOf(region, area))
            "source_missing" -> Sentence(R.string.ha_price_notice_source_missing, listOf(area))
            else -> null
        }
    }
}
