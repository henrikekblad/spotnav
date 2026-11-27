package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.ui.common.ViewScope
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What a [HeatDayChart] shows: the day, its figures in words, the legend, and what is chosen. */
internal class HeatDaySpec(
    val chart: DayChart,
    val label: String,
    val hint: String,
    /** The readout of the step that starts at a time (ms). */
    val words: (Long) -> String,
    val legend: List<DayLegend>,
    /** The comfort band's edges in words, written inside the band at the right. */
    val highEdge: String,
    val lowEdge: String,
    val selectedMs: Long? = null
)

/** The colours of the plan chart, the card's (`--spotnav-heat-*-colour`); the rooms in the heat's and the car in its own from the energy palette. */
internal object HeatChartColours {
    const val LOWER = 0xFF2F80C8.toInt()
    const val RAISE = 0xFFD9822B.toInt()
    const val RELAY = 0xFF14808F.toInt()
    const val WATER = 0xFFC62828.toInt()
    const val COMFORT = 0xFF43A047.toInt()

    fun withAlpha(colour: Int, share: Float): Int = (colour and 0x00FFFFFF) or ((share * 255).toInt() shl 24)

    /** The swatch of a legend entry: its colour (with its share of opaque) and whether it is a thin line or a droplet. */
    fun of(kind: String, text: Int, colours: SiteColours): Triple<Int, String, Int> = when (kind) {
        "indoor_mean" -> Triple(colours.of(Tone.HEAT), "line", 3)
        "indoor_coldest" -> Triple(withAlpha(colours.of(Tone.HEAT), 0.55f), "line", 2)
        "setpoint" -> Triple(withAlpha(text, 0.75f), "dash", 2)
        "comfort" -> Triple(withAlpha(COMFORT, 0.18f), "box", 0)
        "executed" -> Triple(withAlpha(LOWER, 0.38f), "box", 0)
        "planned" -> Triple(withAlpha(LOWER, 0.18f), "box", 0)
        "raised" -> Triple(withAlpha(RAISE, 0.40f), "box", 0)
        "raise_planned" -> Triple(withAlpha(RAISE, 0.18f), "box", 0)
        "relay" -> Triple(withAlpha(RELAY, 0.34f), "box", 0)
        "relay_expected" -> Triple(withAlpha(RELAY, 0.18f), "box", 0)
        "hot_water" -> Triple(WATER, "drop", 0)
        else -> Triple(colours.of(Tone.CAR), "line", 4)
    }
}

/**
 * The Plan box's day chart (the card's `heat-day-chart.ts`): the measured rooms up to now, the comfort band, SpotNav's
 * setpoint as a dashed step, the hours SpotNav lowered (solid) and plans to lower (light), the relay as anyone else
 * closed it, hot water as red drops on the time axis (a planned boost a hollow drop with a thin bar, a peak a tick), the car's charging as a thin bar under it. A touch scrubs by x
 * alone (a sideways drag never moves the page), the arrow keys move a step at a time; the figures of the step are said
 * in words under the chart, with the legend under them.
 */
@SuppressLint("ViewConstructor")
internal class HeatDayChart(
    private val scope: ViewScope,
    private val colours: SiteColours
) : LinearLayout(scope.context), OwnsSidewaysDrag {
    /** The step chosen (its start, ms; `null` for none), so the page can keep it across a redraw. */
    var onSelect: (Long?) -> Unit = {}

    private var spec: HeatDaySpec? = null
    private var chosen: Int? = null
    private val legend = WrapRow(context, scope.dp(14), scope.dp(2))
    private val readout = TextView(context).apply {
        textSize = 13.5f
        setTextColor(scope.muted)
        minLines = 2
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val plot = HeatDayPlotView(scope, colours, { say(it) }, { choose(it) }, { chosen })

    init {
        orientation = VERTICAL
        addView(plot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(readout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(4) })
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(2) })
    }

    fun show(next: HeatDaySpec) {
        spec = next
        chosen = next.selectedMs?.let { HeatPlanModel.stepIndex(next.chart, it) }
        plot.spec = next
        plot.contentDescription = next.label
        fillLegend(next)
        say(chosen)
    }

    private fun fillLegend(next: HeatDaySpec) {
        legend.removeAllViews()
        for (entry in next.legend) {
            legend.addView(TextView(context).apply {
                text = entry.label
                textSize = 12.5f
                setTextColor(scope.muted)
                compoundDrawablePadding = scope.dp(6)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = scope.dp(22)
                setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(entry.kind), null, null, null)
            })
        }
    }

    private fun swatch(kind: String): android.graphics.drawable.Drawable {
        val (colour, shape, thickness) = HeatChartColours.of(kind, colours.text, colours)
        val w = scope.dp(15)
        val h = scope.dp(11)
        val density = scope.resources.displayMetrics.density
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                paint.color = colour
                when (shape) {
                    "line" -> canvas.drawRect(0f, h / 2f - thickness * density / 2, w.toFloat(), h / 2f + thickness * density / 2, paint)
                    "dash" -> {
                        canvas.drawRect(0f, h / 2f - density, 4f * density, h / 2f + density, paint)
                        canvas.drawRect(7f * density, h / 2f - density, w.toFloat(), h / 2f + density, paint)
                    }
                    "drop" -> canvas.drawCircle(w / 2f, h / 2f, h / 2f - density, paint)
                    else -> canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 2f * density, 2f * density, paint)
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

    private fun say(index: Int?) {
        val current = spec ?: return
        val count = HeatPlanModel.stepCount(current.chart)
        readout.text = if (index == null || index !in 0 until count) current.hint else current.words(current.chart.startMs + index * current.chart.stepMs)
        plot.shown = index?.takeIf { it in 0 until count }
    }

    private fun choose(index: Int?) {
        val current = spec ?: return
        chosen = index
        say(index)
        onSelect(index?.let { current.chart.startMs + it * current.chart.stepMs })
    }
}

/** The plot itself, drawn on a canvas in the card's own geometry (a 140 dp picture); made by [HeatDayChart]. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class HeatDayPlotView(
    private val scope: ViewScope,
    private val colours: SiteColours,
    show: (Int?) -> Unit,
    choose: (Int?) -> Unit,
    chosen: () -> Int?
) : View(scope.context) {
    var spec: HeatDaySpec? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The step the finger or a key is on: its crosshair is drawn. */
    var shown: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = scope.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f * this@HeatDayPlotView.density }
    private val rect = RectF()
    private val touch = ScrubTouch(this, ::stepAt, show, choose, chosen)
    private val select = choose
    private val current = chosen

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT, "›"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS, "‹"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                val count = spec?.let { HeatPlanModel.stepCount(it.chart) } ?: return false
                return when (action) {
                    ACTION_NEXT -> { select(BarsLayout.step(current(), count, BarsLayout.Move.RIGHT)); true }
                    ACTION_PREVIOUS -> { select(BarsLayout.step(current(), count, BarsLayout.Move.LEFT)); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (HEIGHT * density).toInt())
    }

    private fun stepAt(x: Float): Int? {
        val chart = spec?.chart ?: return null
        val count = HeatPlanModel.stepCount(chart)
        return BarsLayout.indexAt(x, width.toFloat(), count)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = touch.onTouch(event)

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val chart = spec?.chart ?: return super.onKeyDown(keyCode, event)
        val count = HeatPlanModel.stepCount(chart)
        val perHour = max(1, (HeatPlanModel.HOUR_MS / chart.stepMs).toInt())
        val base = current() ?: chart.nowMs?.takeIf { it in chart.startMs until chart.endMs }?.let { HeatPlanModel.stepIndex(chart, it) }
        fun by(steps: Int): Int = min(count - 1, max(0, (base ?: if (steps > 0) -1 else count) + steps))
        val next: Int? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> by(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> by(-1)
            KeyEvent.KEYCODE_PAGE_DOWN -> by(perHour)
            KeyEvent.KEYCODE_PAGE_UP -> by(-perHour)
            KeyEvent.KEYCODE_MOVE_HOME -> 0
            KeyEvent.KEYCODE_MOVE_END -> count - 1
            KeyEvent.KEYCODE_ESCAPE -> null
            else -> return super.onKeyDown(keyCode, event)
        }
        select(next)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val chart = s.chart
        val w = width.toFloat()
        val d = density
        val top = TOP * d
        val plotBottom = PLOT_BOTTOM * d
        val span = (chart.endMs - chart.startMs).toFloat()
        val x = { ms: Long -> (min(chart.endMs, max(chart.startMs, ms)) - chart.startMs) / span * w }
        val (low, high) = HeatPlanModel.temperatureRange(chart)
        val y = { c: Double -> top + ((high - c) / (high - low)).toFloat() * (plotBottom - top) }

        // The comfort band, shaded across the day.
        fill.color = HeatChartColours.withAlpha(HeatChartColours.COMFORT, 0.18f)
        canvas.drawRect(0f, y(chart.highC), w, max(y(chart.highC) + d, y(chart.lowC)), fill)

        // What lowered the heat, hour by hour: the plan light over the whole hour, what SpotNav did solid over its minutes.
        for (hour in chart.sorted) {
            val lowering = HeatPlanModel.lowering(hour)
            val from = x(hour.startMs)
            val whole = x(hour.startMs + HeatPlanModel.HOUR_MS) - from
            fun column(colour: Int, alpha: Float, share: Double) {
                fill.color = HeatChartColours.withAlpha(colour, alpha)
                canvas.drawRect(from, top, from + max(d, whole * share.toFloat()), plotBottom, fill)
            }
            if (lowering.planned) column(HeatChartColours.LOWER, 0.18f, 1.0)
            if (lowering.executed > 0) column(HeatChartColours.LOWER, 0.38f, lowering.executed / 60)
            else if (lowering.relay > 0) {
                if (lowering.relayExpected) column(HeatChartColours.RELAY, 0.18f, lowering.relay / 60) else column(HeatChartColours.RELAY, 0.34f, lowering.relay / 60)
            }
            if (lowering.raisePlanned) column(HeatChartColours.RAISE, 0.18f, 1.0)
            if (lowering.raised > 0) column(HeatChartColours.RAISE, 0.40f, lowering.raised / 60)
        }

        // The setpoint: a dashed step over the whole day.
        if (chart.sorted.isNotEmpty()) {
            val path = Path()
            chart.sorted.forEachIndexed { i, hour ->
                val from = x(hour.startMs)
                val next = if (i + 1 < chart.sorted.size) chart.sorted[i + 1].startMs else hour.startMs + HeatPlanModel.HOUR_MS
                val to = x(min(next, chart.endMs))
                val level = y(hour.targetC)
                if (i == 0) path.moveTo(from, level) else path.lineTo(from, level)
                path.lineTo(to, level)
            }
            line.color = HeatChartColours.withAlpha(colours.text, 0.75f)
            line.strokeWidth = 1.25f * d
            line.pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
            canvas.drawPath(path, line)
            line.pathEffect = null
        }

        // The measured rooms up to now: a gap in the readings breaks the line.
        fun room(value: (DayChart.Point) -> Double, thick: Float, alpha: Float) {
            val path = Path()
            var previous: Long? = null
            for (point in chart.points) {
                val px = x(point.startMs + chart.stepMs / 2)
                val py = y(value(point))
                if (previous != null && point.startMs - previous <= chart.stepMs) path.lineTo(px, py) else path.moveTo(px, py)
                previous = point.startMs
            }
            line.color = HeatChartColours.withAlpha(colours.of(Tone.HEAT), alpha)
            line.strokeWidth = thick * d
            canvas.drawPath(path, line)
        }
        if (chart.withColdest) room({ it.coldestC }, 1.25f, 0.55f)
        if (chart.points.isNotEmpty()) room({ it.meanC }, 2f, 1f)

        // The band's edges, written inside it at the right.
        label.color = colours.muted
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText(s.highEdge, w - 2 * d, y(chart.highC) + 11f * d, label)
        canvas.drawText(s.lowEdge, w - 2 * d, y(chart.lowC) - 2f * d, label)

        // The time axis, the car's charging under it and the hot water standing on it.
        line.color = colours.wire
        line.strokeWidth = 1f * d
        canvas.drawLine(0f, plotBottom, w, plotBottom, line)
        fill.color = colours.of(Tone.CAR)
        for (run in HeatPlanModel.carRuns(chart)) {
            val from = x(run.startMs)
            rect.set(from, CAR_Y * d, max(from + d, x(run.endMs)), (CAR_Y + CAR_HEIGHT) * d)
            canvas.drawRoundRect(rect, CAR_HEIGHT * d / 2, CAR_HEIGHT * d / 2, fill)
        }
        fill.color = HeatChartColours.WATER
        for (span in chart.water) canvas.drawPath(droplet(x((span.startMs + span.endMs) / 2), plotBottom, d), fill)
        // The boosts planned: a thin bar over the run on the axis, a hollow droplet at its start.
        for (span in chart.planned) {
            if (span.endMs <= chart.startMs || span.startMs >= chart.endMs) continue
            fill.color = HeatChartColours.withAlpha(HeatChartColours.WATER, 0.55f)
            val from = x(span.startMs)
            canvas.drawRect(from, plotBottom - 2 * d, max(from + d, x(span.endMs)), plotBottom, fill)
            val drop = droplet(from, plotBottom, d)
            fill.color = colours.card
            canvas.drawPath(drop, fill)
            line.color = HeatChartColours.WATER
            line.strokeWidth = 1.5f * d
            canvas.drawPath(drop, line)
        }
        // The peaks: a small tick under the axis.
        line.color = HeatChartColours.WATER
        line.strokeWidth = 1.5f * d
        for (peak in chart.peaks) {
            if (peak.ms < chart.startMs || peak.ms >= chart.endMs) continue
            canvas.drawLine(x(peak.ms), plotBottom, x(peak.ms), plotBottom + 4 * d, line)
        }
        label.textAlign = Paint.Align.LEFT
        label.color = colours.muted
        var ms = chart.startMs
        while (ms < chart.endMs) {
            val hour = Instant.ofEpochMilli(ms).atZone(chart.zone).hour
            if (hour % 6 == 0) canvas.drawText("%02d".format(hour), x(ms) + d, LABEL_Y * d, label)
            ms += HeatPlanModel.HOUR_MS
        }
        chart.nowMs?.takeIf { it >= chart.startMs && it < chart.endMs }?.let {
            line.color = colours.text
            line.strokeWidth = 1f * d
            line.alpha = 140
            canvas.drawLine(x(it), (TOP - 4) * d, x(it), (CAR_Y + CAR_HEIGHT) * d, line)
        }

        // The crosshair: a line at the chosen step and a dot on each room line there.
        shown?.let { index ->
            val at = chart.startMs + index * chart.stepMs
            val middle = x(at + chart.stepMs / 2)
            line.color = colours.text
            line.alpha = 200
            line.strokeWidth = 1f * d
            canvas.drawLine(middle, (TOP - 4) * d, middle, (CAR_Y + CAR_HEIGHT) * d, line)
            chart.points.firstOrNull { it.startMs == at }?.let { point ->
                fill.color = colours.of(Tone.HEAT)
                canvas.drawCircle(middle, y(point.meanC), 4f * d, fill)
                if (chart.withColdest && abs(point.coldestC - point.meanC) >= DayChart.DIFFERS_C) {
                    fill.color = HeatChartColours.withAlpha(colours.of(Tone.HEAT), 0.7f)
                    canvas.drawCircle(middle, y(point.coldestC), 3f * d, fill)
                }
            }
        }
    }

    /** A water drop standing on the axis at [x], its tip up. */
    private fun droplet(x: Float, plotBottom: Float, d: Float): Path {
        val base = plotBottom - d
        return Path().apply {
            moveTo(x, base - 10 * d)
            cubicTo(x + 1.5f * d, base - 7 * d, x + 4 * d, base - 5 * d, x + 4 * d, base - 3.5f * d)
            arcTo(RectF(x - 4 * d, base - 7.5f * d, x + 4 * d, base + 0.5f * d), 0f, 180f, false)
            cubicTo(x - 4 * d, base - 5 * d, x - 1.5f * d, base - 7 * d, x, base - 10 * d)
            close()
        }
    }

    private companion object {
        const val HEIGHT = 140f
        const val TOP = 8f
        const val PLOT_BOTTOM = 114f
        const val CAR_Y = 118f
        const val CAR_HEIGHT = 4f
        const val LABEL_Y = 136f
        const val ACTION_NEXT = 0x7E000003
        const val ACTION_PREVIOUS = 0x7E000004
    }
}
