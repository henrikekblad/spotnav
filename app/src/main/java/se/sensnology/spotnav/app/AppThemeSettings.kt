package se.sensnology.spotnav.app

import android.content.Context
import android.content.res.Configuration
import se.sensnology.spotnav.R

object AppThemeSettings {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    private const val PREFS = "app_appearance"
    private const val KEY = "theme"

    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun save(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }

    fun isDark(context: Context): Boolean = when (mode(context)) {
        DARK -> true
        LIGHT -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun apply(activity: android.app.Activity) {
        activity.setTheme(if (isDark(activity)) R.style.AppTheme_Dark else R.style.AppTheme_Light)
    }
}
