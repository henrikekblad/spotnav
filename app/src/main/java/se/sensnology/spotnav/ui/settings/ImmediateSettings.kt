package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsEditor
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.widget.WidgetSettings
import java.text.NumberFormat
import java.util.Locale

/** A fee figure field, as a person typed it. */
internal enum class FigureField { TAX, GRID_FEE }

/** One figure field's text, read once. */
internal sealed interface Figure {
    /** An empty field is a figure of zero, as it always was. */
    data class Value(val amount: Double) : Figure

    /** Not a number, or not one a fee can be. */
    data object Invalid : Figure
}

/** What the phone's own controls say right now, before they are applied. */
internal data class LocalSettingsDraft(
    val area: String,
    val vat: Boolean,
    val tax: Boolean,
    val taxText: String,
    val transfer: Boolean,
    val transferText: String,
    val intervalMinutes: Int,
    val showChargingPlan: Boolean
)

/** What applying a draft comes to: the settings to store, and the fields that were refused. */
internal data class LocalApplication(val settings: WidgetSettings, val invalid: Set<FigureField>)

/**
 * The phone-only settings apply the moment they change (see the settings screen). This is the
 * decision, free of any view: what a draft makes of the stored settings.
 */
internal object ImmediateSettings {
    /** A figure field's text: blank is zero, a comma or a dot is the decimal mark, anything else is refused. */
    fun figure(text: String): Figure {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Figure.Value(0.0)
        val parsed = trimmed.replace(',', '.').toDoubleOrNull() ?: return Figure.Invalid
        return if (parsed.isFinite() && parsed >= 0.0) Figure.Value(parsed) else Figure.Invalid
    }

    /**
     * The settings [draft] makes of [current]. A figure that is not valid keeps the stored one and is
     * named in `invalid`; every other field of the draft still applies. A paired phone owns neither
     * its area nor its fiscal values, so only the two phone-side fields are read from the draft then.
     * A blank area (an empty catalogue) never replaces a stored one.
     */
    fun apply(current: WidgetSettings, draft: LocalSettingsDraft, paired: Boolean): LocalApplication {
        val base = current.copy(
            intervalMinutes = draft.intervalMinutes,
            showChargingPlan = draft.showChargingPlan
        )
        if (paired) return LocalApplication(base, emptySet())
        val invalid = mutableSetOf<FigureField>()
        fun read(text: String, field: FigureField, stored: Double): Double =
            when (val figure = figure(text)) {
                is Figure.Value -> figure.amount
                Figure.Invalid -> { invalid += field; stored }
            }
        val tax = read(draft.taxText, FigureField.TAX, current.taxMinorUnit)
        val fee = read(draft.transferText, FigureField.GRID_FEE, current.gridFeeMinorUnit)
        return LocalApplication(
            base.copy(
                area = draft.area.ifBlank { current.area },
                vat = draft.vat,
                tax = draft.tax,
                transfer = draft.transfer,
                taxMinorUnit = tax,
                gridFeeMinorUnit = fee
            ),
            invalid
        )
    }

    /**
     * The paired form's values as the dialog's Save sends them: a blank or unreadable figure is
     * `null`, which the contract's own validation then decides about.
     */
    fun formValues(
        areaId: String,
        vat: Boolean,
        tax: Boolean,
        taxText: String,
        transfer: Boolean,
        transferText: String
    ): SettingsFormValues = SettingsFormValues(
        areaId = areaId,
        vat = vat,
        tax = tax,
        taxFigure = taxText.takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull(),
        transfer = transfer,
        transferFigure = transferText.takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull()
    )
}

/** One fiscal line of the paired price overview. */
internal sealed interface FiscalLine {
    data object Off : FiscalLine
    data object Unset : FiscalLine
    data class Figure(val amount: Double, val unit: String) : FiscalLine
}

/** What the paired "Electricity price" card says: the confirmed area, VAT, energy tax and grid fee. */
internal data class PriceOverview(
    /** The area's own label, or `null` when the record states none. */
    val area: String?,
    val vat: FiscalLine,
    val tax: FiscalLine,
    val transfer: FiscalLine
) {
    companion object {
        fun of(record: HaPlanningSettings?, market: (String) -> PriceMarket?): PriceOverview {
            val areaId = record?.areaId?.takeIf { it.isNotEmpty() }
            val areaMarket = areaId?.let(market)
            fun line(component: HaAreaOverrideComponent, unit: String, fallback: Double? = null): FiscalLine {
                val value: HaFiscalValue = record?.let { HaSettingsEditor.fiscalFor(it, component, areaId) } ?: return FiscalLine.Unset
                if (!value.enabled) return FiscalLine.Off
                val amount = value.value ?: fallback ?: return FiscalLine.Unset
                return FiscalLine.Figure(amount, unit)
            }
            return PriceOverview(
                area = areaId?.let { areaMarket?.selectorLabel ?: it },
                vat = line(HaAreaOverrideComponent.VAT, "%", areaMarket?.vatPercent),
                tax = line(HaAreaOverrideComponent.TAX, areaMarket?.minorUnit?.let { "$it/kWh" }.orEmpty()),
                transfer = line(HaAreaOverrideComponent.TRANSFER, areaMarket?.minorUnit?.let { "$it/kWh" }.orEmpty())
            )
        }

        /** A figure as the overview writes it: at most two decimals, in the screen's number locale. */
        fun figureText(line: FiscalLine.Figure, locale: Locale): String {
            val number = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 0
                maximumFractionDigits = 2
            }.format(line.amount)
            return when {
                line.unit.isEmpty() -> number
                line.unit == "%" -> "$number %"
                else -> "$number ${line.unit}"
            }
        }
    }
}
