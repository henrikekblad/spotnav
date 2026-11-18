package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House

/**
 * Which boxes the house page stacks under its Now box. A box with nothing to show is left out, never drawn empty, and
 * the shared period picker is drawn only while some box under it needs it. Decided from the answers' own blocks:
 * the Energy flow where the day's energy flow exists (the site has a grid meter in the Energy dashboard).
 * Later boxes (chart, earnings, price and battery) add their own rule here.
 */
internal object HouseBoxes {
    /** The Energy flow box. */
    fun flow(house: House?): Boolean = house?.energyFlow != null

    /** The period boxes there are: the picker drives these. */
    fun periodBoxes(house: House?): Int = if (flow(house)) 1 else 0

    fun picker(house: House?): Boolean = periodBoxes(house) > 0
}
