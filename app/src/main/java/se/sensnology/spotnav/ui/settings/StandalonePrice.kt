package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.prices.MissingSource
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.entsoe.KeyState

/**
 * What an unpaired phone says about where its prices come from (design 5.1-5.3): the ENTSO-E key row of
 * the price card, the line under it, the notice in place of the charging screen's chart, and whether the
 * one-time explanation comes first. Pure: the screens hand in what is stored.
 */
internal object StandalonePrice {
    /** The key row's value: a short status word. */
    enum class KeyValue { ADDED, MISSING, NOT_ACCEPTED }

    /** The line under the area: where prices come from, or why none can. */
    enum class Line { OWN_KEY, NEEDS_HOME_ASSISTANT }

    /** The charging screen's notice, with its one action besides pairing. */
    enum class Notice { ADD_KEY, REPLACE_KEY, NEEDS_HOME_ASSISTANT }

    /** Whether ENTSO-E can price [area] at all: an area no key can price needs Home Assistant (O-A5). */
    fun routed(area: PriceMarket?): Boolean = area?.entsoe != null

    /** A key that is waiting for its first answer counts as added: it is used, and checked by that answer. */
    fun keyValue(state: KeyState): KeyValue = when (state) {
        KeyState.NONE -> KeyValue.MISSING
        KeyState.OK, KeyState.UNCHECKED -> KeyValue.ADDED
        KeyState.REJECTED -> KeyValue.NOT_ACCEPTED
    }

    /** The key row is offered only where a key can price the area. */
    fun keyRowShown(area: PriceMarket?): Boolean = routed(area)

    /**
     * The line under the area: "Prices from ENTSO-E (your key)" once a usable key prices it, "This area
     * needs Home Assistant" where no key can, and nothing while the key row itself says what is missing.
     */
    fun line(area: PriceMarket?, state: KeyState): Line? = when {
        !routed(area) -> Line.NEEDS_HOME_ASSISTANT
        state == KeyState.OK || state == KeyState.UNCHECKED -> Line.OWN_KEY
        else -> null
    }

    /** The notice for a subject no source can price; `null` when there is nothing the person can do here. */
    fun notice(missing: MissingSource?): Notice? = when (missing) {
        MissingSource.NO_KEY -> Notice.ADD_KEY
        MissingSource.KEY_REJECTED -> Notice.REPLACE_KEY
        MissingSource.NO_ROUTE -> Notice.NEEDS_HOME_ASSISTANT
        MissingSource.HA_TOO_OLD, MissingSource.HA_NOT_READ, null -> null
    }

    /**
     * Whether the plan card's answer ("No charging time can be calculated" and why) is shown: not beside a
     * notice, which already says what is needed; in every other case, as before.
     */
    fun showsPlanReadout(notice: Notice?): Boolean = notice == null

    /** The explanation comes before the how-to once, and only for someone who had prices from the server before. */
    fun explainFirst(legacyUser: Boolean, alreadyShown: Boolean): Boolean = legacyUser && !alreadyShown
}
