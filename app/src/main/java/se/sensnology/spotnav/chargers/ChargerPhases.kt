package se.sensnology.spotnav.chargers

import se.sensnology.spotnav.vehicles.VehicleEnergy

object ChargerPhases {
    /** The app's own phase model: a charge uses one phase or all three. */
    val SUPPORTED_PHASES: Set<Int> = setOf(1, 3)

    /** Where the phase value the card shows came from. */
    enum class Status {
        /**
         * Nothing reported: the stored value is simply the user's own choice, with no marker on it
         * either way.
         */
        STORED,

        /**
         * The charger reports exactly the phases the plan uses: there is no disagreement to
         * surface, so nothing is marked and nothing is said.
         */
        DETECTED,

        /** The charger reports something else. */
        CONFLICT
    }

    /** What the card should show for phases. */
    data class Display(
        val phases: Int,
        val status: Status,
        val detectedPhases: Int?,
        val adoptablePhases: Int?
    )

    /**
     * The one place a phase readout is decided: from the stored value the plan is built from, and
     * whatever the charger reported.
     */
    fun display(storedPhases: Int, detectedPhases: Int?): Display {
        val phases = normalized(storedPhases)
        if (detectedPhases == null) {
            return Display(phases, Status.STORED, null, null)
        }
        if (detectedPhases == phases) {
            return Display(phases, Status.DETECTED, detectedPhases, null)
        }
        return Display(
            phases,
            Status.CONFLICT,
            detectedPhases,
            detectedPhases.takeIf { it in SUPPORTED_PHASES }
        )
    }

    /**
     * [phases] in the app's own model — one phase, or three — exactly as `WidgetSettings.load`
     * reads a stored value back and as the card's radio group yields it.
     */
    fun normalized(phases: Int): Int = if (phases == 1) 1 else 3
}
