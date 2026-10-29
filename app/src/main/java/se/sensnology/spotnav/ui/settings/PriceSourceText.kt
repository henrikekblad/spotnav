package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.dashboard.PriceFees
import java.text.NumberFormat
import java.util.Locale

/**
 * The words of the electricity price a paired charger uses, as the card words them (`card-view.ts`'s
 * `priceEntryNodes`): what the price is called, how many chargers use it, each choice's name, and one entry's fees on a
 * line. Pure: the screen hands in its own strings ([Words]).
 */
internal object PriceSourceText {
    /** The strings the words are made of, in the reader's language. */
    interface Words {
        /** "Electricity price SE4". */
        fun title(area: String): String
        fun usedByAll(title: String, count: Int): String
        fun usedBy(title: String, count: Int): String
        fun here(title: String): String
        fun other(title: String): String
        fun off(): String
        fun included(): String
        fun unset(): String
        fun vat(value: String): String
        fun tax(value: String): String
        fun transfer(value: String): String
    }

    private val SPOTNAV_TITLE = Regex("^Electricity price ([A-Za-z0-9-]+)( \\(.+\\))?$")

    /**
     * An electricity price's name as shown: the name SpotNav gives one ("Electricity price SE4", or "Electricity
     * price SE4 (Home)" beside another for the same area) in the reader's language, the part in brackets as it is;
     * any other name as it is.
     */
    fun title(raw: String, words: Words): String {
        val given = SPOTNAV_TITLE.matchEntire(raw) ?: return raw
        return words.title(given.groupValues[1]) + given.groupValues[2]
    }

    /**
     * The line naming the price: its name alone when only this charger uses it, else with how many chargers do
     * ("used by all 2 chargers" when that is every charger there is, [chargerTotal]).
     */
    fun sourceLine(raw: String, count: Int, chargerTotal: Int, words: Words): String {
        val title = title(raw, words)
        return when {
            count <= 1 -> title
            count >= maxOf(chargerTotal, count) -> words.usedByAll(title, count)
            else -> words.usedBy(title, count)
        }
    }

    /**
     * Each entry's name for a list of them, by entry id. Two with the same name are told apart: the one this charger
     * uses ([current]) says so, the other is "the other" (numbered when there are several others).
     */
    fun labels(entries: List<Pair<String, String>>, current: String, words: Words): Map<String, String> {
        val shown = entries.map { (id, raw) -> id to title(raw, words) }
        return shown.associate { (id, title) ->
            val same = shown.filter { it.second == title }
            id to when {
                same.size < 2 -> title
                id == current -> words.here(title)
                else -> {
                    val others = same.filter { it.first != current }
                    val label = words.other(title)
                    if (others.size > 1) "$label ${others.indexOfFirst { it.first == id } + 1}" else label
                }
            }
        }
    }

    /** One entry's fees on a line, "VAT 25 % · tax 36 öre/kWh · grid 30 öre/kWh"; `null` when they cannot be read. */
    fun feesLine(fees: PriceFees?, locale: Locale, words: Words): String? {
        fees ?: return null
        fun value(component: PriceFees.Component): String = when {
            component.policy == "included" -> words.included()
            component.policy == "off" -> words.off()
            component.effective == null -> words.unset()
            else -> {
                val figure = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(component.effective)
                if (component.unit == null) figure else "$figure ${component.unit}"
            }
        }
        return listOf(words.vat(value(fees.vat)), words.tax(value(fees.tax)), words.transfer(value(fees.transfer)))
            .joinToString(" · ")
    }
}
