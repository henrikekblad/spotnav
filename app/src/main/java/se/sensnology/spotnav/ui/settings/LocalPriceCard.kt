package se.sensnology.spotnav.ui.settings

import android.widget.LinearLayout
import android.widget.Toast
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of an unpaired phone, drawn as the paired one is: the area, VAT,
 * energy tax and grid fee as value rows, each opening the price dialog (the same controls as before).
 * Its Save applies to this phone at once, through [apply], which names any figure it refused.
 */
internal class LocalPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var current: () -> WidgetSettings = { WidgetSettings() }
    private var apply: (PriceSettings) -> Set<FigureField> = { emptySet() }

    fun add(parent: LinearLayout, current: () -> WidgetSettings, apply: (PriceSettings) -> Set<FigureField>) {
        this.current = current
        this.apply = apply
        parent.addView(rows)
        show()
    }

    /** Paint this phone's stored price settings. */
    fun show() {
        rows.removeAllViews()
        val overview = PriceOverview.ofLocal(current()) { PriceMarkets.find(it) }
        val locale = AppLanguageSettings.numberLocale(activity)
        fun text(line: FiscalLine): String = when (line) {
            FiscalLine.Off -> t(R.string.price_value_off)
            FiscalLine.Unset -> t(R.string.value_not_set)
            FiscalLine.Included -> t(R.string.price_value_included)
            is FiscalLine.Figure -> PriceOverview.figureText(line, locale)
        }
        val open = { openDialog() }
        settingRow(rows, t(R.string.price_row_area), overview.area ?: t(R.string.value_not_set), onTap = open)
        settingRow(rows, t(R.string.price_row_vat), text(overview.vat), onTap = open)
        settingRow(rows, t(R.string.price_row_tax), text(overview.tax), onTap = open)
        settingRow(rows, t(R.string.price_row_transfer), text(overview.transfer), onTap = open)
    }

    private fun openDialog() {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val controls = PriceSettingsCard(this).add(body, current(), null, true)
        openSaveDialog(t(R.string.price_dialog_title), body) { dialog, _ ->
            if (!SettingsAreaController.canSave(controls.state())) {
                Toast.makeText(context, t(R.string.choose_available_area), Toast.LENGTH_LONG).show()
                return@openSaveDialog
            }
            val invalid = apply(controls)
            controls.showInvalid(invalid)
            if (invalid.isEmpty()) {
                dialog.dismiss()
                show()
            }
        }
    }
}
