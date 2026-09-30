package se.sensnology.spotnav.ha.settings

import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.prices.PriceMarket

/** The Settings form's values, as the person left them. */
internal data class SettingsFormValues(
    val areaId: String,
    val vat: Boolean,
    val tax: Boolean,
    /** The energy-tax figure in the area's minor unit, or `null` when the field is empty. */
    val taxFigure: Double?,
    val transfer: Boolean,
    /** The transfer-fee figure in the area's minor unit, or `null` when the field is empty. */
    val transferFigure: Double?
)

/** What one press of Save decided. */
internal sealed interface SettingsSave {
    /**
     * No paired owner at admission: the widget's own record is saved, exactly as it was before
     * pairing existed. Unpaired, and a contract this app cannot read.
     */
    data object LocalOnly : SettingsSave

    /**
     * The screen's independent presentation settings are nobody else's and keep their own behaviour
     * (at most the one local write of those two fields).
     */
    data object ReadOnly : SettingsSave

    /** The form's own values would produce a document the contract refuses; [code] is its code. */
    data class Refused(val code: String) : SettingsSave

    /**
     * One admitted Save: the paired owner, the screen it belongs to, the operation its answer is
     * recognised by, the revision the form displayed, and the complete replacement.
     */
    data class Send(
        val profileId: String,
        val screenGeneration: Int,
        val operation: Long,
        val expectedRevision: Int,
        val replacement: HaPlanningSettings
    ) : SettingsSave
}

/** The Settings form's paired fields, as one document. */
internal object PairedSettingsForm {
    /**
     * The complete replacement document [record] becomes when the form's own values are applied to
     * it, validated by the accepted codec.
     */
    fun replacement(
        record: HaPlanningSettings,
        values: SettingsFormValues,
        catalogue: List<PriceMarket>
    ): HaSettingsEditResult {
        val areaId = values.areaId.ifEmpty { record.areaId.orEmpty() }
        if (areaId.isEmpty()) {
            // Nothing to be the subject of a fiscal override: the same refusal the contract itself
            // states for an empty `area_id`.
            return HaSettingsEditResult.Refused("invalid_area")
        }
        val market = catalogue.firstOrNull { it.id == areaId }
        val existing = record.overrides.firstOrNull { it.areaId == areaId }
        val wanted = HaAreaOverride(
            areaId = areaId,
            // The VAT switch has no figure field of its own, so it changes only what it can say.
            vat = when {
                (existing?.vat ?: HaFiscalValue.OFF).enabled == values.vat -> existing?.vat ?: HaFiscalValue.OFF
                values.vat -> HaFiscalValue(enabled = true, value = market?.vatPercent)
                else -> HaFiscalValue.OFF
            },
            tax = HaFiscalValue(enabled = values.tax, value = if (values.tax) values.taxFigure else null),
            transfer = HaFiscalValue(enabled = values.transfer, value = if (values.transfer) values.transferFigure else null)
        )
        val overrides = when {
            existing == null && wanted == HaAreaOverride(areaId = areaId) -> record.overrides
            else -> record.overrides.filterNot { it.areaId == areaId } + wanted
        }
        val candidate = record.copy(areaId = areaId, overrides = overrides)
        return try {
            val validated = HaSettingsCodec.parseBody(HaSettingsCodec.encodeBody(candidate))
            HaSettingsEditResult.Ready(validated.copy(revision = record.revision))
        } catch (refusal: HaSettingsFormatException) {
            HaSettingsEditResult.Refused(refusal.code)
        }
    }

    /** What a fiscal field shows for a confirmed value. */
    fun figureText(value: HaFiscalValue): String = value.value?.let { number -> number.toString() } ?: ""
}
