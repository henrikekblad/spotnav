package se.sensnology.spotnav.app

import android.content.Context
import android.os.LocaleList
import se.sensnology.spotnav.R
import java.util.Locale

object AppLanguageSettings {
    const val SYSTEM = "system"
    private const val PREFS = "app_language"
    private const val KEY = "language"

    data class Choice(val code: String, val label: Int)

    val choices = listOf(
        Choice(SYSTEM, R.string.language_system),
        Choice("sv", R.string.language_swedish),
        Choice("nb", R.string.language_norwegian),
        Choice("da", R.string.language_danish),
        Choice("fi", R.string.language_finnish),
        Choice("en", R.string.language_english)
    )

    fun selected(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, SYSTEM) ?: SYSTEM

    fun save(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    fun language(context: Context): String {
        val selected = selected(context)
        if (selected != SYSTEM) return selected
        return when (context.resources.configuration.locales[0].language.lowercase()) {
            "sv" -> "sv"; "no", "nb", "nn" -> "nb"; "da" -> "da"; "fi" -> "fi"; else -> "en"
        }
    }

    fun region(context: Context): String = when (selected(context)) {
        "sv" -> "SE"; "nb" -> "NO"; "da" -> "DK"; "fi" -> "FI"
        else -> context.resources.configuration.locales[0].country
    }

    fun locale(context: Context): Locale = Locale.forLanguageTag(language(context))

    /**
     * The locale numbers are written in: the one the screen's own rows get from their resources --
     * the device's (with its region) on "system", the chosen language's otherwise.
     */
    fun numberLocale(context: Context): Locale =
        if (selected(context) == SYSTEM) context.resources.configuration.locales[0] else locale(context)

    fun text(context: Context, id: Int, vararg args: Any): String {
        return localizedContext(context).getString(id, *args)
    }

    fun quantityText(context: Context, id: Int, quantity: Int, vararg args: Any): String =
        localizedContext(context).resources.getQuantityString(id, quantity, *args)

    private fun localizedContext(context: Context): Context =
        if (selected(context) == SYSTEM) context else {
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocales(LocaleList(locale(context)))
            context.createConfigurationContext(configuration)
        }
}
