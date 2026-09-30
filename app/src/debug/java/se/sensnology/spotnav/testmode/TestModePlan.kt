package se.sensnology.spotnav.testmode

import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.widget.WidgetChargerResolver

/** Entering and leaving test mode, and the re-entry guard. */
object TestModePlan {
    /** What pressing "enter" should do. */
    sealed interface Enter {
        /**
         * Snapshot what is there, then activate these profiles **and bind the widget that asked to
         * [boundProfileId]**.
         */
        data class Activate(val profiles: List<ChargerProfile>, val boundProfileId: String?) : Enter

        /**
         * Already in test mode. Nothing is snapshotted and nothing is replaced: the real profiles
         * are in that snapshot and there is no second copy.
         */
        object AlreadyActive : Enter
    }

    /**
     * What "enter" does. [snapshotPresent] is the whole guard: with a snapshot already stored this
     * returns [Enter.AlreadyActive] and no profiles at all, so there is nothing for a caller to
     * snapshot in its place.
     */
    fun enter(
        snapshotPresent: Boolean,
        servers: List<MockCatalogue.Server>,
        newLocalId: () -> String
    ): Enter = if (snapshotPresent) {
        Enter.AlreadyActive
    } else {
        val activated = profiles(servers, newLocalId)
        Enter.Activate(activated, boundProfileId = activeProfileId(activated))
    }

    /**
     * The profiles test mode activates: **one per scenario, from every server that answered**, in
     * the servers' own order so the screen is stable.
     */
    fun profiles(servers: List<MockCatalogue.Server>, newLocalId: () -> String): List<ChargerProfile> =
        servers.flatMap { server ->
            server.scenarios.map { scenario ->
                ChargerProfile(
                    localId = newLocalId(),
                    displayName = scenario.name,
                    baseUrl = server.baseUrl,
                    webhookId = scenario.webhookId
                )
            }
        }

    /**
     * Which profile the app should treat as active, and which one the widget being configured is
     * bound to while in test mode: the first one, or none when no server offered anything — where a
     * charger is exactly what must not be shown, and the state a new user sees first.
     */
    fun activeProfileId(profiles: List<ChargerProfile>): String? = profiles.firstOrNull()?.localId

    /** Entering is possible exactly when there is nothing to restore. */
    fun canEnter(snapshotPresent: Boolean): Boolean = !snapshotPresent

    /** Leaving is possible exactly when there is something to restore. */
    fun canLeave(snapshotPresent: Boolean): Boolean = snapshotPresent
}
