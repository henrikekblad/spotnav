package se.sensnology.spotnav.ui.settings

import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueRow
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of a paired charger: the confirmed area, VAT, energy tax and grid fee
 * as an overview, and a button that opens the dialog to change them. Nothing is written until the
 * dialog's Save, and then through the paired settings path ([submit]); the screen owns that path.
 */
internal class PairedPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var change: Button
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
        parent.addView(rows)
        change = Button(context).apply {
            text = t(R.string.price_change)
            isAllCaps = false
            setOnClickListener { openDialog(old) }
        }
        parent.addView(change, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
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
        fun row(label: Int, value: String) = valueRow(rows, t(label), valueLabel().apply { this.text = value })
        row(R.string.price_row_area, overview.area ?: t(R.string.value_not_set))
        row(R.string.price_row_vat, text(overview.vat))
        row(R.string.price_row_tax, text(overview.tax))
        row(R.string.price_row_transfer, text(overview.transfer))
        change.isEnabled = writable && record != null
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
