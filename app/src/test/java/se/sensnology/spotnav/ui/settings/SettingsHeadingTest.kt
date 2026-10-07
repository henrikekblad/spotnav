package se.sensnology.spotnav.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** A settings card's heading names what it is about: "Charger · HALO Charger", "Site · My Home". */
class SettingsHeadingTest {
    @Test fun aHeadingNamesItsSubjectAfterItsKind() {
        assertEquals("Laddare · HALO Charger", SettingsHeading.named("Laddare", "HALO Charger"))
        assertEquals("Anläggning · My Home", SettingsHeading.named("Anläggning", " My Home "))
    }

    @Test fun withoutANameTheKindAloneIsTheHeading() {
        assertEquals("Anläggning", SettingsHeading.named("Anläggning", null))
        assertEquals("Laddare", SettingsHeading.named("Laddare", "  "))
    }
}
