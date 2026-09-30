package se.sensnology.spotnav.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DistanceUnitTest {
    private fun text(language: String, tag: String) = DistanceUnit.text(15.2, language, Locale.forLanguageTag(tag))

    @Test fun swedishAndNorwegianWriteMilAndTheOthersKilometres() {
        assertEquals("15,2 mil", text("sv", "sv"))
        assertEquals("15,2 mil", text("nb", "nb"))
        assertEquals("152 km", text("en", "en-SE"))
        assertEquals("152 km", text("en", "en-US"))
        assertEquals("152 km", text("da", "da"))
        assertEquals("152 km", text("fi", "fi"))
    }

    @Test fun costIsWrittenAlikeByThePlanCardAndTheStatusLine() {
        val enSe = Locale.forLanguageTag("en-SE")
        val fromCard = se.sensnology.spotnav.ui.settings.CostLabel.amount(
            38.58, se.sensnology.spotnav.ui.settings.AreaMoneyFacts.Available("SE4", "SEK", "kr"), enSe
        )
        assertEquals("38,58 kr", fromCard)
    }
}
