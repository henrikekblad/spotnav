package se.sensnology.spotnav.app

import java.util.Locale

/**
 * How a distance is written: miles for an area in Great Britain whatever the language, mil (10 km)
 * for Swedish and Norwegian, kilometres otherwise. Consumption stays in kWh per 10 km everywhere, so
 * only this display converts and nothing stored does.
 */
object DistanceUnit {
    /** Kilometres in one statute mile. */
    const val KM_PER_MILE = 1.609344

    fun usesMil(language: String): Boolean = language == "sv" || language == "nb"

    /**
     * `"15,2 mil"`, `"152 km"` or `"94 mi"`: [mil] in the unit of [language] (or miles when [miles]),
     * the digits written in [locale].
     */
    fun text(mil: Double, language: String, locale: Locale, miles: Boolean = false): String = when {
        miles -> String.format(locale, "%.0f mi", mil * 10 / KM_PER_MILE)
        usesMil(language) -> String.format(locale, "%.1f mil", mil)
        else -> String.format(locale, "%.0f km", mil * 10)
    }
}
