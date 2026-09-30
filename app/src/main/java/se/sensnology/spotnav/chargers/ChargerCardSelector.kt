package se.sensnology.spotnav.chargers

import se.sensnology.spotnav.vehicles.VehicleFacts
import se.sensnology.spotnav.widget.WidgetChargerSelectionController

/**
 * What the charger card's header shows, and what picking one of its rows means — kept free of any
 * Android dependency so the decision can be unit tested directly, like [VehicleFacts] and
 * [ChargerPhases].
 */
internal object ChargerCardSelector {
    /** What one row of the selector is. */
    enum class Kind {
        /** The stale binding. Shown once, not selectable, and not a choice. */
        PLACEHOLDER,

        /** The explicit "No charger" row, always present when there is a control. */
        NO_CHARGER,

        /** One of the chargers that actually exist — see [Entry.profile]. */
        PROFILE
    }

    /**
     * One row, in the order the spinner shows it: the placeholder (only for a stale binding), then
     * "No charger", then the profiles themselves.
     */
    data class Entry(val kind: Kind, val profile: ChargerProfile? = null) {
        /** What tapping this row means. */
        val target: Target
            get() = when {
                kind == Kind.PLACEHOLDER -> Target.Ignore
                profile == null -> Target.NoCharger
                else -> Target.Profile(profile.localId)
            }
    }

    /** What a tap on one row does. */
    sealed interface Target {
        /**
         * The stale placeholder. Nothing happens: the user cannot re-select the charger that is
         * gone, and merely rebuilding the row must never write the binding.
         */
        data object Ignore : Target

        /** "No charger": an explicit decision to bind this widget to nothing. */
        data object NoCharger : Target

        /** A charger that exists, by the localId this widget stores. */
        data class Profile(val localId: String) : Target
    }

    /**
     * The card's header content for one moment: which charger it is about (for the title), whether
     * the title gives way to a selector, that selector's rows and which of them is selected, and
     * whether the binding is still stale — which is what draws the warning line under the header.
     */
    data class Content(
        val bound: ChargerProfile?,
        val entries: List<Entry>,
        val selectedIndex: Int,
        val showsControl: Boolean,
        val stale: Boolean
    ) {
        /** What a tap on the row at [position] means. */
        fun targetAt(position: Int): Target = entries.getOrNull(position)?.target ?: Target.Ignore
    }

    /**
     * The header content for [controller], which knows which of the three states this widget's
     * binding is in and what profiles exist.
     */
    fun content(controller: WidgetChargerSelectionController): Content {
        val profiles = controller.availableProfiles
        val selection = controller.selection
        // Only a profile that is actually bound names the card: a stale id is never resolved to
        // another charger just to have something to show.
        val bound = (selection as? WidgetChargerSelectionController.Selection.ValidProfile)
            ?.let { valid -> profiles.firstOrNull { it.localId == valid.localId } }
        // The placeholder row exists because the screen was opened with a stale binding, and the
        // warning because one is still unresolved.
        val placeholder = controller.startedAsMissingProfile
        val entries = buildList {
            if (placeholder) add(Entry(Kind.PLACEHOLDER))
            add(Entry(Kind.NO_CHARGER))
            addAll(profiles.map { Entry(Kind.PROFILE, it) })
        }
        val placeholderOffset = if (placeholder) 1 else 0
        return Content(
            bound = bound,
            entries = entries,
            selectedIndex = when (selection) {
                is WidgetChargerSelectionController.Selection.MissingProfile -> 0
                WidgetChargerSelectionController.Selection.ExplicitlyNoCharger -> placeholderOffset
                is WidgetChargerSelectionController.Selection.ValidProfile ->
                    placeholderOffset + 1 + profiles.indexOfFirst { it.localId == selection.localId }
            },
            // Something to choose between, or something stale to resolve. With exactly one charger,
            // already bound, neither is true — and the card's title says which charger it is
            // instead.
            showsControl = controller.isRelevant &&
                (profiles.size > 1 || selection !is WidgetChargerSelectionController.Selection.ValidProfile),
            stale = controller.requiresExplicitChoice
        )
    }
}
