package se.sensnology.spotnav.app

import java.util.Locale

/** How a distance is written: mil (10 km) for Swedish and Norwegian, kilometres otherwise. */
object DistanceUnit {
    fun usesMil(language: String): Boolean = language == "sv" || language == "nb"

    /** `"15,2 mil"` or `"152 km"`: [mil] in the unit of [language], the digits written in [locale]. */
    fun text(mil: Double, language: String, locale: Locale): String =
        if (usesMil(language)) String.format(locale, "%.1f mil", mil)
        else String.format(locale, "%.0f km", mil * 10)
}
