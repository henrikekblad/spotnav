package se.sensnology.spotnav.ui.settings

/**
 * A row of Settings that another screen can open it at (the card's `openSetting(section, edit)`): the rows of the "Get started"
 * list that are changed in this app. A row carries its [tag] as its view tag; the screen scrolls to it and marks it for a moment.
 */
internal enum class SettingsTarget(val tag: String) {
    /** Phase switching for solar (the charger card's row). */
    PHASE_SWITCHING("setting:phase_switching"),

    /** Which phones hear about the charge (Home Assistant's Companion app row of the notifications card). */
    PHONES("setting:notification_targets"),

    /** The site's solar forecast source. */
    SOLAR_FORECAST("setting:solar_forecast");

    companion object {
        /**
         * The row a "Get started" row opens, or `null` where it is done elsewhere: the forecast row only when a source can be
         * chosen already (else Home Assistant's add-integration page opens first).
         */
        fun ofSetupRow(id: String, forecastChoosable: Boolean): SettingsTarget? = when (id) {
            "phase_switching" -> PHASE_SWITCHING
            "phone" -> PHONES
            "solar_forecast" -> if (forecastChoosable) SOLAR_FORECAST else null
            else -> null
        }

        /** The scroll position that brings a row at [top] (in the page) into view with [margin] above it. */
        fun scrollY(top: Int, margin: Int): Int = maxOf(0, top - margin)
    }
}
