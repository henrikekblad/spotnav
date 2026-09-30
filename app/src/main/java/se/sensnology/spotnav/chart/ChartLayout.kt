package se.sensnology.spotnav.chart

import kotlin.math.max
import kotlin.math.min

/**
 * How much of a graph's canvas goes to text, and whether it draws a footer.
 *
 * The widget's pixels stay fixed; the in-app chart has far more raw pixels at the same on-screen size,
 * so the plan card renders denser text and spends the footer's space on the plot.
 */
internal enum class ChartProfile(
    val footer: Boolean,
    /** Caps on header and axis text as a fraction of canvas height; `null` is uncapped. */
    val headerCapHeightFraction: Float?,
    val axisCapHeightFraction: Float?
) {
    WIDGET(footer = true, headerCapHeightFraction = null, axisCapHeightFraction = null),

    PLAN_CARD(footer = false, headerCapHeightFraction = 0.115f, axisCapHeightFraction = 0.082f)
}

internal data class ChartMetrics(
    val scale: Float,
    val pad: Float,
    val headerTextSize: Float,
    val axisTextSize: Float,
    val footerHeight: Float,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float
) {
    val plotHeight: Float get() = bottom - top

    val plotWidth: Float get() = right - left

    /**
     * The x a given minute of the day is drawn at, midnight to midnight across the plot. The drawing,
     * the selection line and the hit test ([minuteOfDayAt], its inverse) all read this one mapping.
     */
    fun xAt(minuteOfDay: Float): Float = left + minuteOfDay / MINUTES_PER_DAY * plotWidth

    /**
     * The minute of the day [x] falls on, or `null` outside the plot's horizontal span. The vertical
     * check is the caller's.
     */
    fun minuteOfDayAt(x: Float): Float? =
        if (plotWidth <= 0f || x < left || x > right) null else (x - left) / plotWidth * MINUTES_PER_DAY

    private companion object {
        const val MINUTES_PER_DAY = 1440f
    }
}

/**
 * The renderer's geometry, Android-free so profile differences are testable. The header's text width
 * is measured by the caller and passed in as a lambda.
 */
internal object ChartLayout {
    fun scaleFor(width: Int, height: Int): Float =
        min(width.coerceIn(180, 1400) / 420f, height.coerceIn(100, 900) / 220f).coerceIn(.72f, 1.45f)

    fun metrics(
        profile: ChartProfile,
        width: Int,
        height: Int,
        scaledDensity: Float,
        hasChargingPlan: Boolean,
        measureHeader: (textSize: Float) -> Float
    ): ChartMetrics {
        val w = width.coerceIn(180, 1400)
        val h = height.coerceIn(100, 900)
        // Geometry scale is independent of the profile: markers, padding and grid follow it.
        val scale = scaleFor(w, h)
        val pad = 14f * scale
        val desiredHeader = cap(19f * scaledDensity, profile.headerCapHeightFraction, h)
        // Size text for readability, shrinking only as a last resort to avoid overlap.
        val requiredWidth = measureHeader(desiredHeader) + pad * 5f
        val headerTextSize = if (requiredWidth > w) {
            max(14f * scaledDensity, desiredHeader * w / requiredWidth)
        } else desiredHeader
        val axisTextSize = cap(14f * scaledDensity, profile.axisCapHeightFraction, h)
        // Reserved only by a profile that draws a footer, and only when there is a plan.
        val footerHeight = if (profile.footer && hasChargingPlan) axisTextSize * 2.0f else 0f
        val top = pad + headerTextSize * 1.65f
        val bottom = h - pad - axisTextSize * 1.35f - footerHeight
        val left = pad + max(axisTextSize * 2.5f, w * .070f)
        val right = w - pad
        return ChartMetrics(scale, pad, headerTextSize, axisTextSize, footerHeight, top, bottom, left, right)
    }

    private fun cap(size: Float, fractionOfHeight: Float?, height: Int): Float =
        if (fractionOfHeight == null) size else min(size, height * fractionOfHeight)
}
