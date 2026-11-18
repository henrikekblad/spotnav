package se.sensnology.spotnav.chart

/**
 * The chart's whole palette. The chart is an intentional dark data panel that paints its own surface in
 * every system theme, so one type holds every colour the renderer uses.
 */
internal data class ChartTheme(
    val surface: Int,
    val text: Int,
    val muted: Int,
    val band: Int,
    val grid: Int,
    val cheap: Int,
    val expensive: Int,
    val tomorrow: Int,
    /** The high-contrast ink of [surface], drawn solid: the now line. */
    val nowLine: Int,
    /** The same ink, drawn dashed: the selection line. */
    val selectionLine: Int,
    /** The sun behind the price marks: the house card's amber, as a light area and a line. */
    val sun: Int = SUN
) {
    companion object {
        /** Near-white ink that reads against the chart's dark surface. */
        const val INK_ON_DARK: Int = 0xFFFFFFFF.toInt()

        /** The card's `--spotnav-sun`. */
        const val SUN: Int = 0xFFE8A200.toInt()

        /** The dark data panel palette. */
        val Dark = ChartTheme(
            surface = 0xF21A2029.toInt(),
            text = 0xFFF5F7FA.toInt(),
            muted = 0xFF9FA8B5.toInt(),
            band = 0xFF4B9FEA.toInt(),
            grid = 0x243E4956,
            cheap = 0xFF43C887.toInt(),
            expensive = 0xFFFF625F.toInt(),
            tomorrow = 0xFFC5CBD3.toInt(),
            nowLine = INK_ON_DARK,
            selectionLine = INK_ON_DARK
        )
    }
}
