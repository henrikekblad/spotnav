package se.sensnology.spotnav.chart

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore

/**
 * This phone's choice of chart time axis (see [ChartAxisMode]), kept beside the app's other appearance
 * choices. The in-app chart and every widget draw it; it is never sent to Home Assistant. Nothing
 * stored, or anything unknown, reads as [ChartAxisMode.DEFAULT].
 */
internal class ChartAxisPreference(private val store: KeyValueStore) {
    fun mode(): ChartAxisMode = ChartAxisMode.of(store.getString(KEY))

    fun save(mode: ChartAxisMode) {
        store.putString(KEY, mode.key)
    }

    companion object {
        /** The preference file the app's theme is kept in. */
        private const val PREFS = "app_appearance"
        const val KEY = "chart_axis"

        fun of(context: Context): ChartAxisPreference = ChartAxisPreference(SharedPreferencesKeyValueStore(context, PREFS))
    }
}
