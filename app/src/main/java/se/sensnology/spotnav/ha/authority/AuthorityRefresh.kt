package se.sensnology.spotnav.ha.authority

import se.sensnology.spotnav.ha.settings.SettingsUpdate

/** What one price load -- and one authority answer -- belongs to. */
internal data class PriceRequestKey(val profileId: String?, val areaId: String, val generation: Int)

internal object AuthorityRefresh {
    /** Whether a settings answer must be followed by one fresh dashboard read. */
    fun settingsChangeNeedsDashboardReload(answer: SettingsUpdate.Outcome): Boolean =
        answer is SettingsUpdate.Outcome.Updated

    /** The area a price load must use. */
    fun priceArea(localArea: String?, authority: VisibleAuthority?): String? = when (authority) {
        null -> localArea
        is VisibleAuthority.LocalOwner -> localArea
        else -> authority.remoteSettings?.areaId ?: localArea
    }

    /**
     * Whether a late answer may still render: only while its key is exactly the screen's current
     * one.
     */
    fun accepts(answer: PriceRequestKey?, current: PriceRequestKey?): Boolean =
        answer != null && answer == current

    /** Whether adopting a new effective area must start exactly one new price load. */
    fun areaChangeRequiresLoad(previousArea: String?, nextArea: String?): Boolean =
        previousArea != null && nextArea != null && previousArea != nextArea
}
