package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.widget.WidgetSettings

/** One price value, changed on its own row: the area, or one add-on (a figure of `null` keeps the one stored). */
internal sealed interface PriceEdit {
    data class Area(val areaId: String) : PriceEdit
    data class Vat(val on: Boolean) : PriceEdit
    data class Tax(val on: Boolean, val figure: Double?) : PriceEdit
    data class Transfer(val on: Boolean, val figure: Double?) : PriceEdit
}

/**
 * What one [PriceEdit] makes of the whole: the paired form's values (the record's own values for its
 * area, with that one field changed; a new area takes the record's values for that area), or this
 * phone's settings with that one field changed (a new area takes its suggested figures, as a pick did).
 */
internal object PriceEdits {
    fun paired(record: HaPlanningSettings, edit: PriceEdit): SettingsFormValues {
        val area = (edit as? PriceEdit.Area)?.areaId ?: record.areaId.orEmpty()
        fun of(component: HaAreaOverrideComponent) = HaSettingsEditor.fiscalFor(record, component, area)
        val vat = of(HaAreaOverrideComponent.VAT)
        val tax = of(HaAreaOverrideComponent.TAX)
        val transfer = of(HaAreaOverrideComponent.TRANSFER)
        val base = SettingsFormValues(
            areaId = area,
            vat = vat.enabled,
            tax = tax.enabled,
            taxFigure = tax.value,
            transfer = transfer.enabled,
            transferFigure = transfer.value
        )
        return when (edit) {
            is PriceEdit.Area -> base
            is PriceEdit.Vat -> base.copy(vat = edit.on)
            is PriceEdit.Tax -> base.copy(tax = edit.on, taxFigure = edit.figure ?: base.taxFigure)
            is PriceEdit.Transfer -> base.copy(transfer = edit.on, transferFigure = edit.figure ?: base.transferFigure)
        }
    }

    fun local(settings: WidgetSettings, edit: PriceEdit, market: (String) -> PriceMarket?): WidgetSettings = when (edit) {
        is PriceEdit.Area -> {
            val picked = market(edit.areaId)
            settings.copy(
                area = edit.areaId,
                taxMinorUnit = picked?.suggestedTax ?: settings.taxMinorUnit,
                gridFeeMinorUnit = picked?.suggestedGridFee ?: settings.gridFeeMinorUnit
            )
        }
        is PriceEdit.Vat -> settings.copy(vat = edit.on)
        is PriceEdit.Tax -> settings.copy(tax = edit.on, taxMinorUnit = edit.figure ?: settings.taxMinorUnit)
        is PriceEdit.Transfer -> settings.copy(transfer = edit.on, gridFeeMinorUnit = edit.figure ?: settings.gridFeeMinorUnit)
    }
}
