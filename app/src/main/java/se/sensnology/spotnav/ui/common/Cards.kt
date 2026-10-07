package se.sensnology.spotnav.ui.common

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chart.ViewBounds
import kotlin.math.roundToInt

/**
 * The card pattern's own handle: the block, the header row (its end controls), the heading inside
 * it (the marker and the title, or a selector standing in for the title -- see [card]), and the
 * body its rows go in.
 */
internal class Card(
    val header: LinearLayout,
    val heading: LinearLayout,
    val title: TextView,
    val body: LinearLayout
)

/**
 * One card: its header, the heading inside it, the title view a card may replace with a selector,
 * and the body its rows go in.
 */
internal fun ViewScope.card(parent: LinearLayout, title: String, marker: Int, shareRow: Boolean = false): Card {
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // The heading is the top of the header, never the middle of it.
        gravity = Gravity.TOP
    }
    val heading = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val titleView = TextView(context).apply {
        text = title
        textSize = 17f
        setTextColor(dark)
        typeface = Typeface.DEFAULT_BOLD
    }
    heading.addView(ImageView(context).apply {
        setImageResource(marker)
        imageTintList = ColorStateList.valueOf(dark)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
    heading.addView(titleView, weight())
    header.addView(heading, weight())
    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(cardBackground)
            cornerRadius = dp(10).toFloat()
        }
        addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
    // [shareRow] is set when this card sits beside another one: half the width, with a gutter
    // between them, instead of the full width.
    parent.addView(body, if (shareRow) LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply { bottomMargin = dp(14); marginStart = dp(7); marginEnd = dp(7) } else LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(14) })
    return Card(header = header, heading = heading, title = titleView, body = body)
}

/**
 * The adapter a card's header uses when its title gives way to a selector. A row may carry a second,
 * smaller line in the drop-down ([subtitle], read when the drop-down opens); the title shows the
 * first line alone.
 */
internal fun ViewScope.headerSpinnerAdapter(
    items: List<String>,
    isRowEnabled: (Int) -> Boolean = { true },
    subtitle: (Int) -> String? = { null }
) =
    object : ArrayAdapter<String>(context, R.layout.card_title_spinner, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).apply { (this as TextView).setTextColor(dark) }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).apply {
                val row = this as TextView
                row.setTextColor(if (isRowEnabled(position)) dark else muted)
                val second = subtitle(position)
                row.text = if (second.isNullOrEmpty()) items[position] else android.text.SpannableStringBuilder(items[position]).apply {
                    append('\n')
                    val start = length
                    append(second)
                    setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(muted), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                row.isSingleLine = false
                row.maxLines = 2
            }

        override fun isEnabled(position: Int) = isRowEnabled(position)
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

/**
 * An icon-only action: a localized content description, a real 48 dp touch target around a 24 dp
 * glyph, and no reliance on colour to say what it is. The glyph itself is decoration -- the
 * description is the action.
 */
internal fun ViewScope.iconAction(iconResource: Int, description: String, action: () -> Unit) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    contentDescription = description
    isClickable = true
    isFocusable = true
    minimumWidth = dp(48)
    minimumHeight = dp(48)
    setOnClickListener { action() }
    addView(ImageView(context).apply {
        setImageResource(iconResource)
        imageTintList = ColorStateList.valueOf(dark)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(24), dp(24)))
}
/**
 * The same action, drawn in the header it sits in: the same 24 dp glyph, the same description, the
 * same click and focus, but a box of the glyph plus a 4 dp cushion rather than [iconAction]'s 48 dp
 * one.
 */
internal fun ViewScope.compactIconAction(iconResource: Int, description: String, action: () -> Unit) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    contentDescription = description
    isClickable = true
    isFocusable = true
    setPadding(dp(4), dp(4), dp(4), dp(4))
    setOnClickListener { action() }
    addView(ImageView(context).apply {
        setImageResource(iconResource)
        // This glyph opens another screen, so it uses the same accent cue as editable values
        // instead of looking like a read-only value.
        imageTintList = ColorStateList.valueOf(accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(24), dp(24)))
}

/**
 * Give [action] the touch area its own box is too small to have, without giving the layout a 48 dp
 * box to measure: a [TouchDelegate] on [body] whose rectangle is the action's own box grown to
 * [ACTION_TARGET_DP] and held inside the room above [graph].
 */
internal fun ViewScope.expandActionTarget(body: LinearLayout, action: View, graph: View) {
    val box = boundsInParent(action, body)
    // The room this target may use: the card's own top strip, down to where the graph begins.
    val allowed = ViewBounds(0f, 0f, body.width.toFloat(), graph.top.toFloat())
    val target = ActionTarget.hitRect(box, dp(ACTION_TARGET_DP).toFloat(), allowed)
    val rect = Rect(
        target.left.roundToInt(), target.top.roundToInt(),
        target.right.roundToInt(), target.bottom.roundToInt()
    )
    // Handed to the framework, which forwards the touches and lets a screen reader see the enlarged
    // target it is: accessibility draws its own focus rectangle from that.
    setTouchTarget(body, action, rect)
}

/**
 * The touch delegates one view holds: the framework takes one per view, so this one hands each touch
 * to whichever of its own the touch began in. Each target view has one rectangle, replaced when set
 * again (after a new layout).
 */
private class TouchTargets(host: View) : TouchDelegate(Rect(), host) {
    val byTarget = LinkedHashMap<View, TouchDelegate>()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = false
        for (delegate in byTarget.values.toList()) {
            if (delegate.onTouchEvent(event)) handled = true
        }
        return handled
    }
}

/** Let touches inside [rect] (in [host]'s coordinates) go to [target], beside [host]'s other such targets. */
internal fun setTouchTarget(host: View, target: View, rect: Rect) {
    val targets = host.touchDelegate as? TouchTargets ?: TouchTargets(host).also { host.touchDelegate = it }
    targets.byTarget[target] = TouchDelegate(rect, target)
}

/**
 * A view's own box in [ancestor]'s coordinates -- the space a touch delegate's rectangle is
 * measured in, and a place screen coordinates cannot give without dragging the window into
 * arithmetic about one card.
 */
internal fun ViewScope.boundsInParent(view: View, ancestor: View): ViewBounds {
    var left = 0
    var top = 0
    var current: View? = view
    while (current != null && current !== ancestor) {
        left += current.left
        top += current.top
        current = current.parent as? View
    }
    return ViewBounds(left.toFloat(), top.toFloat(), (left + view.width).toFloat(), (top + view.height).toFloat())
}

/**
 * The re-read icon an object card's header carries: the same glyph and the same description on
 * both, because they do the same thing to different things (see [VehicleRefresh] for the vehicle's,
 * and the charger card for the plain `status` poll).
 */
internal fun ViewScope.reReadIcon() = ImageView(context).apply {
    setImageResource(R.drawable.ic_reread)
    contentDescription = t(R.string.card_reread)
    isClickable = true
    setPadding(dp(4), dp(4), dp(4), dp(4))
}

/** The spin on one re-read icon, while that card's values are on their way. */
internal class Spin(private val icon: ImageView) {
    private val turn = ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f).apply {
        duration = 900L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
    }

    init {
        icon.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = stop()
        })
    }

    fun set(spinning: Boolean) {
        if (!spinning) {
            stop()
        } else if (!turn.isRunning) {
            turn.start()
        }
    }

    private fun stop() {
        turn.cancel()
        icon.rotation = 0f
    }
}
