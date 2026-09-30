package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Test

/** The chart's palette: one complete dark data panel in both system themes. */
class ChartThemeTest {
    @Test
    fun theDarkPanelIsTheEstablishedPaletteUnchanged() {
        val dark = ChartTheme.Dark

        assertEquals(0xF21A2029.toInt(), dark.surface)
        assertEquals(0xFFF5F7FA.toInt(), dark.text)
        assertEquals(0xFF9FA8B5.toInt(), dark.muted)
        assertEquals(0xFF4B9FEA.toInt(), dark.band)
        assertEquals(0x243E4956, dark.grid)
        assertEquals(0xFF43C887.toInt(), dark.cheap)
        assertEquals(0xFFFF625F.toInt(), dark.expensive)
        assertEquals(0xFFC5CBD3.toInt(), dark.tomorrow)
        assertEquals("the focus ink is the surface's high-contrast foreground", ChartTheme.INK_ON_DARK, dark.nowLine)
        assertEquals("one role for both lines", dark.nowLine, dark.selectionLine)
    }
}
