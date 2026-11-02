package se.sensnology.spotnav.ui.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * The Power view's bars, as the card draws them: each column's power split into the house's part (the lighter shade)
 * and the car's on top, a bar the backend could not split in one muted shade; a column that holds a counted peak is
 * outlined and tagged with its part's number; the import target is a dashed line across with its figure; a column the
 * target held (a dot) or paused (a ring) is marked under its bar; the y axis says 0, the middle and the top. A touch
 * chooses a column ([onSelect]); a tap on a measured one is handed to [onTap] where the screen drills into it.
 */
internal class PowerBarsView(context: Context) : View(context) {
    private var columns: List<PowerModel.Column> = emptyList()
    private var target: Double? = null
    private var top = 1.0
    private var targetLabel = ""
    private var tickLabels: List<String> = emptyList()
    private var selected: Int? = null
    private var colours = Colours(0, 0, 0, 0, 0, 0)

    /** The shades: the car, the house, a bar not split, the text, the muted lines and the outline of a chosen bar. */
    data class Colours(val car: Int, val house: Int, val unsplit: Int, val text: Int, val muted: Int, val outline: Int)

    var onSelect: (Int?) -> Unit = {}
    var onTap: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density }
    private val box = RectF()

    init {
        isClickable = true
        minimumHeight = (CHART_HEIGHT_DP * density).toInt()
    }

    fun show(
        columns: List<PowerModel.Column>,
        target: Double?,
        top: Double,
        targetLabel: String,
        tickLabels: List<String>,
        colours: Colours
    ) {
        this.columns = columns
        this.target = target
        this.top = if (top > 0) top else 1.0
        this.targetLabel = targetLabel
        this.tickLabels = tickLabels
        this.colours = colours
        if (selected != null && selected!! >= columns.size) selected = null
        invalidate()
    }

    fun select(index: Int?) {
        selected = index
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((CHART_HEIGHT_DP * density).toInt(), heightMeasureSpec)
        )
    }

    private fun plotLeft() = AXIS_DP * density

    override fun onDraw(canvas: Canvas) {
        if (columns.isEmpty()) return
        val left = plotLeft()
        val width = width - left
        val plotTop = TOP_ROOM_DP * density
        val bottom = height - (LABEL_ROOM_DP + MARK_ROOM_DP) * density
        val plotHeight = max(1f, bottom - plotTop)
        fun y(kw: Double) = bottom - (kw / top * plotHeight).toFloat()

        // The y axis: 0, the middle and the top, with a faint line across at the middle and the top.
        text.textAlign = Paint.Align.RIGHT
        text.color = colours.muted
        line.strokeWidth = 1f * density
        line.pathEffect = null
        listOf(0.0, top / 2, top).forEachIndexed { index, value ->
            val at = y(value)
            tickLabels.getOrNull(index)?.let { canvas.drawText(it, left - 4f * density, at + 4f * density, text) }
            line.color = colours.muted
            line.alpha = if (index == 0) 140 else 50
            canvas.drawLine(left, at, left + width, at, line)
        }
        line.alpha = 255

        val slot = width / columns.size
        val gap = max(1f, slot * 0.2f)
        text.textAlign = Paint.Align.CENTER
        columns.forEachIndexed { index, column ->
            val x0 = left + index * slot + gap / 2
            val x1 = left + (index + 1) * slot - gap / 2
            val kw = column.kw
            if (kw != null && kw > 0) {
                val barTop = minOf(y(kw), bottom - 1.5f * density)
                val house = column.house
                if (house == null) {
                    fill.color = colours.unsplit
                    box.set(x0, barTop, x1, bottom)
                    canvas.drawRect(box, fill)
                } else {
                    val houseTop = y(minOf(house, kw))
                    fill.color = colours.house
                    box.set(x0, houseTop, x1, bottom)
                    canvas.drawRect(box, fill)
                    fill.color = colours.car
                    box.set(x0, barTop, x1, houseTop)
                    canvas.drawRect(box, fill)
                }
                if (column.incomplete) {
                    fill.color = colours.text
                    fill.alpha = 60
                    box.set(x0, barTop, x1, bottom)
                    canvas.drawRect(box, fill)
                    fill.alpha = 255
                }
                if (column.counted.isNotEmpty()) {
                    line.color = colours.text
                    line.strokeWidth = 1.5f * density
                    box.set(x0, barTop, x1, bottom)
                    canvas.drawRect(box, line)
                    text.color = colours.text
                    canvas.drawText(column.counted.joinToString("·") { (it + 1).toString() }, (x0 + x1) / 2, barTop - 3f * density, text)
                }
            } else {
                fill.color = colours.muted
                fill.alpha = 90
                box.set(x0, bottom - 1.5f * density, x1, bottom)
                canvas.drawRect(box, fill)
                fill.alpha = 255
            }
            // The target's marks under the bar: a dot where it held the charging back, a ring where it paused it.
            val markY = bottom + MARK_ROOM_DP * density / 2
            val radius = minOf(slot / 3, 2.5f * density)
            if (column.paused) {
                line.color = colours.text
                line.strokeWidth = 1.2f * density
                canvas.drawCircle((x0 + x1) / 2, markY, radius, line)
            } else if (column.held) {
                fill.color = colours.text
                canvas.drawCircle((x0 + x1) / 2, markY, radius, fill)
            }
            if (index == selected) {
                // A chosen bar is underlined, as the card does, rather than filled behind.
                fill.color = colours.outline
                box.set(x0 - gap / 4, bottom + MARK_ROOM_DP * density - 2.5f * density, x1 + gap / 4, bottom + MARK_ROOM_DP * density)
                canvas.drawRect(box, fill)
            }
            if (column.label.isNotEmpty()) {
                text.color = colours.text
                canvas.drawText(column.label, (x0 + x1) / 2, height - 3f * density, text)
            }
        }

        // The import target: a dashed line across, its figure at the right end.
        target?.let { kw ->
            val at = y(kw)
            line.color = colours.text
            line.strokeWidth = 1.5f * density
            line.pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
            canvas.drawLine(left, at, left + width, at, line)
            line.pathEffect = null
            text.textAlign = Paint.Align.RIGHT
            text.color = colours.text
            canvas.drawText(targetLabel, left + width, at - 4f * density, text)
        }
    }

    private fun indexAt(x: Float): Int? {
        val left = plotLeft()
        if (columns.isEmpty() || x < left) return null
        val index = ((x - left) / ((width - left) / columns.size)).toInt()
        return index.takeIf { it in columns.indices }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = indexAt(event.x)
                if (index != selected) {
                    selected = index
                    invalidate()
                    onSelect(index)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                val index = indexAt(event.x)
                if (index != null && columns[index].kw != null) onTap?.invoke(index)
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val CHART_HEIGHT_DP = 210
        const val TOP_ROOM_DP = 16
        const val LABEL_ROOM_DP = 16
        const val MARK_ROOM_DP = 10
        const val AXIS_DP = 34
    }
}
