package se.sensnology.spotnav.ui.charging

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words of the charger card's two short rows, kept apart from the view so they are tested
 * directly: the charging-current label that carries the phase count of a paired charger
 * ("Charging current \u00B7 3 phases"), and the abbreviated month of the charge-history value.
 */
internal object ChargingPhasesText {
    /** The current row's label: [withPhases] for a known count, else the plain [plain] label. */
    fun currentLabel(phases: Int?, plain: String, withPhases: (Int) -> String): String =
        if (phases == null) plain else withPhases(phases)

    /** The month of [date] abbreviated as [locale] writes it ("Oct", "okt."), for the history row. */
    fun monthAbbreviation(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLL", locale).format(date)
}
