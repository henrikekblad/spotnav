package se.sensnology.spotnav.ui.site

import android.graphics.Color
import se.sensnology.spotnav.ui.common.Energy
import se.sensnology.spotnav.ui.common.EnergyPalette
import se.sensnology.spotnav.ui.common.Palette

/**
 * The colours of the house and heat pages: each part's own from the energy palette ([EnergyPalette], the Home Assistant
 * cards' `frontend/src/energy-palette.ts`), light or dark with the screen, and what is mixed from the screen's own text
 * and card colours.
 */
internal class SiteColours(private val palette: Palette) {
    val text: Int get() = palette.dark
    val muted: Int get() = palette.muted
    val card: Int get() = palette.cardBackground

    /** A line with nothing flowing, and a rule under the tabs. */
    val wire: Int = mix(palette.muted, palette.cardBackground, 0.32f)

    /** The energy palette for this screen. */
    val energy: EnergyPalette get() = palette.energy

    /** A part's colour as its disc, icon and lines have it; the grid is its import's. */
    fun of(tone: Tone): Int = energy.of(meaning(tone))

    /** A meaning of the energy palette: an idle line, a ring's track, a price band. */
    fun of(meaning: Energy): Int = energy.of(meaning)

    /**
     * A figure in its part's colour, a third of the way toward the text colour: darker on a light screen, lighter on
     * a dark one, so it keeps its contrast on both. The house's own figure is plain text.
     */
    fun figure(tone: Tone): Int = if (tone == Tone.HOUSE) text else mix(of(tone), text, 0.68f)

    companion object {
        fun meaning(tone: Tone): Energy = when (tone) {
            Tone.SUN -> Energy.SUN
            Tone.BATTERY -> Energy.BATTERY
            Tone.CAR -> Energy.CAR
            Tone.HEAT -> Energy.HEAT
            Tone.GRID, Tone.IMPORT -> Energy.IMPORT
            Tone.EXPORT -> Energy.EXPORT
            Tone.HOUSE -> Energy.HOUSE
            Tone.POOL -> Energy.POOL
            Tone.OTHER -> Energy.OTHER
        }

        /** [share] of [first] and the rest of [second], channel by channel. */
        fun mix(first: Int, second: Int, share: Float): Int {
            fun channel(a: Int, b: Int) = (a * share + b * (1f - share)).toInt().coerceIn(0, 255)
            return Color.rgb(
                channel(Color.red(first), Color.red(second)),
                channel(Color.green(first), Color.green(second)),
                channel(Color.blue(first), Color.blue(second))
            )
        }
    }
}
