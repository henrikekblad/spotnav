package se.sensnology.spotnav.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.AppThemeSettings
import se.sensnology.spotnav.app.EnergyText

/** The screen's colours, decided once per Activity from the theme. */
internal class Palette(
    val dark: Int,
    val muted: Int,
    val accent: Int,
    val appBackground: Int,
    val cardBackground: Int
) {
    companion object {
        fun of(context: Context): Palette {
            val darkTheme = AppThemeSettings.isDark(context)
            val accent = context.getColor(if (darkTheme) R.color.spotnav_accent_dark else R.color.spotnav_accent_light)
            return if (darkTheme) {
                Palette(
                    dark = 0xFFF5F7FA.toInt(), muted = 0xFFA8B1BC.toInt(), accent = accent,
                    appBackground = 0xFF10151B.toInt(), cardBackground = 0xFF222C37.toInt()
                )
            } else {
                Palette(
                    dark = 0xFF192029.toInt(), muted = 0xFF667180.toInt(), accent = accent,
                    appBackground = 0xFFF6F7FB.toInt(), cardBackground = 0xFFE7EEF6.toInt()
                )
            }
        }
    }
}

/**
 * What every view-building piece of the screen needs at hand: the Activity as a [Context], the
 * [Palette], density-independent sizes and translated text.
 */
internal open class ViewScope(val activity: Activity, val palette: Palette) {
    constructor(scope: ViewScope) : this(scope.activity, scope.palette)

    val context: Activity get() = activity
    val applicationContext: Context get() = activity.applicationContext
    val resources get() = activity.resources
    val isDestroyed: Boolean get() = activity.isDestroyed

    val dark: Int get() = palette.dark
    val muted: Int get() = palette.muted
    val accent: Int get() = palette.accent
    val appBackground: Int get() = palette.appBackground
    val cardBackground: Int get() = palette.cardBackground

    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun t(id: Int, vararg args: Any) = AppLanguageSettings.text(activity, id, *args)
    /** A format template as written, for code that fills it itself. */
    fun template(id: Int) = AppLanguageSettings.template(activity, id)
    fun tq(id: Int, quantity: Int, vararg args: Any) = AppLanguageSettings.quantityText(activity, id, quantity, *args)

    /** An amount of energy as every screen writes it: one decimal, in the screen's number locale. */
    fun kwhText(kwh: Double) = EnergyText.kwh(kwh, AppLanguageSettings.numberLocale(activity))

    fun runOnUiThread(action: () -> Unit) = activity.runOnUiThread(action)
    fun startActivity(intent: Intent) = activity.startActivity(intent)
    fun getString(id: Int): String = activity.getString(id)
}
