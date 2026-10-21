package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.FakeKeyValueStore
import java.util.Locale

/** Per-kWh prices in the chosen unit, with the Home Assistant card's figures. Prices arrive in the minor unit. */
class PriceFormatTest {
    private val sv = Locale.forLanguageTag("sv")
    private fun format(unit: PriceUnit, minor: String? = "öre", major: String? = "kr", locale: Locale = sv) =
        PriceFormat(unit, minor, major, locale)

    @Test
    fun theMinorUnitIsATenthWithZerosTrimmed() {
        val minor = format(PriceUnit.MINOR)
        assertEquals("163,1", minor.figure(163.1))
        assertEquals("92", minor.figure(92.0))
        assertEquals("44,4", minor.figure(44.36))
        assertEquals("163,1 öre/kWh", minor.perKwh(163.1))
        assertEquals("öre/kWh", minor.perKwhUnit)
    }

    @Test
    fun theMajorUnitIsTwoDecimalsRoundedInWholeMinorUnitsFirst() {
        val major = format(PriceUnit.MAJOR)
        assertEquals("1,63", major.figure(163.1))
        assertEquals("2,09", major.figure(208.5))
        assertEquals("0,92", major.figure(92.0))
        assertEquals("1,63 kr/kWh", major.perKwh(163.1))
        assertEquals("1.63 €/kWh", format(PriceUnit.MAJOR, "cent", "€", Locale.UK).perKwh(163.1))
    }

    @Test
    fun aMarketWithoutBothUnitsAlwaysShowsTheMinorOne() {
        val noMajor = format(PriceUnit.MAJOR, major = "")
        assertFalse(noMajor.choosable)
        assertEquals("163,1 öre/kWh", noMajor.perKwh(163.1))
        assertFalse(format(PriceUnit.MAJOR, minor = null).choosable)
        assertTrue(format(PriceUnit.MINOR).choosable)
    }

    @Test
    fun theAxisIsWholeMinorUnitsOrMajorDecimalsByTheSpan() {
        val minor = format(PriceUnit.MINOR)
        assertEquals(listOf("208", "104", "0"), listOf(208.0, 104.0, 0.0).map { minor.axisLabel(it, 208.0) })
        val major = format(PriceUnit.MAJOR)
        // A 0-208 öre axis: 2,08 kr, so one decimal.
        assertEquals(listOf("2,1", "1,0", "0"), listOf(208.0, 104.0, 0.0).map { major.axisLabel(it, 208.0) })
        assertEquals(listOf("0,80", "0,40", "0"), listOf(80.0, 40.0, 0.0).map { major.axisLabel(it, 80.0) })
        assertEquals(listOf("12", "6", "0"), listOf(1200.0, 600.0, 0.0).map { major.axisLabel(it, 1200.0) })
    }

    @Test
    fun thePreferenceIsMinorUntilMajorIsChosen() {
        val store = FakeKeyValueStore()
        val preference = PriceUnitPreference(store)
        assertEquals(PriceUnit.MINOR, preference.unit())
        store.setRaw(PriceUnitPreference.KEY, "sideways")
        assertEquals(PriceUnit.MINOR, preference.unit())
        preference.save(PriceUnit.MAJOR)
        assertEquals("major", store.rawOrNull(PriceUnitPreference.KEY))
        assertEquals(PriceUnit.MAJOR, preference.unit())
    }

    @Test
    fun theSettingsExamplesNameTheMarketsOwnUnits() {
        val market = PriceMarket("FI", listOf("FI"), "Finland", "Europe/Helsinki", "EUR", "€", "cent")
        assertEquals("163.1 cent/kWh", PriceFormat.of(PriceUnit.MINOR, market, Locale.UK).perKwh(PriceFormat.EXAMPLE))
        assertEquals("1.63 €/kWh", PriceFormat.of(PriceUnit.MAJOR, market, Locale.UK).perKwh(PriceFormat.EXAMPLE))
        assertFalse("no market, nothing to choose", PriceFormat.of(PriceUnit.MAJOR, null, Locale.UK).choosable)
    }
}
