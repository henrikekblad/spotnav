package se.sensnology.spotnav.chart

import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.FiscalArithmetic
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.planning.FiscalResolution
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarket

/**
 * What a price graph needs to know about a market, and nothing a plan needs.
 *
 * The area, the aggregation interval and the three fiscal components. It carries no phases, amperes,
 * energy, target or departure, so it cannot be turned into a plan by accident (an `auto_price`
 * charger has an authoritative market but must never have planning inputs). [apply] delegates to
 * [FiscalArithmetic], the one formula.
 */
internal data class ChartMarket(
    /** Which price area's market these prices are. */
    val areaId: String,
    /**
     * The presentation resolution the price series is aggregated to: 15 or 60 minutes, never the
     * source's (15, 30 or 60), which is already on the quarter-hour grid: a half-hour is two equal
     * quarters at 15 and half an hour of the average at 60.
     */
    val intervalMinutes: Int,
    val vat: FiscalInput,
    val tax: FiscalInput,
    val transfer: FiscalInput
) {
    init {
        // Market-presentation invariants only; the fiscal components validate themselves (see FiscalInput).
        require(areaId.isNotBlank()) { "a chart needs an area" }
        require(intervalMinutes == 15 || intervalMinutes == 60) { "intervalMinutes must be 15 or 60" }
    }

    val hourly: Boolean get() = intervalMinutes == 60

    val aggregationMinutes: Int get() = intervalMinutes

    fun apply(localMajorPerKwh: Double): Double =
        FiscalArithmetic.apply(localMajorPerKwh, vat = vat, tax = tax, transfer = transfer)

    companion object {
        /** The market a locally calculated plan is drawn in: the calculation inputs minus everything planning-shaped. */
        fun of(inputs: PlanningInputs): ChartMarket = ChartMarket(
            areaId = inputs.areaId,
            intervalMinutes = inputs.intervalMinutes,
            vat = inputs.vat,
            tax = inputs.tax,
            transfer = inputs.transfer
        )

        /**
         * The market an authoritative record is drawn in, at the screen's own presentation [intervalMinutes].
         *
         * [areaId] is the effective area and [market] its catalogue entry. Fiscal components come from
         * [FiscalResolution], so a drawn and a planned price of one row agree. A record that enables a
         * component with neither figure nor suggestion yields [ChartMarketBuild.Incomplete] and no market.
         */
        fun from(
            record: HaPlanningSettings,
            areaId: String,
            intervalMinutes: Int,
            market: PriceMarket?
        ): ChartMarketBuild {
            val components = FiscalResolution.forArea(record, areaId, market)
            if (!components.isComplete) return ChartMarketBuild.Incomplete(components.unresolved)
            return ChartMarketBuild.Ready(
                ChartMarket(
                    areaId = areaId,
                    intervalMinutes = intervalMinutes,
                    vat = components.vat.input,
                    tax = components.tax.input,
                    transfer = components.transfer.input
                )
            )
        }
    }
}

/**
 * What building a chart-only market from an authoritative record produced.
 *
 * A component enabled with no figure and no catalogue suggestion is absent, not zero, and a total
 * computed as if it were zero would pass for the charger's all-in price. So such a record yields
 * [Incomplete] and the caller shows its unavailable state. A disabled component contributes nothing;
 * an enabled one uses its explicit figure (zero included), else the area's suggestion.
 */
internal sealed interface ChartMarketBuild {
    data class Ready(val market: ChartMarket) : ChartMarketBuild

    /** No market: [unresolved] are the components enabled with no figure and no suggestion, in order VAT, tax, transfer. */
    data class Incomplete(val unresolved: List<HaAreaOverrideComponent>) : ChartMarketBuild
}
