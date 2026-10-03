package se.sensnology.spotnav.planning

import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ChartMarketBuild
import se.sensnology.spotnav.ha.authority.HaPlanningAdapter
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.prices.PriceMarket

/** One fiscal component of a record, resolved the **one** way. */
internal object FiscalResolution {
    /** One component, as the arithmetic sees it, and whether it was left without a figure. */
    data class Resolved(val input: FiscalInput, val unresolved: Boolean)

    fun component(fiscal: HaFiscalValue, suggestion: Double?): Resolved = when {
        !fiscal.enabled -> Resolved(FiscalInput.OFF, unresolved = false)
        fiscal.value != null ->
            Resolved(FiscalInput(enabled = true, overrideValue = fiscal.value, effectiveValue = fiscal.value), false)
        else -> {
            val usable = suggestion?.takeIf { it.isFinite() && it >= 0.0 }
            Resolved(FiscalInput.suggested(usable), unresolved = usable == null)
        }
    }

    /** The three components of one area, each resolved, and which of them came out unresolved. */
    data class ForArea(val vat: Resolved, val tax: Resolved, val transfer: Resolved) {
        /**
         * The components left **enabled with neither a stated figure nor a suggestion**: there is
         * no figure to add, so no all-in price can honestly be computed from them.
         */
        val unresolved: List<HaAreaOverrideComponent> get() = buildList {
            if (vat.unresolved) add(HaAreaOverrideComponent.VAT)
            if (tax.unresolved) add(HaAreaOverrideComponent.TAX)
            if (transfer.unresolved) add(HaAreaOverrideComponent.TRANSFER)
        }

        val isComplete: Boolean get() = unresolved.isEmpty()
    }

    /**
     * The three components the record states for **one area**: its own override for that area, and
     * that area's catalogue suggestions beside it.
     */
    fun forArea(record: HaPlanningSettings, areaId: String?, market: PriceMarket?): ForArea {
        // A component the published price already includes adds nothing and is never unresolved.
        val included = HaAreaOverrideComponent.includedFor(record, areaId, market)
        fun resolved(component: HaAreaOverrideComponent, suggestion: Double?): Resolved =
            if (component in included) Resolved(FiscalInput.OFF, unresolved = false)
            else component(HaSettingsEditor.fiscalFor(record, component, areaId), suggestion)
        return ForArea(
            vat = resolved(HaAreaOverrideComponent.VAT, market?.vatPercent),
            tax = resolved(HaAreaOverrideComponent.TAX, market?.suggestedTax),
            transfer = resolved(HaAreaOverrideComponent.TRANSFER, market?.suggestedGridFee)
        )
    }
}
