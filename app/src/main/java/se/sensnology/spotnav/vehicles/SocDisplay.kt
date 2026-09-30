package se.sensnology.spotnav.vehicles

import kotlin.math.roundToInt

/** How a state of charge is displayed: a whole percent, rounded to nearest. */
internal object SocDisplay {
    /**
     * [socPercent] as the whole percent to show. Nearest whole percent, not truncated: 82.0 -> 82,
     * 82.4 -> 82, 82.5 -> 83, 82.6 -> 83.
     */
    fun wholePercent(socPercent: Double): Int =
        if (socPercent.isFinite()) socPercent.roundToInt().coerceAtLeast(0) else 0
}
