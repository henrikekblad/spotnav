package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.prices.AreaSelection
import se.sensnology.spotnav.prices.PriceMarket
import java.util.Locale

/** The settings form's area picker, as Android-free state and transitions. */

/** What the picker currently represents. Three states, because a saved id can be absent, valid, or gone. */
internal sealed interface AreaSelectionState {
    /** A valid catalogue area is selected. */
    data class Available(val id: String) : AreaSelectionState

    /** It is preserved verbatim: nothing here may re-point the widget at another area's prices. */
    data class Missing(val savedId: String) : AreaSelectionState

    /** Nothing is selected and nothing was saved -- an empty catalogue, say. */
    data object None : AreaSelectionState
}

/** One picker row: a heading or the unavailable placeholder (no [id]), or a selectable area. */
internal data class AreaPickerRow(val label: String, val id: String?)

/** What the View must do for one picker event. */
internal data class AreaPickerView(
    val rows: List<AreaPickerRow>,
    val selectedIndex: Int,
    val state: AreaSelectionState,
    val canSave: Boolean,
    /** Put these rows and this selection into the Spinner. */
    val replaceAdapter: Boolean,
    /** A person chose this row: the only case that may apply fiscal suggestions. */
    val userPicked: Boolean
)

/** The picker's state machine, and the one place the current selection lives. */
internal class AreaPickerState(
    val savedId: String,
    areas: List<PriceMarket>,
    private val region: String,
    private val countryLabel: (String) -> String,
    private val unavailableLabel: (String) -> String,
    /**
     * The row shown while the form stands for no area at all: Home Assistant's record states none,
     * and this phone's own default must not stand in for it. Like the unavailable row it carries no
     * id.
     */
    private val notSetLabel: String? = null
) {
    private var areas: List<PriceMarket> = areas
    private var installedRows: List<AreaPickerRow> = emptyList()
    private var installedIndex: Int = -1

    /** The identity the form currently stands for: the saved id, or an unsaved choice since. */
    var selectedId: String? = savedId
        private set

    val state: AreaSelectionState get() = SettingsAreaController.state(selectedId, areas)

    val canSave: Boolean get() = SettingsAreaController.canSave(state)

    /** The initial build. Always installs the adapter, never a user choice. */
    fun open(): AreaPickerView {
        val built = build(selectedId)
        installedRows = built.rows
        installedIndex = built.index
        return view(built, replaceAdapter = true, userPicked = false)
    }

    /** A row was selected in the Spinner. */
    fun onRowSelected(position: Int, rowId: String?): AreaPickerView? {
        if (rowId == null) return null
        if (position == installedIndex && rowId == installedRows.getOrNull(position)?.id) return null
        if (rowId == selectedId) return null
        selectedId = rowId
        installedIndex = position
        return view(
            Built(installedRows, position),
            // The Spinner has already moved itself: only the labels are ours to refresh, and this
            // is the one path that may apply suggestions.
            replaceAdapter = false,
            userPicked = true
        )
    }

    /**
     * A refreshed catalogue. The identity stays; its state is recomputed, so a selection the relay
     * has retired becomes [AreaSelectionState.Missing] -- of *its own* id, never of the saved one.
     */
    fun onCatalogueRefreshed(areas: List<PriceMarket>): AreaPickerView {
        this.areas = areas
        val built = build(selectedId)
        val replace = built.rows != installedRows || built.index != installedIndex
        installedRows = built.rows
        installedIndex = built.index
        return view(built, replaceAdapter = replace, userPicked = false)
    }

    private fun view(built: Built, replaceAdapter: Boolean, userPicked: Boolean) = AreaPickerView(
        rows = built.rows,
        selectedIndex = built.index,
        state = state,
        canSave = canSave,
        replaceAdapter = replaceAdapter,
        userPicked = userPicked
    )

    private fun build(id: String?): Built {
        val selection = SettingsAreaController.state(id, areas)
        val rows = SettingsAreaController.rows(areas, region, selection, countryLabel, unavailableLabel, notSetLabel)
        val index = rows.indexOfFirst { it.id != null && it.id == id }
            .takeIf { it >= 0 }
            ?: rows.indexOfFirst { it.id == null && selection is AreaSelectionState.Missing }
        return Built(rows, if (index < 0) 0 else index)
    }

    private data class Built(val rows: List<AreaPickerRow>, val index: Int)
}

internal object SettingsAreaController {
    /** Which state a saved id is in, against the current catalogue. */
    fun state(savedId: String?, areas: List<PriceMarket>): AreaSelectionState = when {
        savedId.isNullOrEmpty() -> AreaSelectionState.None
        areas.any { it.id == savedId } -> AreaSelectionState.Available(savedId)
        else -> AreaSelectionState.Missing(savedId)
    }

    /** The rows for a picker, headings included. */
    fun rows(
        areas: List<PriceMarket>,
        region: String,
        state: AreaSelectionState,
        countryLabel: (String) -> String,
        unavailableLabel: (String) -> String,
        notSetLabel: String? = null
    ): List<AreaPickerRow> = buildList {
        if (state is AreaSelectionState.None && notSetLabel != null) {
            add(AreaPickerRow(notSetLabel, null))
        }
        if (state is AreaSelectionState.Missing) {
            add(AreaPickerRow(unavailableLabel(state.savedId), null))
        }
        AreaSelection.grouped(areas, region).forEach { (country, inCountry) ->
            add(AreaPickerRow(countryLabel(country), null))
            inCountry.forEach { add(AreaPickerRow(it.selectorLabel, it.id)) }
        }
    }

    /** Whether the form may be saved. */
    fun canSave(state: AreaSelectionState): Boolean = state is AreaSelectionState.Available
}

/** Which fiscal suggestions the relay actually publishes for an area. */
internal enum class FiscalSuggestion { BOTH, TAX_ONLY, GRID_ONLY, NEITHER }

/** The suggestions an area carries, as one of four states. */
internal object FiscalSuggestions {
    fun of(area: PriceMarket?): FiscalSuggestion = when {
        area == null -> FiscalSuggestion.NEITHER
        area.suggestedTax != null && area.suggestedGridFee != null -> FiscalSuggestion.BOTH
        area.suggestedTax != null -> FiscalSuggestion.TAX_ONLY
        area.suggestedGridFee != null -> FiscalSuggestion.GRID_ONLY
        else -> FiscalSuggestion.NEITHER
    }
}

/** The money facts one area carries, for the two surfaces that show money. */
internal sealed interface AreaMoneyFacts {
    /** All three facts are known. */
    data class Available(val areaId: String, val currency: String, val majorUnit: String) : AreaMoneyFacts

    /** The area is not in the catalogue, so it has neither a unit nor a currency. */
    data class Unavailable(val areaId: String) : AreaMoneyFacts
}

/**
 * The one place those facts are put together, so the cost label and the footer cannot drift apart
 * or invent a unit.
 */
internal object AreaMoney {
    fun of(savedId: String, area: PriceMarket?): AreaMoneyFacts =
        if (area == null) AreaMoneyFacts.Unavailable(savedId)
        else AreaMoneyFacts.Available(area.id, area.currency, area.majorUnit)
}

internal object CostLabel {
    /**
     * `"3.95 kr"`, or `null` when the area is unavailable (the caller then shows its own
     * unavailable state rather than a number with no unit).
     */
    fun amount(costMajor: Double, facts: AreaMoneyFacts, locale: Locale): String? =
        when (facts) {
            is AreaMoneyFacts.Unavailable -> null
            is AreaMoneyFacts.Available -> String.format(locale, "%.2f %s", costMajor, facts.majorUnit)
        }
}

/** Which display intervals the settings screen offers. */
internal object PresentationIntervals {
    const val QUARTER_HOUR_MINUTES = 15
    const val HOUR_MINUTES = 60

    /** Every interval a widget may be set to, in the order the form offers them. */
    val all: List<Int> = listOf(QUARTER_HOUR_MINUTES, HOUR_MINUTES)

    /** Whether [minutes] may be shown for a day whose document carried [sourceResMinutes]. */
    @Suppress("UNUSED_PARAMETER") // deliberate: see above, reading it is the bug
    fun isAvailable(minutes: Int, sourceResMinutes: Int? = null): Boolean = minutes in all
}

/**
 * Whether the price controls may be edited: an unpaired phone owns its own settings and always may;
 * a paired one only when its authority allows a write (none before the first answer).
 */
internal fun priceControlsEnabled(paired: Boolean, authority: VisibleAuthority?): Boolean =
    if (!paired) true else authority?.pairedControlsEnabled ?: false
