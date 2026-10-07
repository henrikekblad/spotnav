package se.sensnology.spotnav.ha.authority

import se.sensnology.spotnav.ha.settings.HaPlanningSettings

/** What one canonical Home Assistant record means for this app's local calculation. */
sealed interface HaPlanningInputs {
    data class Auto(val record: HaPlanningSettings) : HaPlanningInputs

    data class Incomplete(val reasons: List<Reason>) : HaPlanningInputs {
        init {
            require(reasons.isNotEmpty()) { "an incomplete adaptation names at least one reason" }
        }
    }

    /** Why a record could not become calculation inputs. Stable names, not prose. */
    enum class Reason {
        /** The area is absent, or the catalogue does not know it. */
        AREA_UNKNOWN,

        PHASES,

        /** No current limit. */
        AMPS,

        /** An enabled component whose figure is neither stated nor suggested by that area. */
        FISCAL_VAT,
        FISCAL_TAX,
        FISCAL_TRANSFER,

        /** A departure the record states but this app cannot read as a wall clock. */
        DEPARTURE,

        /** A target-driven plan with no target to drive it. */
        TARGET,

        /** A value that satisfied this adapter's checks but not the calculation value's own. */
        REJECTED
    }
}
