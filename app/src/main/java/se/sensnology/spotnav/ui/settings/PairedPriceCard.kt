package se.sensnology.spotnav.ui.settings

import android.widget.LinearLayout
import android.widget.Toast
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of a paired charger: the confirmed area, VAT, energy tax and grid fee
 * as value rows, each opening the dialog to change them. Nothing is written until the
 * dialog's Save, and then through the paired settings path ([submit]); the screen owns that path.
 */
internal class PairedPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var old: WidgetSettings? = null
    private var record: HaPlanningSettings? = null
    private var writable = false
    private var submit: (SettingsFormValues, done: () -> Unit) -> Unit = { _, done -> done() }

    /** Add the overview to [parent]; [submit] carries one Save out and calls `done` when it has answered. */
    fun add(
        parent: LinearLayout,
        old: WidgetSettings,
        submit: (SettingsFormValues, done: () -> Unit) -> Unit
    ) {
        this.submit = submit
        this.old = old
        parent.addView(rows)
    }

    /** Paint the confirmed [record]; the button is enabled only while the authority lets it be written. */
    fun show(record: HaPlanningSettings?, writable: Boolean) {
        this.record = record
        this.writable = writable
        rows.removeAllViews()
        val overview = PriceOverview.of(record) { PriceMarkets.find(it) }
        val locale = AppLanguageSettings.numberLocale(activity)
        fun text(line: FiscalLine): String = when (line) {
            FiscalLine.Off -> t(R.string.price_value_off)
            FiscalLine.Unset -> t(R.string.value_not_set)
            FiscalLine.Included -> t(R.string.price_value_included)
            is FiscalLine.Figure -> PriceOverview.figureText(line, locale)
        }
        // Each row opens the price dialog while the record can be written; read-only otherwise.
        val open: (() -> Unit)? = if (writable && record != null) ({ old?.let { openDialog(it) } }) else null
        fun row(label: Int, value: String) = settingRow(rows, t(label), value, onTap = open)
        row(R.string.price_row_area, overview.area ?: t(R.string.value_not_set))
        row(R.string.price_row_vat, text(overview.vat))
        row(R.string.price_row_tax, text(overview.tax))
        row(R.string.price_row_transfer, text(overview.transfer))
    }

    private fun openDialog(old: WidgetSettings) {
        val confirmed = record ?: return
        if (!writable) return
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val controls = PriceSettingsCard(this).add(body, old, confirmed, writable)
        openSaveDialog(t(R.string.price_dialog_title), body) { dialog, save ->
            val invalid = buildSet {
                if (controls.tax() && ImmediateSettings.figure(controls.taxText()) is Figure.Invalid) add(FigureField.TAX)
                if (controls.transfer() && ImmediateSettings.figure(controls.transferText()) is Figure.Invalid) add(FigureField.GRID_FEE)
            }
            controls.showInvalid(invalid)
            if (invalid.isNotEmpty()) return@openSaveDialog
            if (!SettingsAreaController.canSave(controls.state())) {
                Toast.makeText(context, t(R.string.choose_available_area), Toast.LENGTH_LONG).show()
                return@openSaveDialog
            }
            save.isEnabled = false
            submit(
                ImmediateSettings.formValues(
                    controls.area(), controls.vat(), controls.tax(), controls.taxText(),
                    controls.transfer(), controls.transferText()
                )
            ) { dialog.dismiss() }
        }
    }
}
