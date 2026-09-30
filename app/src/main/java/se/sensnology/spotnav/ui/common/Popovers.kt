package se.sensnology.spotnav.ui.common

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R

/**
 * One popover, described once: what kind of thing is being edited, what it is called, the value the
 * popover renders large, the control itself, any tick labels for the slider's meaningful stops, and
 * one line saying what the value is for.
 */
internal class PopoverSpec(
    val eyebrow: String,
    val title: String,
    val value: TextView,
    val control: View,
    val note: String,
    /**
     * The slider's meaningful stops, or `null` for a popover whose control is not a slider. Each
     * stop names both the value it stands at (in the slider's own units, from its `min`) and the
     * label under it, because the value is what has to line up with the thumb -- see [TickRow].
     */
    val tickAxis: TickAxis? = null
)

/** One tick's value in the slider's own units, and the label drawn under it. */
internal class Tick(val value: Int, val label: String)

/** A slider's range and the stops labelled under it. */
internal class TickAxis(val min: Int, val max: Int, val ticks: List<Tick>)

/**
 * The popover a tappable value row opens, in the shape the design exploration settled:
 * `AlertDialog` with a custom view, the same mechanism `LauncherActivity` and the departure picker
 * already use — no new popup mechanism.
 */
internal fun ViewScope.showValuePopover(
    existing: AlertDialog?,
    spec: PopoverSpec,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null
): AlertDialog {
    val dialog = existing ?: AlertDialog.Builder(context)
        .setView(popoverBody(spec))
        .setPositiveButton(confirmLabel ?: t(android.R.string.ok)) { _, _ -> onConfirm?.invoke() }
        .create()
    dialog.show()
    return dialog
}

internal fun ViewScope.popoverBody(spec: PopoverSpec) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(22), dp(18), dp(22), dp(4))
    addView(TextView(context).apply {
        text = spec.eyebrow
        textSize = 11f
        setTextColor(muted)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
    })
    addView(TextView(context).apply {
        text = spec.title
        textSize = 17f
        setTextColor(dark)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, 0)
    })
    // The value is the popover's focal point, and the same view the control itself keeps up to date
    // as it moves.
    spec.value.textSize = 30f
    spec.value.setTextColor(dark)
    spec.value.gravity = Gravity.START
    addView(spec.value, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) })
    addView(spec.control, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) })
    if (spec.tickAxis != null) {
        addView(tickRow(spec.tickAxis, spec.control))
    }
    addView(TextView(context).apply {
        text = spec.note
        textSize = 13f
        setTextColor(muted)
        setPadding(0, dp(12), 0, 0)
    })
}

/** What a popover's control is built into, when it needs a parent. */
internal fun ViewScope.popoverControl() = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    // A slider's thumb draws its pressed halo outside its own box, and the popover has room above
    // and below the control for it.
    clipChildren = false
}

/**
 * The tick labels under a slider, positioned in the slider's own coordinate system (see
 * [SliderTicks]) instead of spread across the container.
 */
internal fun ViewScope.tickRow(axis: TickAxis, control: View): View {
    val row = TickRow(context, axis, control as? SeekBar)
    axis.ticks.forEach { tick ->
        row.addView(TextView(context).apply {
            text = tick.label
            textSize = 11f
            setTextColor(muted)
        })
    }
    row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    return row
}

/**
 * A row that lays its labels out where the thumb actually travels, read from the `SeekBar` itself
 * after layout: the framework's own rail arithmetic, from the view's width and padding, the thumb
 * drawable's intrinsic width and the view's `thumbOffset` (see SliderTicks.rail).
 */
@SuppressLint("ViewConstructor") // built in code, from one slider and its axis: there is nothing to inflate
internal class TickRow(context: Context, private val axis: TickAxis, private val rail: SeekBar?) :
    ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var height = 0
        for (index in 0 until childCount) {
            // Each label at its own width: the widths are what must not add up into a shift of the
            // ticks they sit under.
            val child = getChildAt(index)
            measureChild(child, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec)
            height = maxOf(height, child.measuredHeight)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val slider = rail
        val geometry = slider?.let {
            SliderTicks.rail(
                viewWidth = it.width,
                paddingLeft = it.paddingLeft,
                paddingRight = it.paddingRight,
                // A theme may give the slider no thumb at all; the framework's own formula then has
                // no thumb to inset for, and neither has this.
                thumbWidth = it.thumb?.intrinsicWidth ?: 0,
                thumbOffset = it.thumbOffset
            )
        }
        if (slider == null || geometry == null || childCount != axis.ticks.size) {
            // No measured rail: rather than guess where the ticks are, this pass places nothing and
            // a later one -- when there is a rail -- places them properly.
            for (index in 0 until childCount) getChildAt(index).layout(0, 0, 0, 0)
            return
        }
        // The slider's own reading direction, not the app's assumption: a `SeekBar` mirrors its
        // thumb in RTL, so progress 0 is on the right.
        val mirrored = slider.layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val center = SliderTicks.centerX(axis.ticks[index].value, axis.min, axis.max, geometry, mirrored)
            val labelLeft = SliderTicks.labelLeft(center, child.measuredWidth.toFloat(), 0f, width.toFloat())
            val start = labelLeft.toInt()
            child.layout(start, 0, start + child.measuredWidth, child.measuredHeight)
        }
    }
}
