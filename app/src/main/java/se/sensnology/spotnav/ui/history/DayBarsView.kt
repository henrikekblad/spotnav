package se.sensnology.spotnav.ui.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * One bar for every day of a month: the height is the day's energy, the colour where the day's
 * average price sits in the month (green cheap, red dear). A touch on a bar selects it, and the
 * screen writes that day's figures beside the chart; a day with no charge has no bar, only its tick.
 */
internal class DayBarsView(context: Context) : View(context) {
    private var bars: List<DayBar> = emptyList()
    private var selected: Int? = null
    private var topLabel: String = ""

    private var barColour: (DayBar) -> Int = { 0 }
    private var textColour = 0
    private var mutedColour = 0
    private var outlineColour = 0

    /** Called with the index of the bar under the finger, or `null` when it was let go over none. */
    var onSelect: (Int?) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density }
    private val box = RectF()

    init {
        isClickable = true
        minimumHeight = (CHART_HEIGHT_DP * density).toInt()
    }

    /** What is drawn: the bars, the label of the tallest one, and how a bar is coloured. */
    fun show(
        bars: List<DayBar>,
        topLabel: String,
        colour: (DayBar) -> Int,
        text: Int,
        muted: Int,
        outline: Int
    ) {
        this.bars = bars
        this.topLabel = topLabel
        barColour = colour
        textColour = text
        mutedColour = muted
        outlineColour = outline
        if (selected != null && selected!! >= bars.size) selected = null
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

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val width = width.toFloat()
        val labelRoom = LABEL_ROOM_DP * density
        val top = labelRoom
        val bottom = height - labelRoom
        val plotHeight = max(1f, bottom - top)
        val slot = width / bars.size
        val gap = max(1f, slot * 0.18f)

        text.color = mutedColour
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(topLabel, 0f, labelRoom - 4f * density, text)

        bars.forEachIndexed { index, bar ->
            val left = index * slot + gap / 2
            val right = (index + 1) * slot - gap / 2
            if (bar.day.energyKwh > 0.0) {
                fill.color = barColour(bar)
                val barTop = bottom - max(2f * density, (bar.height * plotHeight).toFloat())
                box.set(left, barTop, right, bottom)
                canvas.drawRoundRect(box, 2f * density, 2f * density, fill)
            } else {
                fill.color = mutedColour
                fill.alpha = 90
                box.set(left, bottom - 1.5f * density, right, bottom)
                canvas.drawRect(box, fill)
                fill.alpha = 255
            }
            if (index == selected) {
                line.color = outlineColour
                box.set(left - gap / 4, top, right + gap / 4, bottom + 1f)
                canvas.drawRoundRect(box, 3f * density, 3f * density, line)
            }
        }

        // The day numbers under the chart: the first, every seventh and the last.
        text.color = textColour
        text.textAlign = Paint.Align.CENTER
        bars.forEachIndexed { index, bar ->
            val day = bar.day.date.dayOfMonth
            if (day == 1 || day % 7 == 1 && index < bars.size - 3 || index == bars.lastIndex) {
                canvas.drawText(day.toString(), index * slot + slot / 2, height - 3f * density, text)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = DayBars.indexAt(event.x, width.toFloat(), bars.size)
                if (index != selected) {
                    selected = index
                    invalidate()
                    onSelect(index)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val CHART_HEIGHT_DP = 190
        const val LABEL_ROOM_DP = 18
    }
}
