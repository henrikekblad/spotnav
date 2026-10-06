package se.sensnology.spotnav.ui.settings

import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.NumberSpec
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.editNumber
import se.sensnology.spotnav.ui.common.editOnOff
import se.sensnology.spotnav.ui.common.openEditor
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The price card's four rows, paired or not: the area, VAT, energy tax and grid fee, each opening its
 * own editor (the area its picker, VAT on/off, each fee a number that can be off) and handing one
 * [PriceEdit] to [edit], which does the card's own write. A part the area's price already includes is
 * read-only, and so is everything while [editable] is false.
 */
internal class PriceRows(scope: ViewScope) : ViewScope(scope) {
    fun add(
        parent: LinearLayout,
        overview: PriceOverview,
        areaId: String?,
        /** The fee figures stored now, the editors' starting values (`null`: none). */
        taxFigure: Double?,
        transferFigure: Double?,
        editable: Boolean,
        /** The area picker's controls, built for the record (paired) or this phone's settings. */
        areaControls: (LinearLayout) -> PriceSettings,
        edit: (PriceEdit, (String?) -> Unit) -> Unit
    ) {
        val market = areaId?.let { PriceMarkets.find(it) }
        val locale = AppLanguageSettings.numberLocale(activity)
        fun text(line: FiscalLine): String = when (line) {
            FiscalLine.Off -> t(R.string.price_value_off)
            FiscalLine.Unset -> t(R.string.value_not_set)
            FiscalLine.Included -> t(R.string.price_value_included)
            is FiscalLine.Figure -> PriceOverview.figureText(line, locale)
        }
        settingRow(parent, t(R.string.price_row_area), overview.area ?: t(R.string.value_not_set),
            onTap = if (editable) ({ openArea(areaControls, edit) }) else null)
        val vatSettable = editable && overview.vat != FiscalLine.Included && (market?.vatPercent ?: 0.0) > 0.0
        settingRow(parent, t(R.string.price_row_vat), text(overview.vat), onTap = if (vatSettable) ({
            // What VAT is here, above the choice: "VAT 25 %", the area's own rate.
            val rate = market?.vatPercent?.let { java.text.NumberFormat.getNumberInstance(locale).format(it) }
            editOnOff(t(R.string.price_row_vat), overview.vat is FiscalLine.Figure, help = rate?.let { t(R.string.vat, it) }) { on, done ->
                edit(PriceEdit.Vat(on), done)
            }
        }) else null)
        val unit = market?.minorUnit?.let { "$it/kWh" }.orEmpty()
        fun fee(label: Int, kind: Fee, line: FiscalLine, figure: Double?, make: (Boolean, Double?) -> PriceEdit) {
            settingRow(parent, t(label), text(line), onTap = if (editable && line != FiscalLine.Included) ({
                editNumber(
                    title = t(label), spec = FEE, unit = unit,
                    current = figure.takeIf { line !is FiscalLine.Off },
                    rangeMessage = t(R.string.paired_error_number),
                    noneLabel = t(R.string.price_value_off),
                    // The area's own published figure, offered as the old form filled it in.
                    suggestion = suggestion(market, kind)
                ) { value, done -> edit(make(value != null, value), done) }
            }) else null)
        }
        fee(R.string.price_row_tax, Fee.TAX, overview.tax, taxFigure) { on, value -> PriceEdit.Tax(on, value) }
        fee(R.string.price_row_transfer, Fee.GRID, overview.transfer, transferFigure) { on, value -> PriceEdit.Transfer(on, value) }
    }

    /** The area: its picker (with where the prices come from, and a region search where there is one). */
    private fun openArea(areaControls: (LinearLayout) -> PriceSettings, edit: (PriceEdit, (String?) -> Unit) -> Unit) {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val controls = areaControls(body)
        val error = TextView(context).apply { textSize = 13f; setTextColor(0xFFD65C5C.toInt()); visibility = android.view.View.GONE }
        body.addView(error)
        openEditor(t(R.string.price_row_area), body, error) { done ->
            if (!SettingsAreaController.canSave(controls.state())) done(t(R.string.choose_available_area))
            else edit(PriceEdit.Area(controls.area()), done)
        }
    }

    /** The two fees an area may suggest a figure for. */
    enum class Fee { TAX, GRID }

    companion object {
        /** The figure [market] suggests for [fee], or `null` when it publishes none. */
        fun suggestion(market: se.sensnology.spotnav.prices.PriceMarket?, fee: Fee): Double? = when (fee) {
            Fee.TAX -> market?.suggestedTax
            Fee.GRID -> market?.suggestedGridFee
        }


        /** A fee per kWh in the area's minor unit: never negative, two decimals. */
        val FEE = NumberSpec(min = 0.0, max = 10_000.0, decimals = 2)

        /** The stored figure of one fee in a paired record, for the record's own area. */
        fun recordFigure(record: HaPlanningSettings?, component: se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent): Double? =
            record?.let { se.sensnology.spotnav.ha.settings.HaSettingsEditor.fiscalFor(it, component, it.areaId).value }

        /** A local figure, `null` when there is none. */
        fun localFigure(value: Double): Double? = value.takeIf { it != WidgetSettings.NO_SUGGESTION }
    }
}
