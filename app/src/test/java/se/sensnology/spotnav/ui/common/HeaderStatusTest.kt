package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The status after a card's name in its header, as the Home Assistant card's shared header writes it. */
class HeaderStatusTest {
    @Test fun theStatusFollowsTheNameAfterADot() {
        assertEquals("Garage · Väntar", HeaderStatus.text("Garage", "Väntar"))
    }

    @Test fun aStatusOfSeveralWordsIsHeldTogetherSoItWrapsUnderALongNameAsAWhole() {
        val text = HeaderStatus.text("Laddboxen i garaget", "Ansluten · väntar på svar")
        assertEquals("Laddboxen i garaget · Ansluten · väntar på svar", text)
        // The one place a line may break is between the name and its status.
        assertEquals(1, text.removePrefix("Laddboxen i garaget").count { it == ' ' })
    }

    @Test fun withNothingToSayTheNameStandsAlone() {
        assertEquals("Garage", HeaderStatus.text("Garage", null))
        assertEquals("Garage", HeaderStatus.text("Garage", "  "))
        assertNull(HeaderStatus.suffix(""))
    }
}
