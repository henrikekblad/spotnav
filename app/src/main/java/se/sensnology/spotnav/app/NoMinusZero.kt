package se.sensnology.spotnav.app

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A figure that is about to be written with [digits] decimals, with a value that rounds to zero there turned into a
 * plain zero, so a screen never says "-0,00 kr" or "-0 %". Real negatives and non-finite values pass unchanged.
 */
internal fun noMinusZero(value: Double, digits: Int): Double {
    if (value.isNaN() || value.isInfinite()) return value
    if (value == 0.0) return 0.0
    return if (BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).signum() == 0) 0.0 else value
}
