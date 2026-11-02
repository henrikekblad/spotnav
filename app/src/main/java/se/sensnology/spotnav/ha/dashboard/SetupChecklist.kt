package se.sensnology.spotnav.ha.dashboard

/**
 * The "Get started" rows this app shows (the card's `setup-checklist.ts`): the dashboard's `setup.items`, but the row that
 * says to get the app, which cannot be left once this app asks. A row is dismissed only where the dashboard states
 * `capabilities.dismiss_setup_item`.
 */
internal object SetupChecklist {
    /** The row that points at this very app. */
    private const val THE_APP = "app"

    fun rows(dashboard: Dashboard?): List<SetupRow> = dashboard?.setup.orEmpty().filter { it.id != THE_APP }

    fun canDismiss(dashboard: Dashboard?, row: SetupRow): Boolean =
        row.dismissible && dashboard?.capabilities?.dismissSetupItem == true
}
