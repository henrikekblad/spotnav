package se.sensnology.spotnav.app

import java.util.Locale

/** How an amount of energy is written on screen: one decimal, the digits in the screen's number locale. */
object EnergyText {
    /** `"30,0 kWh"` or `"30.0 kWh"`: [kwh] with one decimal, written in [locale]. */
    fun kwh(kwh: Double, locale: Locale): String = String.format(locale, "%.1f kWh", kwh)
}
