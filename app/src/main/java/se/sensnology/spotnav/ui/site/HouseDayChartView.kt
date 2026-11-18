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
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ui.common.ViewScope
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** What a [HouseDayChart] shows: a day's hours in one of the two views, and the words for them. */
internal class DaySpec(
    val hours: List<House.Hour>,
    val zone: ZoneId,
    /** The moment "now" stands at; for a past day an instant after its last hour (no now line). */
    val nowMs: Long,
    val view: ChartView,
    val hidden: Set<String>,
    /** The legend's series, in order; the ones this day has. */
    val series: List<BarSeries>,
    val label: String,
    val nowWord: String,
    val kw: (Double) -> String,
    val price: (Double) -> String,
    val priceUnit: String,
    val money: (Double) -> String,
    val hourLabel: (Int) -> String,
    val dayCue: (Int) -> String,
    /** The readout of the hour at an index (its figures and, where kept, what the battery earned). */
    val words: (Int) -> String,
    val hint: String,
    val selected: Int? = null
)

/**
 * The house's day chart (the card's `house-chart.ts`), hour by hour on one axis, and under the Pris och batteri
 * view the battery's kr per hour on the same hours. See [HouseDayChartView] for what is drawn; under it the legend
 * (toggles, remembered by the page) and the readout. X-only touch scrub; arrow keys move between hours.
 */
@SuppressLint("ViewConstructor")
internal class HouseDayChart(
    private val scope: ViewScope,
    private val colours: SiteColours
) : LinearLayout(scope.context), OwnsSidewaysDrag {
    var onSelect: (Int?) -> Unit = {}
    var onHiddenChange: (Set<String>) -> Unit = {}

    private var spec: DaySpec? = null
    private var chosen: Int? = null
    private val legend = WrapRow(context, scope.dp(14), scope.dp(2))
    private val readout = TextView(context).apply {
        textSize = 13.5f
        setTextColor(scope.muted)
        minLines = 3
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val chart = HouseDayChartView(scope, colours, { say(it) }, { choose(it) }, { chosen })

    init {
        orientation = VERTICAL
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(readout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(4) })
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(2) })
    }

    fun show(next: DaySpec) {
        spec = next
        chosen = next.selected?.takeIf { it in next.hours.indices }
        chart.spec = next
        chart.contentDescription = next.label
        LegendRow.fill(scope, legend, next.series, next.hidden, true, onHiddenChange)
        say(chosen)
    }

    private fun say(index: Int?) {
        val current = spec ?: return
        readout.text = if (index == null || index !in current.hours.indices) current.hint else current.words(index)
        chart.shown = index
    }

    private fun choose(index: Int?) {
        chosen = index
        say(index)
        onSelect(index)
    }
}

/** The chart itself: bars, areas and lines on a canvas; made by [HouseDayChart]. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class HouseDayChartView(
    private val scope: ViewScope,
    private val colours: SiteColours,
    show: (Int?) -> Unit,
    choose: (Int?) -> Unit,
    chosen: () -> Int?
) : View(scope.context) {
    var spec: DaySpec? = null
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** The hour the finger or a key is on (its selection line is drawn). */
    var shown: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = scope.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * this@HouseDayChartView.density }
    private val rect = RectF()
    private val touch = ScrubTouch(this, ::hourAt, show, choose, chosen)
    private val select = choose
    private val current = chosen

    private val sell = 0xFFC2185B.toInt()

    init {
        isFocusable = true
        isClickable = true
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_NEXT, "›"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_PREVIOUS, "‹"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                val count = spec?.hours?.size ?: return false
                return when (action) {
                    ACTION_NEXT -> { select(BarsLayout.step(current(), count, BarsLayout.Move.RIGHT)); true }
                    ACTION_PREVIOUS -> { select(BarsLayout.step(current(), count, BarsLayout.Move.LEFT)); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
        }
    }

    private fun chartHeight(): Int = scope.dp(if (spec?.view == ChartView.PRICE) 170 else 190)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), chartHeight())
    }

    private fun left() = 40f * density
    private fun right() = width - (if (spec?.view == ChartView.PRICE) 32f else 6f) * density
    private fun top() = 15f * density
    private fun bottom() = chartHeight() - 18f * density

    private fun hourAt(x: Float): Int? {
        val hours = spec?.hours?.size ?: return null
        if (x < left() - 4 * density || x > right() + 4 * density) return null
        val share = (x - left()) / max(1f, right() - left())
        return (share * hours).toInt().coerceIn(0, hours - 1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = touch.onTouch(event)

    override fun performClick(): Boolean = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val count = spec?.hours?.size ?: 0
        val move = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> BarsLayout.Move.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BarsLayout.Move.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> BarsLayout.Move.HOME
            KeyEvent.KEYCODE_MOVE_END -> BarsLayout.Move.END
            KeyEvent.KEYCODE_ESCAPE -> BarsLayout.Move.CLEAR
            else -> return super.onKeyDown(keyCode, event)
        }
        select(BarsLayout.step(current(), count, move))
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val hours = s.hours
        if (hours.isEmpty()) return
        val hidden = s.hidden
        val on = { k: String -> k !in hidden }
        val l = left()
        val r = right()
        val t = top()
        val b = bottom()
        val n = hours.size
        val slot = (r - l) / n
        val startMs = hours.first().start.toEpochMilli()
        fun x(ms: Long) = l + ((ms - startMs).toFloat() / HouseGraph.HOUR_MS) * slot
        val price = s.view == ChartView.PRICE

        // The vertical scale.
        val low: Double
        val top: Double
        val step: Double
        if (price) {
            val (lo, hi) = HouseGraph.priceRange(hours, hidden)
            step = HouseGraph.niceStep(lo, hi, 3)
            low = Math.floor(lo / step + 1e-9) * step
            top = Math.ceil(hi / step - 1e-9) * step
        } else {
            val (tp, st) = HouseGraph.axisTop(HouseGraph.energyTop(hours, s.nowMs, hidden), 3)
            low = 0.0
            top = tp
            step = st
        }
        fun y(v: Double): Float = (b - ((v - low) / max(1e-9, top - low)) * (b - t)).toFloat()
        val base = y(max(0.0, low))

        // The plan's grid charging and selling, behind everything.
        if (price) {
            hours.forEachIndexed { i, h ->
                val key = when (h.batteryAction) { "grid_charge" -> Series.CHARGE; "export_discharge" -> Series.SELL; else -> null }
                if (key == null || !on(key)) return@forEachIndexed
                fill.color = if (key == Series.CHARGE) colours.of(Tone.GRID) else sell
                fill.alpha = if (key == Series.CHARGE) 41 else 51
                canvas.drawRect(l + i * slot, t, l + (i + 1) * slot, b, fill)
                fill.alpha = 255
            }
        }

        // Gridlines and the left axis.
        line.strokeWidth = 1f * density
        line.color = colours.wire
        text.color = colours.muted
        text.textAlign = Paint.Align.LEFT
        var v = low
        while (v <= top + 1e-9) {
            val gy = y(v)
            canvas.drawLine(l, gy, r, gy, line)
            val label = (if (price) s.price(v) else s.kw(v)) + if (v + step > top + 1e-9) (if (price) " ${s.priceUnit}" else " kW") else ""
            canvas.drawText(label, 2f * density, gy - 2f * density, text)
            v += step
        }
        // The hours: a line and a label every six hours, the day's cue at midnight.
        hours.forEachIndexed { i, h ->
            val local = h.start.atZone(s.zone).hour
            if (local % 6 != 0) return@forEachIndexed
            val gx = l + i * slot
            val midnight = local == 0
            line.color = if (midnight) colours.muted else colours.wire
            canvas.drawLine(gx, t, gx, b, line)
            text.textAlign = when {
                gx - l < 14 * density -> Paint.Align.LEFT
                r - gx < 14 * density -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            text.color = colours.muted
            canvas.drawText(if (midnight) s.dayCue(i) else s.hourLabel(i), gx, b + 13f * density, text)
        }
        if (price && on(Series.BATTERY)) {
            text.textAlign = Paint.Align.RIGHT
            for (pct in intArrayOf(0, 50, 100)) canvas.drawText("$pct %", width - 2f * density, b - pct / 100f * (b - t) - 2f * density, text)
        }

        if (price) {
            for ((key, series, colour, w) in listOf(
                Quad("sell", Series.SELL_PRICE, sell, 1.25f),
                Quad("buy", Series.BUY, colours.text, 1.5f)
            )) {
                if (!on(series)) continue
                line.color = colour
                line.strokeWidth = w * density
                line.alpha = if (series == Series.BUY) 205 else 255
                val path = Path()
                var open = false
                for (h in hours) {
                    val value = if (key == "buy") h.buy else h.sell
                    if (value == null) { open = false; continue }
                    val yy = y(value)
                    val x0 = x(h.start.toEpochMilli())
                    val x1 = x(h.start.toEpochMilli() + HouseGraph.HOUR_MS)
                    if (!open) { path.moveTo(x0, yy); open = true } else path.lineTo(x0, yy)
                    path.lineTo(x1, yy)
                }
                canvas.drawPath(path, line)
                line.alpha = 255
            }
        } else {
            // The sun: the area, then the raw forecast dotted over it.
            if (on(Series.SUN)) {
                val points = hours.map { h -> HouseGraph.sunShown(h, s.nowMs)?.let { x(h.start.toEpochMilli() + HouseGraph.HOUR_MS / 2) to y(it) } }
                if (points.any { it != null && it.second < base - 0.5f }) {
                    fill.color = colours.of(Tone.SUN)
                    fill.alpha = 130
                    drawRuns(canvas, points, base, fill)
                    fill.alpha = 255
                    line.color = colours.of(Tone.SUN)
                    line.strokeWidth = 1.5f * density
                    line.strokeCap = Paint.Cap.ROUND
                    line.pathEffect = DashPathEffect(floatArrayOf(1f * density, 4f * density), 0f)
                    fun up(i: Int) = (hours.getOrNull(i)?.sunForecastKwh ?: 0.0) > 0
                    val raw = hours.mapIndexed { i, h ->
                        if (h.sunForecastKwh == null || !(up(i - 1) || up(i) || up(i + 1))) null
                        else x(h.start.toEpochMilli() + HouseGraph.HOUR_MS / 2) to y(h.sunForecastKwh)
                    }
                    canvas.drawPath(pathOf(raw), line)
                    line.pathEffect = null
                    line.strokeCap = Paint.Cap.BUTT
                }
            }
            // The house: a bar per hour; over a measured bar the first forecast as a short line.
            val carOn = on(Series.CAR)
            hours.forEach { h ->
                val (kw, measured) = HouseGraph.houseShown(h)
                val x0 = x(h.start.toEpochMilli())
                val inset = slot * 0.18f
                val bx0 = x0 + inset
                val bx1 = x0 + slot - inset
                if (kw != null && kw > 0) {
                    if (on(Series.HOUSE)) {
                        fill.color = colours.of(Tone.HOUSE)
                        fill.alpha = if (measured) 217 else 140
                        rect.set(bx0, y(kw), bx1, base)
                        canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)
                    }
                    val heat = if (on(Series.HEAT)) HouseGraph.heatShown(h) else null
                    if (heat != null && heat > 0) {
                        fill.color = colours.of(Tone.HEAT)
                        fill.alpha = if (measured) 230 else 140
                        rect.set(bx0, y(heat), bx1, base)
                        canvas.drawRect(rect, fill)
                    }
                    fill.alpha = 255
                }
                if (on(Series.FORECAST) && measured && h.houseForecastKw != null) {
                    line.color = colours.text
                    line.alpha = 190
                    line.strokeWidth = 1.5f * density
                    line.strokeCap = Paint.Cap.ROUND
                    val fy = y(h.houseForecastKw)
                    canvas.drawLine(x0 + inset * 0.4f, fy, x0 + slot - inset * 0.4f, fy, line)
                    line.alpha = 255
                    line.strokeCap = Paint.Cap.BUTT
                }
                if (carOn && h.carKw != null && h.carKw > 0) {
                    val under = HouseGraph.carBase(h, on)
                    fill.color = colours.of(Tone.CAR)
                    fill.alpha = 230
                    rect.set(bx0, y(under + h.carKw), bx1, y(under))
                    canvas.drawRect(rect, fill)
                    fill.alpha = 255
                }
            }
        }

        // The battery: measured, then planned from the last measured point on so the lines meet.
        if (price && on(Series.BATTERY)) {
            fun socY(p: Double) = (b - min(100.0, max(0.0, p)) / 100.0 * (b - t)).toFloat()
            fun end(h: House.Hour) = x(h.start.toEpochMilli() + HouseGraph.HOUR_MS)
            val measured = hours.map { h -> h.socPct?.let { end(h) to socY(it) } }
            val planned = ArrayList<Pair<Float, Float>?>()
            measured.lastOrNull { it != null }?.let { planned += it }
            for (h in hours) if (h.start.toEpochMilli() + HouseGraph.HOUR_MS > s.nowMs && h.socPlanPct != null) planned += end(h) to socY(h.socPlanPct)
            line.color = colours.of(Tone.BATTERY)
            line.strokeWidth = 2f * density
            canvas.drawPath(pathOf(measured), line)
            line.pathEffect = DashPathEffect(floatArrayOf(5f * density, 3f * density), 0f)
            canvas.drawPath(pathOf(planned), line)
            line.pathEffect = null
        }

        // Now, and the chosen hour.
        if (s.nowMs in startMs..(startMs + n * HouseGraph.HOUR_MS)) {
            val nx = x(s.nowMs)
            line.color = colours.text
            line.strokeWidth = 1.25f * density
            canvas.drawLine(nx, t, nx, b, line)
            text.color = colours.text
            text.isFakeBoldText = true
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(s.nowWord, nx + 3f * density, t + 10f * density, text)
            text.isFakeBoldText = false
        }
        shown?.takeIf { it in hours.indices }?.let { i ->
            val sx = l + (i + 0.5f) * slot
            line.color = colours.text
            line.strokeWidth = 1f * density
            line.pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
            canvas.drawLine(sx, t, sx, b, line)
            line.pathEffect = null
        }
    }

    private fun pathOf(points: List<Pair<Float, Float>?>): Path {
        val path = Path()
        var open = false
        for (p in points) {
            if (p == null) { open = false; continue }
            if (!open) { path.moveTo(p.first, p.second); open = true } else path.lineTo(p.first, p.second)
        }
        return path
    }

    /** An area under each run of points, closed down to [base]. */
    private fun drawRuns(canvas: Canvas, points: List<Pair<Float, Float>?>, base: Float, paint: Paint) {
        var run = ArrayList<Pair<Float, Float>>()
        fun flush() {
            if (run.size > 0) {
                val path = Path()
                path.moveTo(run.first().first, base)
                run.forEach { path.lineTo(it.first, it.second) }
                path.lineTo(run.last().first, base)
                path.close()
                canvas.drawPath(path, paint)
            }
            run = ArrayList()
        }
        for (p in points) if (p == null) flush() else run.add(p)
        flush()
    }

    private data class Quad(val key: String, val series: String, val colour: Int, val width: Float)

    private companion object {
        const val ACTION_NEXT = 0x7E000011
        const val ACTION_PREVIOUS = 0x7E000012
    }
}
