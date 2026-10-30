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

    /** Paired with a Home Assistant that cannot answer `price_days` yet, and the relay is not allowed. */
    HA_TOO_OLD
}

/** The one source a subject's day files come from. There is no fallback between sources per attempt. */
internal sealed interface ChosenSource {
    data class HomeAssistant(val profileId: String) : ChosenSource
    data object Entsoe : ChosenSource
    data object Relay : ChosenSource
    data class None(val reason: MissingSource) : ChosenSource
}

/**
 * Which source prices a subject, as one pure rule (design section 1):
 *
 * 1. a subject that names a configured charger profile is Home Assistant's, when its last dashboard says
 *    it answers `price_days`; an older Home Assistant gets the relay while that is allowed, else nothing;
 * 2. else an area with an ENTSO-E route and a key that is held and not rejected uses ENTSO-E;
 * 3. else the relay, while that is allowed;
 * 4. else nothing, with the reason the UI shows.
 *
 * A paired charger never falls back to ENTSO-E: two sources for one charger would show two rates and two
 * truths about "now".
 */
internal object PriceSourceChoice {
    fun choose(
        subject: PriceSubject,
        profileConfigured: Boolean,
        haCanPriceDays: Boolean?,
        key: KeyState,
        area: PriceMarket?,
        legacyRelayAllowed: Boolean
    ): ChosenSource {
        val profileId = subject.profileId
        if (profileId != null && profileConfigured) {
            return when {
                haCanPriceDays == true -> ChosenSource.HomeAssistant(profileId)
                legacyRelayAllowed -> ChosenSource.Relay
                else -> ChosenSource.None(MissingSource.HA_TOO_OLD)
            }
        }
        val routed = area?.entsoe != null
        val usableKey = key == KeyState.OK || key == KeyState.UNCHECKED
        if (routed && usableKey) return ChosenSource.Entsoe
        if (legacyRelayAllowed) return ChosenSource.Relay
        return ChosenSource.None(
            when {
                !routed -> MissingSource.NO_ROUTE
                key == KeyState.REJECTED -> MissingSource.KEY_REJECTED
                else -> MissingSource.NO_KEY
            }
        )
    }
}
