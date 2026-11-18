package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.ui.common.ViewScope
import kotlin.math.abs
import kotlin.math.max

/** One part of a lane: [series] names its colour and legend entry, [value] its size (negative: below zero). */
internal class BarPart(val series: String, val value: Double)

/**
 * One bar: one or two [lanes] side by side, each a stack of parts all above zero or all below it, so a bar can hold a
 * stack above the line and one below it ([slots] and [slotCount] put a lane in a place of its own, default its index).
 * [words] is what the readout says of it and its accessible name, [axis] what stands under it ("" for none).
 */
internal class Bar(
    val key: String,
    val lanes: List<List<BarPart>>,
    val words: String,
    val axis: String,
    val slots: List<Int>? = null,
    val slotCount: Int? = null
)

/** A legend entry: the series' [key], its words and colour; [line] draws its swatch as a line (the sun's step line). */
internal class BarSeries(val key: String, val label: String, val colour: Int, val line: Boolean = false)

/** What a [StackedBarsView] shows. [hidden] series are left out of the bars and the scale; the legend keeps them, struck through. */
internal class BarsSpec(
    /** The plot's name for a screen reader. */
    val label: String,
    val bars: List<Bar>,
    val series: List<BarSeries>,
    val hidden: Set<String> = emptySet(),
    /** The scale's words for a value: the tallest lane's, and the deepest below zero. */
    val scale: (Double) -> String,
    /** The readout's words while no bar is chosen. */
    val hint: String,
    /** The bar chosen when drawn (its key), or none. */
    val selected: String? = null,
    /** A step line over the bars, one value per bar (`null`: none there), drawn as the series [lineSeries]. */
    val line: List<Double?>? = null,
    val lineSeries: String? = null,
    /** Whether the legend toggles parts (needs at least one series); otherwise it only names them (two or more). */
    val toggles: Boolean = true
)

/** The arithmetic of the bars, apart from the views: how tall each part is, and which bar a touch is on. */
internal object BarsLayout {
    class Rect(val bar: Int, val series: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The room the bars' extent needs above and below the line: the tallest lane above zero, the deepest below. */
    class Extent(val top: Double, val bottom: Double) {
        val span: Double get() = if (top + bottom > 0) top + bottom else 1.0

        /** The zero line's share of the height, from the bottom. */
        val zero: Double get() = bottom / span
    }

    fun visible(lane: List<BarPart>, hidden: Set<String>): List<BarPart> = lane.filter { it.series !in hidden && it.value != 0.0 }

    fun sum(lane: List<BarPart>, hidden: Set<String>): Double = visible(lane, hidden).sumOf { it.value }

    fun extent(bars: List<Bar>, hidden: Set<String>, line: List<Double?>? = null): Extent {
        var top = 0.0
        var bottom = 0.0
        for (bar in bars) for (lane in bar.lanes) {
            val sum = sum(lane, hidden)
            top = max(top, sum)
            bottom = max(bottom, -sum)
        }
        line?.forEach { if (it != null) top = max(top, it) }
        return Extent(top, bottom)
    }

    /**
     * The rectangles of every part in a plot of [width] by [height]. A lane's height is its sum's share of the whole
     * span, and each part's height its share of the lane, never the bare value, so a lane always reaches the baseline
     * and a part is never drawn shorter than a pixel.
     */
    fun rects(bars: List<Bar>, hidden: Set<String>, width: Float, height: Float, gap: Float, line: List<Double?>? = null): List<Rect> {
        if (bars.isEmpty()) return emptyList()
        val extent = extent(bars, hidden, line)
        val zeroY = height - (extent.zero * height).toFloat()
        val out = ArrayList<Rect>()
        val barWidth = width / bars.size
        bars.forEachIndexed { index, bar ->
            val places = bar.slotCount ?: bar.lanes.size
            val laneWidth = barWidth / max(1, places)
            bar.lanes.forEachIndexed { laneIndex, lane ->
                val parts = visible(lane, hidden)
                val sum = parts.sumOf { it.value }
                if (parts.isEmpty() || sum == 0.0) return@forEachIndexed
                val at = bar.slots?.getOrNull(laneIndex) ?: laneIndex
                val left = index * barWidth + at * laneWidth + gap / 2
                val right = index * barWidth + (at + 1) * laneWidth - gap / 2
                val laneHeight = max(1f, (abs(sum) / extent.span * height).toFloat())
                val total = parts.sumOf { abs(it.value) }
                // From the baseline outward, the last part outermost (a stack reads bottom up).
                var edge = zeroY
                for (part in parts) {
                    val size = laneHeight * (abs(part.value) / total).toFloat()
                    if (sum > 0) {
                        out += Rect(index, part.series, left, edge - size, right, edge)
                        edge -= size
                    } else {
                        out += Rect(index, part.series, left, edge, right, edge + size)
                        edge += size
                    }
                }
            }
        }
        return out
    }

    /** The bar under [x] in a plot [width] wide with [count] bars; `null` outside. */
    fun indexAt(x: Float, width: Float, count: Int): Int? {
        if (count <= 0 || width <= 0f || x < 0f || x > width) return null
        return ((x / width) * count).toInt().coerceIn(0, count - 1)
    }

    /** The bar a key moves to: left and right by one, Home and End to the ends, Escape to none. */
    fun step(current: Int?, count: Int, move: Move): Int? = when (move) {
        Move.LEFT -> if (current == null) count - 1 else max(0, current - 1)
        Move.RIGHT -> if (current == null) 0 else minOf(count - 1, current + 1)
        Move.HOME -> 0
        Move.END -> count - 1
        Move.CLEAR -> null
    }.takeIf { count > 0 }

    enum class Move { LEFT, RIGHT, HOME, END, CLEAR }
}

/**
 * Touch scrubbing by the finger's x alone, as the card's charts do (`chart-scrub.ts`): a press or a drag shows the
 * bar or hour under the finger, the one it ends on is kept; a tap on the chosen one clears it. The parent never takes
 * the gesture from a sideways drag (a chart is `OwnsSidewaysDrag`: no page swipe begins on it).
 */
internal class ScrubTouch(
    private val host: View,
    private val indexAt: (Float) -> Int?,
    private val show: (Int?) -> Unit,
    private val choose: (Int?) -> Unit,
    private val chosen: () -> Int?
) {
    private var scrubbed: Int? = null
    private var startX = 0f
    private var startY = 0f
    private var sideways = false
    private val slop = android.view.ViewConfiguration.get(host.context).scaledTouchSlop

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                sideways = false
                scrubbed = indexAt(event.x)
                show(scrubbed)
                // Not yet known whether this scrubs or scrolls the page: let the page have a vertical move.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!sideways && abs(event.x - startX) > slop && abs(event.x - startX) > abs(event.y - startY)) {
                    sideways = true
                }
                if (sideways) {
                    host.parent?.requestDisallowInterceptTouchEvent(true)
                    scrubbed = indexAt(event.x) ?: scrubbed
                    show(scrubbed)
                } else if (abs(event.y - startY) > slop) {
                    // A vertical move is the page's scroll: the hover goes back to what was chosen.
                    show(chosen())
                    return false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = scrubbed
                val was = chosen()
                if (!sideways && index != null && index == was) choose(null) else choose(index ?: was)
                if (!sideways) host.performClick()
                scrubbed = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                show(chosen())
                scrubbed = null
                return true
            }
        }
        return false
    }
}

/** A row of views that wraps to the next line when the width is used. Only for a legend. */
internal class WrapRow(context: android.content.Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val room = MeasureSpec.getSize(widthSpec)
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + child.measuredWidth > room) {
                x = 0
                y += line + vGap
                line = 0
            }
            x += child.measuredWidth + hGap
            line = max(line, child.measuredHeight)
        }
        setMeasuredDimension(room, y + line)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val room = r - l
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > room) {
                x = 0
                y += line + vGap
                line = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + hGap
            line = max(line, child.measuredHeight)
        }
    }
}

/** The legend under a chart: a swatch and name per series, a toggle where [fill] is asked to, struck through when hidden. */
internal object LegendRow {
    fun fill(scope: ViewScope, legend: WrapRow, entries: List<BarSeries>, hiddenSet: Set<String>, toggles: Boolean, onHiddenChange: (Set<String>) -> Unit) {
        legend.removeAllViews()
        val all = entries.filter { it.label.isNotEmpty() }
        if (all.isEmpty() || (!toggles && all.size < 2)) return
        val density = scope.resources.displayMetrics.density
        for (series in all) {
            val hidden = series.key in hiddenSet
            legend.addView(TextView(scope.context).apply {
                text = series.label
                textSize = 12.5f
                setTextColor(if (hidden) scope.muted else scope.dark)
                paintFlags = if (hidden) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                alpha = if (hidden) 0.6f else 1f
                compoundDrawablePadding = scope.dp(6)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = scope.dp(if (toggles) 36 else 22)
                setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(scope, series, density), null, null, null)
                if (toggles) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val next = if (hidden) hiddenSet - series.key else hiddenSet + series.key
                        onHiddenChange(next)
                    }
                    // A toggle for a screen reader: pressed means shown.
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = android.widget.ToggleButton::class.java.name
                            info.isCheckable = true
                            info.isChecked = !hidden
                        }
                    }
                }
            })
        }
    }

    private fun swatch(scope: ViewScope, series: BarSeries, density: Float): android.graphics.drawable.Drawable {
        val w = scope.dp(15)
        val h = scope.dp(11)
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                paint.color = series.colour
                if (series.line) {
                    paint.strokeWidth = 2f * density
                    canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, paint)
                } else {
                    canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 2f * density, 2f * density, paint)
                }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth() = w
            override fun getIntrinsicHeight() = h
        }
    }
}

/**
 * The stacked bars the house and heat pages share (the card's `barChart`, `frontend/src/house-period-view.ts`): a plot
 * of bars side by side, each one or two lanes stacked from parts, above and below a zero line, with the tallest
 * figure as the scale; under it the legend (a toggle per series, struck through when hidden) and the readout of the
 * bar under the finger. Touch scrubs by x, the arrow keys, Home, End and Escape move between bars, and a screen reader
 * gets the plot as one control with "next bar" and "previous bar" actions that say the bar in the live readout.
 *
 * Public API for the tabs that use it:
 * - [show] draws a [BarsSpec] (bars with their words, the legend's series, the hidden ones, the scale's words);
 * - [onSelect] hears the bar chosen (its key, `null` for none) so the page can remember it;
 * - [onHiddenChange] hears the legend's toggle with the new set of hidden series: the page remembers it (through
 *   `SitePreferences.setHidden`) and calls [show] again with it.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility") // built in code; the plot's touch is a scrub, not a click
internal class StackedBarsView(
    private val scope: ViewScope,
    private val colours: SiteColours,
    private val plotHeightDp: Int = 150
) : LinearLayout(scope.context), OwnsSidewaysDrag {
    var onSelect: (String?) -> Unit = {}
    var onHiddenChange: (Set<String>) -> Unit = {}

    private var spec: BarsSpec? = null
    private var chosen: Int? = null
    private var shownIndex: Int? = null
    private val density = resources.displayMetrics.density
    private val legend = WrapRow(context, scope.dp(14), scope.dp(2))
    private val readout = TextView(context).apply {
        textSize = 13.5f
        setTextColor(scope.muted)
        minLines = 2
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val plot = Plot()

    init {
        orientation = VERTICAL
        addView(plot, LayoutParams(LayoutParams.MATCH_PARENT, scope.dp(plotHeightDp) + scope.dp(AXIS_DP)))
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(4) })
        addView(readout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(2) })
    }

    /** The bar chosen, by its key. */
    val selectedKey: String? get() = chosen?.let { spec?.bars?.getOrNull(it)?.key }

    fun show(next: BarsSpec) {
        spec = next
        chosen = next.selected?.let { key -> next.bars.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
        shownIndex = chosen
        plot.contentDescription = next.label
        drawLegend(next)
        say(chosen)
        plot.invalidate()
    }

    private fun say(index: Int?) {
        val current = spec ?: return
        readout.text = index?.let { current.bars.getOrNull(it)?.words } ?: current.hint
        shownIndex = index
        plot.invalidate()
    }

    private fun choose(index: Int?) {
        val current = spec ?: return
        chosen = index
        say(index)
        onSelect(index?.let { current.bars.getOrNull(it)?.key })
    }

    private fun drawLegend(current: BarsSpec) =
        LegendRow.fill(scope, legend, current.series, current.hidden, current.toggles, onHiddenChange)

    private fun colourOf(key: String): Int = spec?.series?.firstOrNull { it.key == key }?.colour ?: colours.muted

    private inner class Plot : View(scope.context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
        private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density }
        private val rect = RectF()
        private val touch = ScrubTouch(this, ::barAt, { say(it) }, { choose(it) }, { chosen })

        init {
            isFocusable = true
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.ListView"
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT, "›"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS, "‹"))
                    info.stateDescription = chosen?.let { spec?.bars?.getOrNull(it)?.words }
                }

                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    val count = spec?.bars?.size ?: return false
                    return when (action) {
                        ACTION_NEXT -> { choose(BarsLayout.step(chosen, count, BarsLayout.Move.RIGHT)); true }
                        ACTION_PREVIOUS -> { choose(BarsLayout.step(chosen, count, BarsLayout.Move.LEFT)); true }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            }
        }

        private fun plotHeight() = height - scope.dp(AXIS_DP).toFloat()

        private fun barAt(x: Float): Int? = BarsLayout.indexAt(x, width.toFloat(), spec?.bars?.size ?: 0)

        override fun onTouchEvent(event: MotionEvent): Boolean = touch.onTouch(event)

        override fun performClick(): Boolean = super.performClick()

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val count = spec?.bars?.size ?: 0
            val move = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> BarsLayout.Move.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> BarsLayout.Move.RIGHT
                KeyEvent.KEYCODE_MOVE_HOME -> BarsLayout.Move.HOME
                KeyEvent.KEYCODE_MOVE_END -> BarsLayout.Move.END
                KeyEvent.KEYCODE_ESCAPE -> BarsLayout.Move.CLEAR
                else -> return super.onKeyDown(keyCode, event)
            }
            choose(BarsLayout.step(chosen, count, move))
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val current = spec ?: return
            val w = width.toFloat()
            val h = plotHeight()
            val gap = (if (current.bars.size > 40) 1f else 2f) * density
            // The chosen bar's band, behind everything.
            shownIndex?.let {
                val slot = w / current.bars.size
                fill.color = SiteColours.mix(colours.muted, colours.card, 0.18f)
                canvas.drawRect(it * slot, 0f, (it + 1) * slot, h, fill)
            }
            val extent = BarsLayout.extent(current.bars, current.hidden, current.line)
            for (r in BarsLayout.rects(current.bars, current.hidden, w, h, gap, current.line)) {
                fill.color = colourOf(r.series)
                rect.set(r.left, r.top, r.right, r.bottom)
                canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)
            }
            val zeroY = h - (extent.zero * h).toFloat()
            stroke.color = colours.muted
            if (extent.bottom > 0) canvas.drawLine(0f, zeroY, w, zeroY, stroke)
            // The step line over the bars (the sun).
            current.line?.let { values ->
                val key = current.lineSeries
                if (key == null || key !in current.hidden) {
                    stroke.color = key?.let(::colourOf) ?: colours.muted
                    stroke.strokeWidth = 2f * density
                    val slot = w / current.bars.size
                    var open = false
                    var path = android.graphics.Path()
                    values.forEachIndexed { i, v ->
                        if (v == null) {
                            open = false
                            return@forEachIndexed
                        }
                        val y = h - ((v / extent.span + extent.zero) * h).toFloat()
                        if (!open) { path.moveTo(i * slot, y); open = true } else path.lineTo(i * slot, y)
                        path.lineTo((i + 1) * slot, y)
                    }
                    canvas.drawPath(path, stroke)
                    stroke.strokeWidth = 1f * density
                }
            }
            stroke.color = colours.wire
            canvas.drawLine(0f, h, w, h, stroke)
            // The scale: the tallest figure at the top left, the deepest where bars go below zero.
            drawScale(canvas, current.scale(extent.top), 0f, 0f)
            if (extent.bottom > 0) drawScale(canvas, current.scale(-extent.bottom), 0f, h - 14f * density)
            // The axis under the bars.
            label.color = colours.muted
            label.textAlign = Paint.Align.CENTER
            val slot = w / current.bars.size
            current.bars.forEachIndexed { i, bar ->
                if (bar.axis.isNotEmpty()) canvas.drawText(bar.axis, (i + 0.5f) * slot, h + 13f * density, label)
            }
        }

        private fun drawScale(canvas: Canvas, text: String, x: Float, y: Float) {
            label.color = colours.muted
            label.textAlign = Paint.Align.LEFT
            val tw = label.measureText(text)
            fill.color = colours.card
            fill.alpha = 220
            canvas.drawRoundRect(x, y, x + tw + 6f * density, y + 14f * density, 3f * density, 3f * density, fill)
            fill.alpha = 255
            canvas.drawText(text, x + 3f * density, y + 11f * density, label)
        }
    }

    private companion object {
        const val AXIS_DP = 18
        const val ACTION_NEXT = 0x7E000001
        const val ACTION_PREVIOUS = 0x7E000002
    }
}
