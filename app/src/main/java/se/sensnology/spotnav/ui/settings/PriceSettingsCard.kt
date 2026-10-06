package se.sensnology.spotnav.ui.settings

import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.PairedSettingsForm
import se.sensnology.spotnav.prices.AreaSelection
import se.sensnology.spotnav.prices.AreaSource
import se.sensnology.spotnav.prices.GreatBritainRegion
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
        // Great Britain's regions are listed under its own name, not the United Kingdom's.
        val countryLabel: (String) -> String = {
            if (it.equals(PriceMarket.GREAT_BRITAIN, ignoreCase = true)) t(R.string.area_heading_great_britain)
            else AreaSelection.countryLabel(it, locale)
        }
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
        // Where the selected area's prices come from, right under the choice it belongs to.
        val sourceLine = TextView(context).apply {
            textSize = 13f; setTextColor(muted); setPadding(0, dp(6), 0, 0)
            movementMethod = LinkMovementMethod.getInstance()
            visibility = View.GONE
        }
        parent.addView(sourceLine)
        val findRegion = FindRegionField(
            selectRegion = { id ->
                val index = choices.indexOfFirst { it.code == id }
                if (index >= 0) area.setSelection(index)
            },
            nameOf = { id -> PriceMarkets.find(id)?.selectorLabel ?: id },
            saveNeeded = paired
        )
        parent.addView(findRegion.view)
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
        // What each box stands for when a person has a say: a box the area's price already includes is
        // shown checked and locked, and what is saved for it stays the person's own choice (unpaired) or
        // the record's (paired, where it is never sent back as an edit).
        var vatChoice = vat.isChecked
        var taxChoice = tax.isChecked
        var transferChoice = transfer.isChecked
        // Set while this card changes a box itself, so that is not taken for a person's toggle.
        var settingBoxes = false
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
            settingBoxes = true
            if (paired) {
                vatChoice = HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.VAT, selected).enabled
                taxChoice = HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.TAX, selected).enabled
                transferChoice = HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.TRANSFER, selected).enabled
                taxValue.setText(
                    PairedSettingsForm.figureText(HaSettingsEditor.fiscalFor(confirmed, HaAreaOverrideComponent.TAX, selected))
                )
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
            // A part the published price already includes: checked, locked, saying so, and no figure.
            val included = HaAreaOverrideComponent.includedFor(confirmed, selected, market)
            fun lock(box: android.widget.CheckBox, field: EditText?, component: HaAreaOverrideComponent, label: Int, choice: Boolean) {
                val locked = component in included
                if (locked) {
                    box.text = t(R.string.fiscal_included, t(label))
                    box.isEnabled = false
                }
                box.isChecked = locked || choice
                field?.visibility = if (locked) View.GONE else View.VISIBLE
            }
            lock(vat, null, HaAreaOverrideComponent.VAT, R.string.price_row_vat, vatChoice)
            if (HaAreaOverrideComponent.TAX !in included) tax.text = t(R.string.tax)
            lock(tax, taxValue, HaAreaOverrideComponent.TAX, R.string.price_row_tax, taxChoice)
            if (HaAreaOverrideComponent.TRANSFER !in included) transfer.text = t(R.string.transfer)
            lock(transfer, transferValue, HaAreaOverrideComponent.TRANSFER, R.string.price_row_transfer, transferChoice)
            settingBoxes = false
            val source = market?.source
            sourceLine.visibility = if (source == null) View.GONE else View.VISIBLE
            if (source != null) sourceLine.text = sourceText(source)
            findRegion.show(controlsEnabled && GreatBritainRegion.offered(PriceMarkets.all, market, region))
            // Four cases, not two: NO, DK and FI publish a tax figure and no grid fee, so claiming
            // the relay publishes neither would be false about them. A price that already includes
            // a part says so instead.
            priceInfo.text = if (included.size == HaAreaOverrideComponent.entries.size) t(R.string.market_info_included)
            else if (included.isNotEmpty()) t(R.string.market_info_included_some)
            else when (FiscalSuggestions.of(market)) {
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
        vat.setOnCheckedChangeListener { _, checked -> if (!settingBoxes) { vatChoice = checked; onChange() } }
        tax.setOnCheckedChangeListener { _, checked -> if (!settingBoxes) { taxChoice = checked; onChange() } }
        transfer.setOnCheckedChangeListener { _, checked -> if (!settingBoxes) { transferChoice = checked; onChange() } }
        listOf(taxValue, transferValue).forEach { field -> commitOnLeave(field, onChange) }
        return PriceSettings(
            // The identity to save is the machine's own, so it is the area this screen stands for:
            // the saved one, or a choice not yet saved.
            area = { picker.selectedId.orEmpty() },
            // The person's own choice, never a locked box's "included" check.
            vat = { vatChoice },
            tax = { taxChoice },
            transfer = { transferChoice },
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

    /** "Price source: Octopus Energy (Agile)", the name linked to the source's own page. */
    private fun sourceText(source: AreaSource): CharSequence {
        val text = t(R.string.price_source, source.name)
        val start = text.lastIndexOf(source.name)
        return SpannableString(text).apply {
            if (start >= 0) setSpan(URLSpan(source.url), start, start + source.name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * "Find my region": a postcode, one button and one line saying what happened. The postcode is sent
     * only to Octopus Energy (never to the relay), and is neither saved nor logged; a found region is
     * selected in the picker as a person's own pick.
     */
    private inner class FindRegionField(
        private val selectRegion: (String) -> Unit,
        private val nameOf: (String) -> String,
        private val saveNeeded: Boolean
    ) {
        private val postcode = EditText(context).apply {
            hint = t(R.string.find_region_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Not kept across a re-creation of the screen either.
            isSaveEnabled = false
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        private val find = Button(context).apply { text = t(R.string.find_region_button); isAllCaps = false }
        private val outcome = TextView(context).apply {
            textSize = 13f; setTextColor(muted); visibility = View.GONE
        }
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(label(t(R.string.find_region_label)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(postcode, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(find)
            })
            addView(TextView(context).apply { text = t(R.string.find_region_description); textSize = 13f; setTextColor(muted) })
            addView(outcome)
        }

        init {
            find.setOnClickListener { run() }
            postcode.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { run(); true } else false
            }
        }

        fun show(offered: Boolean) {
            view.visibility = if (offered) View.VISIBLE else View.GONE
        }

        private fun say(text: String) {
            outcome.text = text
            outcome.visibility = View.VISIBLE
        }

        private fun run() {
            val typed = postcode.text.toString()
            if (typed.isBlank()) {
                say(t(R.string.find_region_invalid))
                return
            }
            find.isEnabled = false
            val catalogue = PriceMarkets.all
            Thread {
                val answer = GreatBritainRegion.find(typed, catalogue, GreatBritainRegion::httpGet)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    find.isEnabled = true
                    when (answer) {
                        is GreatBritainRegion.Answer.Found -> {
                            val name = nameOf(answer.region)
                            say(t(if (saveNeeded) R.string.find_region_found_save else R.string.find_region_found, name))
                            selectRegion(answer.region)
                        }
                        GreatBritainRegion.Answer.Invalid -> say(t(R.string.find_region_invalid))
                        GreatBritainRegion.Answer.NotFound -> say(t(R.string.find_region_not_found))
                        GreatBritainRegion.Answer.Unavailable -> say(t(R.string.find_region_unavailable))
                    }
                }
            }.apply { isDaemon = true }.start()
        }
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
