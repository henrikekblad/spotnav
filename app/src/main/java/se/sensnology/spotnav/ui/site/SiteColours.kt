package se.sensnology.spotnav.ui.site

import android.graphics.Color
import se.sensnology.spotnav.ui.common.Palette

/**
 * The colours of the house and heat pages: each part's own, the Home Assistant card's (`frontend/src/house-styles.ts`),
 * the same on a light and a dark screen, and what is mixed from the screen's own text and card colours.
 */
internal class SiteColours(private val palette: Palette) {
    val text: Int get() = palette.dark
    val muted: Int get() = palette.muted
    val card: Int get() = palette.cardBackground

    /** A line with nothing flowing, and a rule under the tabs. */
    val wire: Int = mix(palette.muted, palette.cardBackground, 0.32f)

    /** A part's colour as its disc, icon and dots have it. */
    fun of(tone: Tone): Int = when (tone) {
        Tone.SUN -> 0xFFE8A200.toInt()
        Tone.BATTERY -> 0xFF129C90.toInt()
        Tone.CAR -> 0xFFE07B2E.toInt()
        Tone.HEAT -> 0xFFE5554B.toInt()
        Tone.GRID -> 0xFF5C6BD6.toInt()
        Tone.IMPORT -> 0xFFD64541.toInt()
        Tone.EXPORT -> 0xFF2E9D57.toInt()
        Tone.HOUSE -> 0xFF8F63D8.toInt()
    }

    /**
     * A figure in its part's colour, a third of the way toward the text colour: darker on a light screen, lighter on
     * a dark one, so it keeps its contrast on both. The house's own figure is plain text.
     */
    fun figure(tone: Tone): Int = if (tone == Tone.HOUSE) text else mix(of(tone), text, 0.68f)

    companion object {
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
