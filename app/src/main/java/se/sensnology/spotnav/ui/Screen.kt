package se.sensnology.spotnav.ui

/** The three screens this Activity can show, and how the user moves between them. */
internal enum class Screen(val storedKey: String) {
    /** The root: the charger, the car, the plan and the plan's graph, on one page. */
    MAIN("charging"),

    /** The day's prices, and nothing that can be edited. */
    PRICE_TABLE("price_table"),

    /** The settings form, with the save button that ends the widget-host flow. */
    SETTINGS("settings");

    /**
     * Where Back from this screen lands, or `null` when Back leaves the Activity -- which is the
     * platform's own behaviour for the root, and is also how an unconfigured widget's Settings
     * keeps Android's cancel path (finishing with this screen's already-set `RESULT_CANCELED`)
     * instead of pretending the configuration succeeded.
     */
    fun back(existingWidget: Boolean): Screen? = when (this) {
        MAIN -> null
        PRICE_TABLE -> MAIN
        SETTINGS -> if (existingWidget) MAIN else null
    }

    companion object {
        /**
         * The two panels the main screen opens, and the whole of what it opens: Nothing else
         * navigates anywhere, so an entry added here without an action is a mistake the navigation
         * tests can see.
         */
        val panels: List<Screen> = listOf(SETTINGS, PRICE_TABLE)

        /** Where a launch with nothing to restore begins. */
        fun freshLaunch(existingWidget: Boolean): Screen = if (existingWidget) MAIN else SETTINGS

        /** The screen a stored key names, or `null` when it names none we know. */
        fun fromStoredKey(key: String?): Screen? = entries.firstOrNull { it.storedKey == key }

        /**
         * The screen to show: what was restored when that is a screen we know, else [freshLaunch]'s
         * default. Never `null`, so the caller has no third case to invent.
         */
        fun restored(key: String?, existingWidget: Boolean): Screen =
            fromStoredKey(key) ?: freshLaunch(existingWidget)
    }
}
