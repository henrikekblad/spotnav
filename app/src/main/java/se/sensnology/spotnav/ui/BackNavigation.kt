package se.sensnology.spotnav.ui

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Back for the screen model, through the platform's own mechanism on each side of API 33. */
internal class BackNavigation(private val activity: Activity, private val goBack: () -> Unit) {
    // Held as an Any so this class loads below API 33, where the callback type does not exist.
    private var callback: Any? = null

    /** Whether Back has an internal destination now: keep the callback registered exactly then. */
    fun setInterceptsBack(intercepts: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = activity.onBackInvokedDispatcher
        val held = callback as? OnBackInvokedCallback
        if (intercepts && held == null) {
            val created = OnBackInvokedCallback { goBack() }
            callback = created
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, created)
        } else if (!intercepts && held != null) {
            callback = null
            dispatcher.unregisterOnBackInvokedCallback(held)
        }
    }

    /** The Activity is going away: nothing may be left registered against it. */
    fun release() = setInterceptsBack(false)
}
