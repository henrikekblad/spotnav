package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The generic number editor's judgement of what was typed. */
class ValueEditorsTest {
    private val capacity = NumberSpec(min = 1.0, max = 500.0, decimals = 1)
    private val percent = NumberSpec(min = 0.0, max = 100.0, decimals = 0)

    @Test fun aTypedNumberIsReadWithEitherDecimalMarkAndRoundedToItsDecimals() {
        assertEquals(NumberCheck.Valid(77.4), capacity.check("77,4"))
        assertEquals(NumberCheck.Valid(77.4), capacity.check(" 77.44 "))
        assertEquals(NumberCheck.Valid(85.0), percent.check("84.6"))
    }

    @Test fun somethingElseOrOutsideTheRangeIsRefusedAndNeverClamped() {
        assertEquals(NumberCheck.NotANumber, capacity.check("abc"))
        assertEquals(NumberCheck.NotANumber, capacity.check(""))
        assertEquals(NumberCheck.OutOfRange, capacity.check("0"))
        assertEquals(NumberCheck.OutOfRange, percent.check("101"))
        assertEquals(NumberCheck.Valid(0.0), percent.check("0"))
    }

    @Test fun aNumberIsShownInTheFieldAsTheRowShowsItInTheReadersOwnDecimalMark() {
        val consumption = NumberSpec(min = 0.1, max = 50.0, decimals = 1, minDecimals = 1)
        val fee = NumberSpec(min = 0.0, max = 10_000.0, decimals = 2)
        assertEquals("77.4", capacity.fieldText(77.4, Locale.ENGLISH))
        assertEquals("80", percent.fieldText(80.0, Locale.ENGLISH))
        // A consumption keeps its decimal, as the row does ("2.0 kWh/10 km").
        assertEquals("2.0", consumption.fieldText(2.0, Locale.ENGLISH))
        // A fee shows no decimals it does not have ("36", "36,5"), with the reader's own mark.
        assertEquals("36", fee.fieldText(36.0, Locale("sv")))
        assertEquals("36,5", fee.fieldText(36.5, Locale("sv")))
        assertEquals("7.13", fee.fieldText(7.13, Locale.ENGLISH))
        // What the field shows reads back as the same number.
        assertEquals(NumberCheck.Valid(36.5), fee.check("36,5"))
    }
}
