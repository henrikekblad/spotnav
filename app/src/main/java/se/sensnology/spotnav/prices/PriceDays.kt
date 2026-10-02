package se.sensnology.spotnav.prices

import java.time.LocalDate

/** Which day a price result is the answer for. */
internal object PriceDays {
    /**
     * Whether [result]'s `today` rows are the rows of [date] (in the market's zone, as the rows carry it).
     * A result with no `today` rows shows no date, so it never counts as today's.
     */
    fun showsDate(result: PriceResult, date: LocalDate): Boolean =
        result.today.firstOrNull()?.start?.toLocalDate() == date
}
