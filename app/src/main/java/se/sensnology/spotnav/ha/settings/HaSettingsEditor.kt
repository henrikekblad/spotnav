package se.sensnology.spotnav.ha.settings

import se.sensnology.spotnav.ha.authority.HaPlanningAdapter
import se.sensnology.spotnav.prices.IncludedPart
import se.sensnology.spotnav.prices.PriceMarket
import java.time.LocalDate

/** A fiscal component by name, so an edit can say *which* one it is about. */
enum class HaAreaOverrideComponent(val wire: String, val part: IncludedPart) {
    VAT("vat", IncludedPart.VAT),
    TAX("tax", IncludedPart.TAX),
    TRANSFER("transfer", IncludedPart.GRID_FEE);

    companion object {
        fun of(wire: Any?): HaAreaOverrideComponent? = entries.firstOrNull { it.wire == wire }

        /** The components a relay `included` list names (`grid_fee` is the transfer fee). */
        fun of(parts: Set<IncludedPart>): Set<HaAreaOverrideComponent> = entries.filter { it.part in parts }.toSet()

        /**
         * What [areaId]'s price already includes: the record's own `fiscal_included` when it is the
         * record's area, and what the catalogue says of [market].
         */
        fun includedFor(record: HaPlanningSettings?, areaId: String?, market: PriceMarket?): Set<HaAreaOverrideComponent> =
            (if (record != null && areaId != null && areaId == record.areaId) record.fiscalIncluded else emptySet()) +
                of(market?.included.orEmpty())
    }
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

    /**
     * The Companion app's phones and the events they hear about, as a whole; the record's own tap
     * path is kept. Only for a record that states `notifications`.
     */
    data class Notifications(val targets: List<String>, val events: List<String>) : HaSettingsEdit
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
            HaSettingsEditResult.Ready(validated.withReadOnlyOf(confirmed))
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
        // A component the price already includes is locked: an edit of it changes nothing.
        is HaSettingsEdit.Fiscal ->
            if (edit.component in HaAreaOverrideComponent.includedFor(confirmed, edit.areaId, null)) confirmed
            else confirmed.copy(overrides = withComponent(confirmed, edit))
        is HaSettingsEdit.Strategy -> confirmed.copy(strategy = edit.strategy)
        is HaSettingsEdit.Notifications -> confirmed.copy(
            notifications = (confirmed.notifications ?: HaNotificationSettings()).copy(
                targets = edit.targets.distinct(),
                events = HaSettingsCodec.canonicalEvents(edit.events.distinct())
            )
        )
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

    /**
     * Whether [answer] is a revision conflict in which nothing this app edits moved since [base]: only
     * the revision (and read-only facts). Home Assistant 1.11 stores a person's Start or Stop as a pause
     * in the record, so a write built just before one meets a newer revision with the same settings;
     * the same edit is then sent once more against it, quietly, rather than asked for again.
     */
    fun onlyRevisionMoved(base: HaPlanningSettings, answer: SettingsUpdate.Outcome): Boolean =
        answer is SettingsUpdate.Outcome.Conflict &&
            answer.current.revision > base.revision &&
            answer.current.withReadOnlyOf(base) == base

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
