package se.sensnology.spotnav.ui.settings

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.PairedSettingsForm
import se.sensnology.spotnav.prices.AreaSelection
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.checkbox
import se.sensnology.spotnav.ui.common.label
import se.sensnology.spotnav.ui.common.numberField
import se.sensnology.spotnav.widget.WidgetSettings
import java.util.Locale

/**
 * The price controls of the settings screen: area and the fiscal add-ons, and the display resolution
 * that is a setting of this phone alone. Unpaired they sit in the "Electricity price" card and each
 * change is handed to `onChange` the moment it is made; paired the area and fiscal controls are
 * built into the dialog of the card's Change button, and nothing is handed on until its Save.
 */
internal class PriceSettingsCard(scope: ViewScope) : ViewScope(scope) {
    /**
     * A catalogue figure as the fee fields hold it: digits with a dot, whatever the phone's locale
     * is.
     */
    private fun plainFigure(value: Double): String = String.format(Locale.ROOT, "%s", value)

    /**
     * The "Electricity price" section: which market's prices to use, at which resolution, and the
     * three add-ons that depend on it (VAT, tax and the transfer fee). One piece because the market
     * selection rewrites the other fields:
     */
    fun add(
        parent: LinearLayout,
        old: WidgetSettings,
        /** The confirmed record this screen must show, or `null` for a local widget. */
        confirmed: HaPlanningSettings?,
        /** Whether that record may be edited right now (see VisibleAuthority.writable). */
        writable: Boolean,
        /** Called after each change a person makes (a pick, a toggle, a committed figure). */
        onChange: () -> Unit = {},
        /** Adds controls between the area and the fiscal add-ons (the resolution, when unpaired). */
        afterArea: (LinearLayout) -> Unit = {}
    ): PriceSettings {
        // Paired: Unpaired: exactly as before.
        val paired = confirmed != null
        val recordArea = confirmed?.areaId
        // Whether the paired controls may be edited at all: a pending, offline or conflicting
        // authority shows the confirmed values and offers no write (see VisibleAuthority).
        val controlsEnabled = writable
        parent.addView(label(t(R.string.area)))
        data class AreaChoice(val label: String, val code: String? = null) {
            override fun toString() = label
        }
        val region = AppLanguageSettings.region(context)
        val locale = AppLanguageSettings.locale(context)
        val countryLabel: (String) -> String = { AreaSelection.countryLabel(it, locale) }
        val unavailableLabel: (String) -> String = { t(R.string.area_unavailable, it) }
        // A row can be a heading or the unavailable placeholder and carry no id at all, so reading
        // the selection back out of the adapter is what let an unsaved pick that a refresh retired
        // be silently replaced by the saved area.
        var picker = AreaPickerState(
            // A record that states no area shows "not set", never this phone's own default area.
            savedId = if (paired) recordArea.orEmpty() else old.area,
            areas = PriceMarkets.all,
            region = region,
            countryLabel = countryLabel,
            unavailableLabel = unavailableLabel,
            notSetLabel = if (paired) t(R.string.value_not_set) else null
        )
        val choices = mutableListOf<AreaChoice>()
        val adapter = object : ArrayAdapter<AreaChoice>(context, android.R.layout.simple_spinner_dropdown_item, choices) {
            // A heading and the unavailable placeholder are labels, not choices:
            override fun isEnabled(position: Int) = getItem(position)?.code != null
            override fun areAllItemsEnabled() = false
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    val heading = getItem(position)?.code == null
                    typeface = if (heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (heading) muted else dark)
                    setPadding(dp(if (heading) 12 else 26), dp(10), dp(12), dp(10))
                }
            }
        }
        val area = Spinner(context).apply { this.adapter = adapter }
        // No suppression flag around this:
        fun replaceChoices(view: AreaPickerView) {
            choices.clear()
            view.rows.forEach { choices.add(AreaChoice(it.label, it.id)) }
            adapter.notifyDataSetChanged()
            if (view.selectedIndex in choices.indices) area.setSelection(view.selectedIndex)
        }
        replaceChoices(picker.open())
        parent.addView(area)
        afterArea(parent)
        val priceInfo = TextView(context).apply {
            textSize = 13f; setTextColor(muted); setPadding(0, dp(14), 0, dp(8))
        }
        parent.addView(priceInfo)
        // The record's own state for the area the form stands for, read once for the controls'
        // initial values:
        val recordVat = confirmed?.let { HaSettingsEditor.fiscalFor(it, HaAreaOverrideComponent.VAT, recordArea) }
        val recordTax = confirmed?.let { HaSettingsEditor.fiscalFor(it, HaAreaOverrideComponent.TAX, recordArea) }
        val recordTransfer = confirmed?.let { HaSettingsEditor.fiscalFor(it, HaAreaOverrideComponent.TRANSFER, recordArea) }
        val vat = checkbox(t(R.string.vat, "25"), recordVat?.enabled ?: old.vat)
        val tax = checkbox(t(R.string.tax), recordTax?.enabled ?: old.tax)
        val taxValue = numberField(old.taxMinorUnit, t(R.string.tax_hint, "öre"))
        val transfer = checkbox(t(R.string.transfer), recordTransfer?.enabled ?: old.transfer)
        val transferValue = numberField(old.gridFeeMinorUnit, t(R.string.transfer_hint, "öre"))
        if (recordTax != null) taxValue.setText(PairedSettingsForm.figureText(recordTax))
        if (recordTransfer != null) transferValue.setText(PairedSettingsForm.figureText(recordTransfer))
        parent.addView(vat); parent.addView(tax); parent.addView(taxValue); parent.addView(transfer); parent.addView(transferValue)
        // The identity the labels describe is the form's own state, never the Spinner's selected
        // row:
        fun currentAreaId(): String = picker.selectedId.orEmpty()
        fun updateMarketText(applySuggestions: Boolean) {
            val selected = currentAreaId()
            val market = PriceMarkets.find(selected)
            // VAT has three states here, not two.
            val vatPercent = market?.vatPercent
            val vatText = vatPercent?.toString()?.replace(".0", "")?.replace('.', ',')
            vat.isEnabled = controlsEnabled && vatPercent != null && vatPercent > 0.0
            vat.text = when {
                vatPercent == null -> t(R.string.vat_unknown, selected)
                vatPercent == 0.0 -> t(R.string.vat_zero, selected)
                else -> t(R.string.vat, vatText.orEmpty())
            }
            // Both intervals are always offered, for every area and every day:
            area.isEnabled = controlsEnabled
            tax.isEnabled = controlsEnabled
            taxValue.isEnabled = controlsEnabled
            transfer.isEnabled = controlsEnabled
            transferValue.isEnabled = controlsEnabled
            val minorUnit = market?.minorUnit.orEmpty()
            taxValue.hint = t(R.string.tax_hint, minorUnit)
            transferValue.hint = t(R.string.transfer_hint, minorUnit)
            if (paired) {
                vat.isChecked = HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.VAT, selected).enabled
                tax.isChecked = HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.TAX, selected).enabled
                taxValue.setText(
                    PairedSettingsForm.figureText(HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.TAX, selected))
                )
                transfer.isChecked = HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.TRANSFER, selected).enabled
                transferValue.setText(
                    PairedSettingsForm.figureText(
                        HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.TRANSFER, selected)
                    )
                )
            } else if (applySuggestions) {
                // Only a person's own selection reaches this:
                market?.suggestedTax?.let { taxValue.setText(plainFigure(it)) }
                market?.suggestedGridFee?.let { transferValue.setText(plainFigure(it)) }
            }
            // Four cases, not two: NO, DK and FI publish a tax figure and no grid fee, so claiming
            // the relay publishes neither would be false about them.
            priceInfo.text = when (FiscalSuggestions.of(market)) {
                FiscalSuggestion.BOTH -> t(
                    R.string.market_info,
                    market!!.suggestedTax.toString(), market.minorUnit,
                    market.suggestedGridFee.toString()
                )
                FiscalSuggestion.TAX_ONLY -> t(
                    R.string.market_info_tax_only,
                    market!!.suggestedTax.toString(), market.minorUnit
                )
                FiscalSuggestion.GRID_ONLY -> t(
                    R.string.market_info_grid_only,
                    market!!.suggestedGridFee.toString(), market.minorUnit
                )
                FiscalSuggestion.NEITHER ->
                    if (market == null) t(R.string.area_unavailable, selected)
                    else t(R.string.market_info_neither)
            }
        }
        area.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // The row's own id, checked against what the machine installed.
                val picked = picker.onRowSelected(position, choices.getOrNull(position)?.code) ?: return
                updateMarketText(applySuggestions = picked.userPicked)
                if (picked.userPicked) onChange()
            }
        }
        updateMarketText(applySuggestions = false)
        // A toggle is a change at once; a figure is one when its field is left or IME-done is pressed.
        // Only a person's own touch reaches these listeners: the paired refills set state in code
        // before they exist for a dialog a person has not touched yet.
        listOf(vat, tax, transfer).forEach { box -> box.setOnCheckedChangeListener { _, _ -> onChange() } }
        listOf(taxValue, transferValue).forEach { field -> commitOnLeave(field, onChange) }
        return PriceSettings(
            // The identity to save is the machine's own, so it is the area this screen stands for:
            // the saved one, or a choice not yet saved.
            area = { picker.selectedId.orEmpty() },
            vat = { vat.isChecked },
            tax = { tax.isChecked },
            transfer = { transfer.isChecked },
            taxText = { taxValue.text.toString() },
            transferText = { transferValue.text.toString() },
            showInvalid = { invalid ->
                val message = t(R.string.paired_error_number)
                taxValue.error = if (FigureField.TAX in invalid) message else null
                transferValue.error = if (FigureField.GRID_FEE in invalid) message else null
            },
            state = { picker.state },
            applyCatalogue = { areas ->
                // Only the picker and the labels that depend on the selected area are touched.
                val refreshed = picker.onCatalogueRefreshed(areas)
                if (refreshed.replaceAdapter) replaceChoices(refreshed)
                updateMarketText(applySuggestions = false)
            }
        )
    }

    /** The resolution radio group: a setting of this phone, applied as soon as it is chosen. */
    fun addResolution(parent: LinearLayout, old: WidgetSettings, onChange: () -> Unit): () -> Int {
        parent.addView(label(t(R.string.resolution)))
        val interval = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val quarterId = View.generateViewId()
        val hourId = View.generateViewId()
        interval.addView(RadioButton(context).apply {
            id = quarterId; text = t(R.string.quarter); isChecked = old.intervalMinutes == PresentationIntervals.QUARTER_HOUR_MINUTES
        })
        interval.addView(RadioButton(context).apply {
            id = hourId; text = t(R.string.hour); isChecked = old.intervalMinutes == PresentationIntervals.HOUR_MINUTES
        })
        parent.addView(interval)
        interval.setOnCheckedChangeListener { _, _ -> onChange() }
        return { if (interval.checkedRadioButtonId == hourId) PresentationIntervals.HOUR_MINUTES else PresentationIntervals.QUARTER_HOUR_MINUTES }
    }

    /** Commit a field when it loses focus or the keyboard's Done is pressed. */
    private fun commitOnLeave(field: EditText, commit: () -> Unit) {
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                // Leaving the field commits it (once, through the focus listener).
                field.clearFocus()
                true
            } else false
        }
    }
}

/**
 * What [PriceSettingsCard.add] hands back: the controls' current values, read on demand, so the
 * screen never reads the section's views itself.
 */
internal class PriceSettings(
    val area: () -> String,
    val vat: () -> Boolean,
    val tax: () -> Boolean,
    val transfer: () -> Boolean,
    val taxText: () -> String,
    val transferText: () -> String,
    /** Put the existing "not a number" error under the named fields, and take it off the others. */
    val showInvalid: (Set<FigureField>) -> Unit,
    /** What the picker represents. */
    val state: () -> AreaSelectionState,
    /** Apply a refreshed catalogue to the picker and its labels only. */
    val applyCatalogue: (List<PriceMarket>) -> Unit
)
