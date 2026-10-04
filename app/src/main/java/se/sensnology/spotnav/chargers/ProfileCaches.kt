package se.sensnology.spotnav.chargers

import android.content.Context
import se.sensnology.spotnav.ha.settings.ConfirmedSettingsStore
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.widget.WidgetDashboardStore
import se.sensnology.spotnav.widget.WidgetPlanSnapshotStore

/**
 * Everything the app remembers *about* one charger profile from Home Assistant: the confirmed
 * settings record, the last dashboard a home-screen widget draws from and the confirmed plan of a
 * home-screen widget.
 */
internal object ProfileCaches {
    fun forget(context: Context, localId: String) {
        ConfirmedSettingsStore.forContext(context).removeProfile(localId)
        WidgetDashboardStore.forContext(context).clear(localId)
        WidgetPlanSnapshotStore.forContext(context).clear(localId)
        LocalNotifications.onProfileForgotten(context, localId)
    }
}
