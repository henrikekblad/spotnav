package se.sensnology.spotnav.planning

/** One fiscal component, with its two meanings kept apart. */
data class FiscalInput(
    val enabled: Boolean,
    val overrideValue: Double?,
    val effectiveValue: Double?
) {
    init {
        listOf(overrideValue, effectiveValue).forEach { figure ->
            require(figure == null || (figure.isFinite() && figure >= 0.0)) {
                "a fiscal figure must be finite and not negative"
            }
        }
    }

    /**
     * What this component adds to a price, in minor units — its effective figure, or nothing when
     * it is disabled or has none.
     */
    val addend: Double get() = if (enabled) effectiveValue ?: 0.0 else 0.0

    /**
     * What this component multiplies a price by — VAT's own part of the formula, and `1.0` (no
     * change) when it is disabled or has no effective rate.
     */
    val multiplier: Double get() = if (enabled) 1.0 + (effectiveValue ?: 0.0) / 100.0 else 1.0

    companion object {
        val OFF = FiscalInput(enabled = false, overrideValue = null, effectiveValue = null)

        /**
         * An enabled component whose figure came from the area's catalogue rather than from the
         * user: the value the arithmetic uses, and no override.
         */
        fun suggested(effectiveValue: Double?): FiscalInput =
            FiscalInput(enabled = effectiveValue != null, overrideValue = null, effectiveValue = effectiveValue)
    }
}

/** A stored or reported figure as a usable fiscal number, or absent when it is not one. */
internal fun fiscalFigure(value: Double): Double? = value.takeIf { it.isFinite() && it >= 0.0 }

/** The one fiscal formula, in the order the tax rules require. */
internal object FiscalArithmetic {
    fun apply(localMajorPerKwh: Double, vat: FiscalInput, tax: FiscalInput, transfer: FiscalInput): Double {
        var minorUnits = localMajorPerKwh * 100.0
        minorUnits += tax.addend
        minorUnits += transfer.addend
        minorUnits *= vat.multiplier
        return minorUnits
    }
}
