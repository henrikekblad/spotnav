package se.sensnology.spotnav.ha.session

import android.content.Context
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.client.FetchedDashboard
import se.sensnology.spotnav.widget.WidgetChartBoundary
import se.sensnology.spotnav.widget.WidgetDashboardStore

/**
 * Keeps each dashboard a screen reads on the profile it was read for, and for the home-screen
 * widget.
 */
internal class ProfileDashboardRecorder(context: Context, private val profileId: String) : DashboardRecorder {
    private val appContext = context.applicationContext

    override fun record(result: Result<FetchedDashboard>): Int? {
        val profiles = ChargerProfileStore.forContext(appContext)
        profiles.applyDashboardResult(profileId, result.map { it.dashboard })
        result.getOrNull()?.let { fresh ->
            WidgetDashboardStore.forContext(appContext).put(profileId, fresh.body, System.currentTimeMillis())
            WidgetChartBoundary.requestRedraw(appContext)
        }
        return profiles.getProfile(profileId)?.detectedPhases
    }
}
