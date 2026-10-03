package se.sensnology.spotnav.ha.settings

import se.sensnology.spotnav.ha.authority.HaPlanningAdapter
import java.time.LocalDate

/** A fiscal component by name, so an edit can say *which* one it is about. */
internal enum class HaAreaOverrideComponent(val wire: String) {
    VAT("vat"),
    TAX("tax"),
    TRANSFER("transfer")
}
/** **One** deliberate user edit of the charger's canonical settings. */
internal sealed interface HaSettingsEdit {
    /** The price area whose market the plan is for. */
    data class Area(val areaId: String) : HaSettingsEdit

    /** The charger's current limit in whole amperes. */
    data class Amps(val amps: Int) : HaSettingsEdit

    /** The requested amount, as a decimal kWh exactly as asked. */
    data class Energy(val requestedKwh: Double) : HaSettingsEdit

    /** The period cap. */
    data class MaxPeriods(val maxPeriods: Int) : HaSettingsEdit

    /**
     * Departure, as a whole state: whether it applies, the wall time it is, the day (`null` for
     * every day) and the weekdays a daily departure applies on (`null` keeps the record's own).
     */
    data class Departure(
        val enabled: Boolean,
        val time: String,
        val date: LocalDate?,
        val weekdays: List<Int>? = null
    ) : HaSettingsEdit

    /** What drives the plan, and the target intent that goes with it. */
    data class Driver(val driver: HaSettingsDriver, val target: HaTargetIntent) : HaSettingsEdit

    /** One component of the *current* area's override: enabled, and its figure or none. */
    data class Fiscal(
        val areaId: String,
        val component: HaAreaOverrideComponent,
        val value: HaFiscalValue
    ) : HaSettingsEdit

    /** What the system optimizes -- the strategy chooser's own edit (see `ChargingStrategyUi`). */
    data class Strategy(val strategy: HaSettingsStrategy) : HaSettingsEdit
}

/** What one edit produced: a full replacement, or the contract's own stable refusal. */
internal sealed interface HaSettingsEditResult {
    /**
     * The complete replacement body for the confirmed record's revision, validated by the accepted
     * codec rather than by this file's own opinion.
     */
    data class Ready(val settings: HaPlanningSettings) : HaSettingsEditResult

    /** The edit would produce a value the contract refuses; [code] is the stable code. */
    data class Refused(val code: String) : HaSettingsEditResult
}

/** One confirmed record plus **one** deliberate edit into a full replacement. */
internal object HaSettingsEditor {
    fun replacement(confirmed: HaPlanningSettings, edit: HaSettingsEdit): HaSettingsEditResult {
        val candidate = apply(confirmed, edit)
        return try {
            val validated = HaSettingsCodec.parseBody(HaSettingsCodec.encodeBody(candidate))
            HaSettingsEditResult.Ready(validated.copy(revision = confirmed.revision))
        } catch (refusal: HaSettingsFormatException) {
            HaSettingsEditResult.Refused(refusal.code)
        }
    }

    private fun apply(confirmed: HaPlanningSettings, edit: HaSettingsEdit): HaPlanningSettings = when (edit) {
        is HaSettingsEdit.Area -> confirmed.copy(areaId = edit.areaId)
        is HaSettingsEdit.Amps -> confirmed.copy(amps = edit.amps)
        is HaSettingsEdit.Energy -> confirmed.copy(requestedKwh = edit.requestedKwh)
        is HaSettingsEdit.MaxPeriods -> confirmed.copy(maxPeriods = edit.maxPeriods)
        is HaSettingsEdit.Departure ->
            confirmed.copy(
                departureEnabled = edit.enabled,
                departureTime = edit.time,
                departureDate = edit.date,
                departureWeekdays = edit.weekdays ?: confirmed.departureWeekdays
            )
        is HaSettingsEdit.Driver -> confirmed.copy(driver = edit.driver, target = edit.target)
        is HaSettingsEdit.Fiscal -> confirmed.copy(overrides = withComponent(confirmed, edit))
        is HaSettingsEdit.Strategy -> confirmed.copy(strategy = edit.strategy)
    }

    /** The overrides with one component of one area replaced. */
    private fun withComponent(settings: HaPlanningSettings, edit: HaSettingsEdit.Fiscal): List<HaAreaOverride> {
        val existing = settings.overrides.firstOrNull { it.areaId == edit.areaId }
        val updated = (existing ?: HaAreaOverride(areaId = edit.areaId)).let { current ->
            when (edit.component) {
                HaAreaOverrideComponent.VAT -> current.copy(vat = edit.value)
                HaAreaOverrideComponent.TAX -> current.copy(tax = edit.value)
                HaAreaOverrideComponent.TRANSFER -> current.copy(transfer = edit.value)
            }
        }
        return settings.overrides.filterNot { it.areaId == edit.areaId } + updated
    }

    /** The edit the screen may send *next* after [answer] to [edit]. */
    fun nextEdit(edit: HaSettingsEdit, answer: SettingsUpdate.Outcome): HaSettingsEdit? = null

    /**
     * The current area's override state for one component, with no override for that area read as
     * all-off.
     */
    fun fiscalFor(
        settings: HaPlanningSettings,
        component: HaAreaOverrideComponent,
        areaId: String?
    ): HaFiscalValue {
        val override = areaId?.let { id -> settings.overrides.firstOrNull { it.areaId == id } }
            ?: return HaFiscalValue.OFF
        return when (component) {
            HaAreaOverrideComponent.VAT -> override.vat
            HaAreaOverrideComponent.TAX -> override.tax
            HaAreaOverrideComponent.TRANSFER -> override.transfer
        }
    }
}
