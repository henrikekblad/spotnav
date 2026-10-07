package se.sensnology.spotnav.ha.session

import android.content.Context
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.client.FetchedDashboard
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.widget.WidgetChartBoundary
import se.sensnology.spotnav.widget.WidgetDashboardStore

/**
 * Keeps each dashboard a screen reads on the profile it was read for, for the home-screen widget,
 * and for this phone's notifications (whether that Home Assistant identifies cars).
 */
internal class ProfileDashboardRecorder(context: Context, private val profileId: String) : DashboardRecorder {
    private val appContext = context.applicationContext

    override fun record(result: Result<FetchedDashboard>): Int? {
        val profiles = ChargerProfileStore.forContext(appContext)
        profiles.applyDashboardResult(profileId, result.map { it.dashboard })
        result.getOrNull()?.let { fresh ->
            WidgetDashboardStore.forContext(appContext).put(profileId, fresh.body, System.currentTimeMillis())
            WidgetChartBoundary.requestRedraw(appContext)
            // Whether this Home Assistant identifies cars, so the question is offered and registered.
            LocalNotifications.observeIdentification(appContext, profileId, fresh.dashboard)
        }
        return profiles.getProfile(profileId)?.detectedPhases
    }
}
