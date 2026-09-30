package se.sensnology.spotnav.testmode

import android.app.Activity
import android.widget.LinearLayout

/**
 * The test-mode entry points as a release build sees them: nothing to add, and nothing that could
 * be started.
 */
@Suppress("UNUSED_PARAMETER")
object TestMode {
    fun addSettingsSection(activity: Activity, parent: LinearLayout) = Unit

    fun addBanner(activity: Activity, parent: LinearLayout) = Unit
}
