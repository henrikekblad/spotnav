package se.sensnology.spotnav.ui.charging

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** A percent as the screen writes it: whole when it is whole ("80 %"), one decimal otherwise, in the reader's mark. */
class PercentFormatTest {
    @Test fun aWholePercentHasNoDecimal() {
        assertEquals("80\u00A0%", PercentFormat.text(80.0, Locale("sv")))
        assertEquals("80,5\u00A0%", PercentFormat.text(80.5, Locale("sv")))
        assertEquals("80.5\u00A0%", PercentFormat.text(80.5, Locale.ENGLISH))
    }
}
