package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

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

    @Test fun aNumberIsShownInTheFieldWithItsOwnDecimals() {
        assertEquals("77.4", capacity.fieldText(77.4))
        assertEquals("80", percent.fieldText(80.0))
    }
}
