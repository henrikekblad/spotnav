package se.sensnology.spotnav.widget

import se.sensnology.spotnav.chargers.ChargerCardSelector
import se.sensnology.spotnav.chargers.ChargerProfile

/**
 * Pure state for the charger card's selector. The selection is modelled explicitly (see [Selection]) so a
 * stale binding (the stored profile no longer exists) is never conflated with an explicit "No charger":
 * a stale binding survives until the user resolves it, see [chargerProfileIdToSave].
 * [ChargerCardSelector] turns this state into the card's rows and reads taps back.
 *
 * Not a `data class`, so the default `toString()` cannot print any field.
 */
internal class WidgetChargerSelectionController(
    val availableProfiles: List<ChargerProfile>,
    storedChargerProfileId: String?
) {
    sealed interface Selection {
        data class ValidProfile(val localId: String) : Selection
        data object ExplicitlyNoCharger : Selection

        /** The stored profile no longer exists; [localId] is kept only so it can be preserved, never shown. */
        data class MissingProfile(val localId: String) : Selection
    }

    /** Whether there is something to choose or a stale binding to resolve. */
    val isRelevant: Boolean = availableProfiles.isNotEmpty() || storedChargerProfileId != null

    /** Whether construction found a stale binding; fixed at construction. */
    val startedAsMissingProfile: Boolean

    var selection: Selection
        private set

    init {
        selection = when {
            storedChargerProfileId == null -> Selection.ExplicitlyNoCharger
            availableProfiles.any { it.localId == storedChargerProfileId } ->
                Selection.ValidProfile(storedChargerProfileId)
            else -> Selection.MissingProfile(storedChargerProfileId)
        }
        startedAsMissingProfile = selection is Selection.MissingProfile
    }

    /** [localId] must be one of [availableProfiles]. */
    fun selectProfile(localId: String) {
        require(availableProfiles.any { it.localId == localId }) { "Unknown charger profile id" }
        selection = Selection.ValidProfile(localId)
    }

    fun selectNoCharger() {
        selection = Selection.ExplicitlyNoCharger
    }

    /**
     * The charger id to write for the current selection. A stale [Selection.MissingProfile] id is carried
     * through unchanged until the user calls [selectProfile] or [selectNoCharger].
     */
    val chargerProfileIdToSave: String?
        get() = when (val current = selection) {
            is Selection.ValidProfile -> current.localId
            Selection.ExplicitlyNoCharger -> null
            is Selection.MissingProfile -> current.localId
        }

    /** True while the original stale binding is unresolved; drives the card's warning line. */
    val requiresExplicitChoice: Boolean get() = selection is Selection.MissingProfile
}
