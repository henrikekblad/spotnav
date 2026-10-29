package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.PriceFees
import java.util.Locale

/** The electricity price's words, as the card words them; Swedish and English from the app's own strings. */
class PriceSourceTextTest {
    private class Words(private val sv: Boolean) : PriceSourceText.Words {
        override fun title(area: String) = if (sv) "Elpris $area" else "Electricity price $area"
        override fun usedByAll(title: String, count: Int) =
            if (sv) "$title · används av alla $count laddare" else "$title · used by all $count chargers"
        override fun usedBy(title: String, count: Int) =
            if (sv) "$title · används av $count laddare" else "$title · used by $count chargers"
        override fun here(title: String) = if (sv) "$title (används här)" else "$title (used here)"
        override fun other(title: String) = if (sv) "$title (det andra)" else "$title (the other)"
        override fun off() = if (sv) "Av" else "Off"
        override fun included() = "Included in the price"
        override fun unset() = "Not set"
        override fun vat(value: String) = if (sv) "moms $value" else "VAT $value"
        override fun tax(value: String) = if (sv) "skatt $value" else "tax $value"
        override fun transfer(value: String) = if (sv) "nät $value" else "grid $value"
    }

    private val sv = Words(true)
    private val en = Words(false)

    @Test fun thePriceIsNamedInTheReadersLanguageWithHowManyChargersUseIt() {
        assertEquals("Elpris SE4 · används av alla 2 laddare", PriceSourceText.sourceLine("Electricity price SE4", 2, 2, sv))
        assertEquals("Electricity price SE4 · used by all 2 chargers", PriceSourceText.sourceLine("Electricity price SE4", 2, 1, en))
        assertEquals("Elpris SE4 · används av 2 laddare", PriceSourceText.sourceLine("Electricity price SE4", 2, 3, sv))
        // Only this charger: the name alone.
        assertEquals("Elpris SE3 (Hemma)", PriceSourceText.sourceLine("Electricity price SE3 (Hemma)", 1, 3, sv))
        // A name of the owner's own is kept as it is.
        assertEquals("Mitt elpris", PriceSourceText.sourceLine("Mitt elpris", 0, 3, sv))
    }

    @Test fun twoPricesWithTheSameNameAreToldApart() {
        val entries = listOf("a" to "Electricity price SE4", "b" to "Electricity price SE4", "c" to "Electricity price SE3")
        assertEquals(
            mapOf("a" to "Elpris SE4 (används här)", "b" to "Elpris SE4 (det andra)", "c" to "Elpris SE3"),
            PriceSourceText.labels(entries, "a", sv)
        )
        val three = listOf("a" to "Electricity price SE4", "b" to "Electricity price SE4", "c" to "Electricity price SE4")
        assertEquals(
            mapOf("a" to "Electricity price SE4 (used here)", "b" to "Electricity price SE4 (the other) 1", "c" to "Electricity price SE4 (the other) 2"),
            PriceSourceText.labels(three, "a", en)
        )
    }

    @Test fun anEntrysFeesFitOnOneLine() {
        val fees = PriceFees(
            PriceFees.Component("explicit", 25.0, "%"),
            PriceFees.Component("explicit", 36.5, "öre/kWh"),
            PriceFees.Component("off", null, "öre/kWh")
        )
        assertEquals("moms 25 % · skatt 36,5 öre/kWh · nät Av", PriceSourceText.feesLine(fees, Locale.forLanguageTag("sv"), sv))
        val included = fees.copy(vat = PriceFees.Component("included", 25.0, "%"), transfer = PriceFees.Component("explicit", null, null))
        assertEquals(
            "VAT Included in the price · tax 36.5 öre/kWh · grid Not set",
            PriceSourceText.feesLine(included, Locale.UK, en)
        )
        assertNull(PriceSourceText.feesLine(null, Locale.UK, en))
    }
}
