package se.sensnology.spotnav.ui.charging

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import se.sensnology.spotnav.ui.common.ATTENTION_COLOUR
import se.sensnology.spotnav.ui.common.SHADING_ALPHA
import se.sensnology.spotnav.ui.common.ViewScope
import java.util.Locale

/** One quiet line of a paired charger's words around the target slider; gone until it has something to say. */
internal fun ViewScope.pairedLine() = TextView(context).apply {
    textSize = 12f
    setTextColor(muted)
    visibility = View.GONE
    setPadding(0, dp(2), 0, dp(2))
}

/** A percent as the card writes it: whole when it is whole ("80 %"), else one decimal, in the reader's own number format. */
internal object PercentFormat {
    fun text(value: Double, locale: java.util.Locale): String {
        val format = java.text.NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
        }
        // A no-break space: "80 %" never breaks between the figure and its sign at the end of a line.
        return "${format.format(value)}\u00A0%"
    }
}

/** [PercentFormat] in the screen's own number format. */
@Suppress("UnusedReceiverParameter")
internal fun ViewScope.percentText(value: Double, locale: java.util.Locale): String = PercentFormat.text(value, locale)

internal fun ViewScope.bandLabel() = TextView(context).apply {
    textSize = 12f
    setTextColor(muted)
    isSingleLine = true
    // Exactly one line, including when the text is empty (the energy control's reserved row): the
    // line height is what both drivers' bottom row is measured to.
    minLines = 1
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/**
 * What the target slider shades its two unavailable ends with: the palette's own [muted], partly
 * transparent so the track's groove still shows through under it. One colour for both themes, and
 * the app's existing word for "inactive" -- no second colour pair to keep in step.
 */
internal fun ViewScope.mutedShadingColour() = (muted and 0x00FFFFFF) or (SHADING_ALPHA shl 24)

/** The paired-offline notice: the page's own prominent line for a record that cannot be checked. */
internal fun ViewScope.pairedOfflineNotice(): TextView = TextView(context).apply {
    textSize = 14f
    setTextColor(ATTENTION_COLOUR)
    typeface = Typeface.DEFAULT_BOLD
    setPadding(dp(12), dp(10), dp(12), dp(10))
    background = GradientDrawable().apply {
        setColor(0x1AD47A19)
        setStroke(dp(1), ATTENTION_COLOUR)
        cornerRadius = dp(8).toFloat()
    }
    visibility = View.GONE
}
