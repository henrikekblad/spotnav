package se.sensnology.spotnav.app

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** How an amount of money is written on screen. */
object MoneyText {
    /** The currency written the way the reader's locale writes money ("£1.33", "1,33 £"). */
    private const val LOCALE_FORMATTED = "GBP"

    /**
     * `"34.60 kr"` -- two decimals and the market's own [unit] after them, as every area has always
     * been written -- or, for the pound (Great Britain), money as [locale] writes it with the symbol:
     * `"£1.33"` in English, `"1,33 £"` in Swedish.
     */
    fun amount(value: Double, currency: String?, unit: String, locale: Locale): String {
        if (currency == LOCALE_FORMATTED) {
            val formatted = runCatching {
                (NumberFormat.getCurrencyInstance(locale) as DecimalFormat).apply {
                    this.currency = Currency.getInstance(currency)
                    decimalFormatSymbols = decimalFormatSymbols.apply { currencySymbol = unit.ifBlank { "£" } }
                    minimumFractionDigits = 2
                    maximumFractionDigits = 2
                }.format(value)
            }.getOrNull()
            if (formatted != null) return formatted
        }
        return String.format(locale, "%.2f %s", value, unit).trim()
    }
}
