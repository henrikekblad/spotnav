package se.sensnology.spotnav.ui.site

import android.view.View
import se.sensnology.spotnav.ha.house.House

/** The period boxes the house page stacks under the Now box and its picker, in the card's order; [tabKey] names the box's heading. */
internal enum class HouseBox(val tabKey: String, val chart: String) {
    GRAPH("graph", "house.graph"),
    EARN("earn", "house.earn"),
    PRICE("price", "house.price")
}

/** What the page asks of a period box: read for the period, say it changed, draw (`false`: nothing to show, left out). */
internal interface HousePeriodBox {
    val panel: View

    /** The box came into view or its period changed: ask for what it draws. */
    fun open(force: Boolean = false)

    /** Every minute while in view: the running period is read again, a past one is not. */
    fun refresh()

    /** The shared picker moved: forget what was chosen on the old period. */
    fun periodChanged()

    fun render(): Boolean
}

/**
 * Which boxes the house page has under its Now box: the Energy flow, then the period boxes above. A box with nothing
 * to show is left out, never drawn empty (owner rule, plan section 8), decided from the answer's own fields: before
 * the house has answered there is none; the Energy flow is there where the day's energy flow exists; Pris och batteri
 * with a battery or with prices. (A box whose period turns out to hold nothing hides itself too, see
 * [HouseHistoryPanel.render].) The one shared period picker is drawn only while some box needs it.
 */
internal object HouseBoxes {
    /** The Energy flow box. */
    fun flow(house: House?): Boolean = house?.energyFlow != null

    fun shown(house: House?): List<HouseBox> = HouseBox.entries.filter { house != null && has(house, it) }

    /** The period boxes there are: the picker drives these. */
    fun periodBoxes(house: House?): Int = shown(house).size + if (flow(house)) 1 else 0

    fun picker(house: House?): Boolean = periodBoxes(house) > 0

    /** The card key of the box's heading: Pris och batteri is called Pris without a battery, as the card does. */
    fun titleKey(box: HouseBox, house: House?): String =
        if (box == HouseBox.PRICE && house != null && house.battery == null) "house.tab.priceOnly" else "house.tab.${box.tabKey}"

    private fun has(house: House, box: HouseBox): Boolean = when (box) {
        HouseBox.PRICE -> house.battery != null || house.hours.any { it.buy != null || it.sell != null }
        HouseBox.GRAPH -> true
        HouseBox.EARN -> house.battery != null || house.heat?.status == "found" || house.saving != null ||
            house.energyFlow?.nodes?.any { it.role == "car" } == true
    }
}
