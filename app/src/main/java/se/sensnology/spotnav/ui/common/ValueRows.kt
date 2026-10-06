package se.sensnology.spotnav.ui.common

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The tappable value row pattern: a label and a readout that together are one tap target. */
internal fun ViewScope.valueRow(parent: LinearLayout, label: String, value: TextView, onTap: (() -> Unit)? = null): ValueRow {
    val labelView = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(muted)
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        addView(labelView, weight())
        addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // A value reads right-aligned, exactly like `resultRow`'s.
        value.gravity = Gravity.END
    }
    parent.addView(row)
    return ValueRow(row, value, palette, onTap, labelView).apply { show(ValueCue.of(editable = onTap != null)) }
}

internal class ValueRow(
    val view: LinearLayout,
    val value: TextView,
    private val palette: Palette,
    private var onTap: (() -> Unit)? = null,
    /** The row's label, for a row whose wording changes with what the card learns. */
    val label: TextView? = null
) {
    private var cue = ValueCue.READ_ONLY
    private var attention = false

    /** This row opens [action], from now on. Attached where the screen is ready to own it. */
    fun tap(action: () -> Unit) {
        onTap = action
        show(cue, attention)
    }

    /**
     * That warning is about the *value*, not about actionability: it replaces the accent colour
     * because the value is the thing to look at, and the row stays as tappable as its cue says (its
     * popover is where the disagreement is settled).
     */
    fun show(cue: ValueCue, attention: Boolean = false) {
        this.cue = cue
        this.attention = attention
        val opens = cue.actionable && onTap != null
        view.isClickable = opens
        view.isFocusable = opens
        val action = onTap
        view.setOnClickListener(if (opens && action != null) View.OnClickListener { action() } else null)
        value.setTextColor(if (attention) ATTENTION_COLOUR else palette.valueColour(cue))
    }
}

/**
 * The colour one [ValueCue] reads in: the screen's whole colour vocabulary for a value, in one
 * expression.
 */
internal fun Palette.valueColour(cue: ValueCue) = when (cue) {
    ValueCue.EDITABLE -> accent
    ValueCue.READ_ONLY -> dark
    ValueCue.UNAVAILABLE -> muted
}

/**
 * One action cell of the charger card, drawn like the Home Assistant card's: a small caption on top
 * stating the state, and below it an icon and the action.
 */
internal class ControlCell(val view: LinearLayout, private val caption: TextView, private val icon: ImageView, private val action: TextView) {
    /**
     * [help], when given, says what the action does: read out after [description], and shown on a
     * long press. A cell not [enabled] is shown dimmed and cannot be tapped.
     */
    fun show(caption: String, icon: Int, action: String, description: String, help: String? = null, enabled: Boolean = true) {
        this.caption.text = caption
        this.icon.setImageResource(icon)
        this.action.text = action
        view.contentDescription = if (help == null) description else "$description. $help"
        view.tooltipText = help
        view.isEnabled = enabled
        view.isClickable = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        view.visibility = View.VISIBLE
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
    }
}

internal fun ViewScope.controlCell(): ControlCell {
    val captionView = TextView(context).apply {
        textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val iconView = ImageView(context).apply {
        imageTintList = ColorStateList.valueOf(accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val actionView = TextView(context).apply {
        textSize = 16f; setTextColor(dark); typeface = Typeface.DEFAULT_BOLD
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val line = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(iconView, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
        addView(actionView)
    }
    val cell = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(10))
        minimumHeight = dp(56)
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        val shape = GradientDrawable().apply {
            setColor(appBackground)
            setStroke(dp(1), (muted and 0x00FFFFFF) or 0x66000000)
            cornerRadius = dp(8).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), shape, null)
        addView(captionView)
        addView(line)
    }
    return ControlCell(cell, captionView, iconView, actionView)
}
