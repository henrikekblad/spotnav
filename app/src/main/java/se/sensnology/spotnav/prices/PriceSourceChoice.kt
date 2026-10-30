package se.sensnology.spotnav.prices

import se.sensnology.spotnav.prices.entsoe.KeyState

/** Why no source can price a subject: each is something the person can act on, or a fact the UI states. */
internal enum class MissingSource {
    /** Standalone, the area has an ENTSO-E route, and no key is held. */
    NO_KEY,

    /** Standalone, and ENTSO-E refused the held key. */
    KEY_REJECTED,

    /** Standalone, and the area has no ENTSO-E route (or is not in the list). */
    NO_ROUTE,

    /** Paired with a Home Assistant that cannot answer `price_days`: updating SpotNav there fixes it. */
    HA_TOO_OLD,

    /** Paired, and no dashboard has said yet whether Home Assistant answers `price_days`: nothing to act on. */
    HA_NOT_READ;

    /** Whether this is a paired charger's: its prices are Home Assistant's alone, held or asked for. */
    val paired: Boolean get() = this == HA_TOO_OLD || this == HA_NOT_READ
}

/** The one source a subject's day files come from. There is no fallback between sources per attempt. */
internal sealed interface ChosenSource {
    data class HomeAssistant(val profileId: String) : ChosenSource
    data object Entsoe : ChosenSource
    data class None(val reason: MissingSource) : ChosenSource
}

/**
 * Which source prices a subject, as one pure rule (design section 1):
 *
 * 1. a subject that names a configured charger profile is Home Assistant's, when its last dashboard says
 *    it answers `price_days`; an older Home Assistant gets nothing (it is updated, the relay is never
 *    asked for a paired charger), and so does one whose dashboard has not been read yet;
 * 2. else an area with an ENTSO-E route and a key that is held and not rejected uses ENTSO-E;
 * 3. else nothing, with the reason the UI shows.
 *
 * The relay is no source: SpotNav's server no longer passes prices on, so a phone that used to get them
 * from it gets them like everyone else (the owner's hard cut, no grace period). A paired charger never falls
 * back to ENTSO-E: two sources for one charger would show two rates and two truths about "now".
 */
internal object PriceSourceChoice {
    fun choose(
        subject: PriceSubject,
        profileConfigured: Boolean,
        haCanPriceDays: Boolean?,
        key: KeyState,
        area: PriceMarket?
    ): ChosenSource {
        val profileId = subject.profileId
        if (profileId != null && profileConfigured) {
            return when (haCanPriceDays) {
                true -> ChosenSource.HomeAssistant(profileId)
                false -> ChosenSource.None(MissingSource.HA_TOO_OLD)
                null -> ChosenSource.None(MissingSource.HA_NOT_READ)
            }
        }
        val routed = area?.entsoe != null
        val usableKey = key == KeyState.OK || key == KeyState.UNCHECKED
        if (routed && usableKey) return ChosenSource.Entsoe
        return ChosenSource.None(
            when {
                !routed -> MissingSource.NO_ROUTE
                key == KeyState.REJECTED -> MissingSource.KEY_REJECTED
                else -> MissingSource.NO_KEY
            }
        )
    }
}
