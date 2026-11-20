package se.sensnology.spotnav.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.prices.PriceFormat
import se.sensnology.spotnav.prices.PriceUnit
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.ui.site.SiteText
import java.util.Locale

/** A figure that rounds to zero at the digits shown never carries a minus sign; real negatives keep theirs. */
class NoMinusZeroTest {
    private val sv = Locale.forLanguageTag("sv")
    private val en = Locale.ENGLISH

    private fun text(locale: Locale) = SiteText(CardStringsXml.words(locale.language), locale)

    /** Swedish writes the real minus sign; the test compares with a plain hyphen. */
    private fun plain(s: String) = s.replace('−', '-')

    @Test
    fun helperTurnsNegativeZeroAndTinyNegativesIntoZero() {
        assertTrue(1.0 / noMinusZero(-0.0, 2) > 0)
        assertTrue(1.0 / noMinusZero(-0.004, 2) > 0)
        assertEquals(-0.005, noMinusZero(-0.005, 2), 0.0)
        assertEquals(-0.5, noMinusZero(-0.5, 2), 0.0)
        assertEquals(-0.5, noMinusZero(-0.5, 0), 0.0)
        assertTrue(1.0 / noMinusZero(-0.4, 0) > 0)
        assertTrue(noMinusZero(Double.NaN, 1).isNaN())
    }

    @Test
    fun houseFiguresInSwedish() {
        val t = text(sv)
        val money = House.Money("SEK", "kr", "öre")
        assertEquals("0,00", t.fixed(-0.0, 2))
        assertEquals("0,00", t.fixed(-0.001, 2))
        assertEquals("0", t.fixed(-0.3, 0))
        assertEquals("-0,50", plain(t.fixed(-0.5, 2)))
        assertEquals("0 kr", t.amount(-0.0, money))
        assertEquals("0,00 kr", t.amount(-0.04, money))
        assertEquals("-0,50 kr", plain(t.amount(-50.0, money)))
        assertEquals("0,0 kWh", t.kwh(-0.01))
        assertEquals("0,0 kW", t.kw(-0.04))
        assertEquals("0 %", t.percent(-0.2))
    }

    @Test
    fun houseFiguresInEnglish() {
        val t = text(en)
        val money = House.Money("USD", "$", "c")
        assertEquals("0.00 $", t.amount(-0.04, money))
        assertEquals("0.0 kWh", t.kwh(-0.01))
        assertFalse(plain(t.percent(-0.2)).contains("-"))
        assertEquals("-0.50 $", plain(t.amount(-50.0, money)))
    }

    @Test
    fun priceFormatKeepsRealNegativesOnly() {
        val f = PriceFormat(PriceUnit.MAJOR, "öre", "kr", sv)
        assertEquals("0,00", f.figure(-0.3))
        assertEquals("0,00", f.figure(-0.0))
        assertEquals("-0,50", plain(f.figure(-50.0)))
        val e = PriceFormat(PriceUnit.MINOR, "c", "$", en)
        assertEquals("0", e.figure(-0.04))
        assertEquals("-0.5", plain(e.figure(-0.5)))
    }
}
