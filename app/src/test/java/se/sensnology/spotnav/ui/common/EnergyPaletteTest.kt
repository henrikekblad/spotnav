package se.sensnology.spotnav.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ui.site.SiteColours
import se.sensnology.spotnav.ui.site.Tone
import kotlin.math.pow

/** The energy palette, the cards' `energy-palette.ts`: one colour per meaning, readable on a light and a dark card. */
class EnergyPaletteTest {
    private fun luminance(colour: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((colour shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    @Test fun everyMeaningKeeps3To1OnALightCardInItsLightValueAndOnADarkCardInItsDarkOne() {
        for (meaning in Energy.entries - Energy.IDLE - Energy.RING) {
            assertTrue("$meaning light", contrast(meaning.light, 0xFFFFFFFF.toInt()) >= 3.0)
            assertTrue("$meaning dark", contrast(meaning.dark, 0xFF1C1C1C.toInt()) >= 3.0)
        }
    }

    @Test fun theValuesAreTheCards() {
        assertEquals(0xFFC2408F.toInt(), EnergyPalette.LIGHT.of(Energy.CAR))
        assertEquals(0xFFEC6FB5.toInt(), EnergyPalette.DARK.of(Energy.CAR))
        assertEquals(0xFF3B82F6.toInt(), EnergyPalette.LIGHT.of(Energy.IMPORT))
        assertEquals(0xFF34C26B.toInt(), EnergyPalette.DARK.of(Energy.EXPORT))
        assertNotEquals(Energy.CAR.light, Energy.HOUSE.light)
        assertNotEquals(Energy.CAR.light, Energy.HEAT.light)
    }

    @Test fun eachToneIsAMeaningAndTheGridIsItsImport() {
        assertEquals(Energy.IMPORT, SiteColours.meaning(Tone.GRID))
        assertEquals(Energy.IMPORT, SiteColours.meaning(Tone.IMPORT))
        assertEquals(Energy.EXPORT, SiteColours.meaning(Tone.EXPORT))
        assertEquals(Energy.POOL, SiteColours.meaning(Tone.POOL))
        assertEquals(Energy.OTHER, SiteColours.meaning(Tone.OTHER))
        assertEquals(Tone.entries.size, Tone.entries.map(SiteColours::meaning).size)
    }

    @Test fun theEnergyFlowsBlocksAreAStepLighterAndWhatIsSoldIsTheExports() {
        assertEquals(0xFFFFFFFF.toInt(), EnergyPalette.mixWhite(0xFF000000.toInt(), 0f))
        assertEquals(0xFF3B82F6.toInt(), EnergyPalette.mixWhite(0xFF3B82F6.toInt(), 1f))
        val light = EnergyPalette.LIGHT
        assertEquals(EnergyPalette.mixWhite(Energy.IMPORT.light, 0.72f), light.flowBlock("grid", source = true))
        assertEquals(EnergyPalette.mixWhite(Energy.EXPORT.light, 0.72f), light.flowBlock("grid", source = false))
        assertEquals(EnergyPalette.mixWhite(Energy.SUN.light, 0.78f), light.flowBlock("solar", source = true))
        assertEquals(EnergyPalette.mixWhite(Energy.CAR.light, 0.72f), light.flowBlock("car", source = false))
        assertEquals(EnergyPalette.mixWhite(Energy.OTHER.light, 0.72f), light.flowBlock("pool", source = false))
        // Dark words (the flow's ink) still read on every block.
        for (role in listOf("solar", "battery", "grid", "house", "car", "heat", "other")) {
            for (palette in listOf(EnergyPalette.LIGHT, EnergyPalette.DARK)) {
                assertTrue(role, contrast(palette.flowBlock(role, true), 0xFF15151C.toInt()) >= 4.5)
            }
        }
    }
}
