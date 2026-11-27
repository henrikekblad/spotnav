package se.sensnology.spotnav.ui.common

/**
 * What energy is, one colour per meaning, as every Home Assistant card draws it (`frontend/src/energy-palette.ts`): the
 * sun, the grid's import and what is sold, the battery, the car, the house and its parts (heat, the pool with the spa
 * and the sauna, the rest), the price bands, and an idle line and an empty ring. The car is magenta, apart from the
 * house's violet and the heat's red. Each meaning has a light value and a brighter dark one, so it keeps 3:1 against
 * the card on both screens.
 */
internal enum class Energy(val light: Int, val dark: Int) {
    SUN(0xFFC18B00.toInt(), 0xFFF2B705.toInt()),
    IMPORT(0xFF3B82F6.toInt(), 0xFF5B9BFF.toInt()),
    EXPORT(0xFF16A34A.toInt(), 0xFF34C26B.toInt()),
    BATTERY(0xFF0F9F8F.toInt(), 0xFF22C3B0.toInt()),
    CAR(0xFFC2408F.toInt(), 0xFFEC6FB5.toInt()),
    HOUSE(0xFF6D5BD0.toInt(), 0xFF9A8CF0.toInt()),
    HEAT(0xFFE2554A.toInt(), 0xFFF06A5F.toInt()),
    POOL(0xFF289ED0.toInt(), 0xFF4BB9E6.toInt()),
    OTHER(0xFF8796AB.toInt(), 0xFF7D8A9A.toInt()),
    CHEAP(0xFF16A34A.toInt(), 0xFF34C26B.toInt()),
    MID(0xFFBF8B00.toInt(), 0xFFE7B416.toInt()),
    EXPENSIVE(0xFFDC2626.toInt(), 0xFFF2564F.toInt()),
    IDLE(0xFFC9D1DB.toInt(), 0xFF36404C.toInt()),
    RING(0xFFE6EAF0.toInt(), 0xFF2A323D.toInt())
}

/** The energy palette for a light or a dark screen ([night]). */
internal class EnergyPalette(val night: Boolean) {
    fun of(meaning: Energy): Int = if (night) meaning.dark else meaning.light

    /**
     * The energy flow's block for a role (`solar`, `grid`, …) on its side: the meaning a step lighter (72 % of it,
     * the sun 78 %, mixed with white) for the dark words on it. The grid as a use is what is sold: the export's.
     */
    fun flowBlock(role: String, source: Boolean): Int {
        val meaning = when (role) {
            "solar" -> Energy.SUN
            "battery" -> Energy.BATTERY
            "grid" -> if (source) Energy.IMPORT else Energy.EXPORT
            "house" -> Energy.HOUSE
            "car" -> Energy.CAR
            "heat" -> Energy.HEAT
            else -> Energy.OTHER
        }
        return mixWhite(of(meaning), if (meaning == Energy.SUN) 0.78f else 0.72f)
    }

    companion object {
        val LIGHT = EnergyPalette(night = false)
        val DARK = EnergyPalette(night = true)

        /** [share] of [colour] and the rest white, channel by channel (the card's `color-mix(in srgb, … , #ffffff)`). */
        fun mixWhite(colour: Int, share: Float): Int {
            // Plain bit arithmetic rather than android.graphics.Color, so the palette is checked in unit tests.
            fun channel(shift: Int) = Math.round(((colour shr shift) and 0xFF) * share + 255 * (1f - share)).coerceIn(0, 255) shl shift
            return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
        }
    }
}
