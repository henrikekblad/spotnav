package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import se.sensnology.spotnav.ui.common.Palette

/**
 * The thermostat dial of the heat page (the card's `dial`): a 270 degree track from 16 to 25 °C with the comfort band
 * around the target, the target's arc, the measured value as a dot and the target in figures with the band under it.
 * It only draws; the target is changed with the buttons beside it, so nothing here competes with a swipe.
 */
@SuppressLint("ViewConstructor") // built in code with the screen's colours
internal class HeatDialView(context: Context, private val palette: Palette, private val colours: SiteColours) : View(context) {
    private var target = 21.0
    private var measured: Double? = null
    private var figure = ""
    private var band = ""

    private val density = resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val figurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = palette.dark
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = palette.muted
    }
    private val box = RectF()

    fun show(target: Double, measured: Double?, figure: String, band: String, description: String) {
        this.target = target
        this.measured = measured
        this.figure = figure
        this.band = band
        contentDescription = description
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wide = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost((260 * density).toInt())
        setMeasuredDimension(wide, (wide * 170f / 200f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val unit = width / 200f
        val cx = 100 * unit
        val cy = 96 * unit
        val r = 78 * unit
        box.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.strokeWidth = 12 * unit
        arcPaint.color = colours.wire
        canvas.drawArc(box, START, SWEEP, false, arcPaint)
        // The comfort band, a degree wide around the target.
        val low = HeatNowModel.share(target - 0.5).toFloat()
        val high = HeatNowModel.share(target + 0.5).toFloat()
        arcPaint.strokeWidth = 18 * unit
        arcPaint.color = SiteColours.mix(colours.of(Tone.HEAT), palette.cardBackground, 0.3f)
        canvas.drawArc(box, START + SWEEP * low, SWEEP * (high - low).coerceAtLeast(0.004f), false, arcPaint)
        arcPaint.strokeWidth = 12 * unit
        arcPaint.color = palette.accent
        canvas.drawArc(box, START, SWEEP * HeatNowModel.share(target).toFloat().coerceAtLeast(0.002f), false, arcPaint)
        measured?.let {
            val angle = Math.toRadians((START + SWEEP * HeatNowModel.share(it)).toDouble())
            val x = cx + r * Math.cos(angle).toFloat()
            val y = cy + r * Math.sin(angle).toFloat()
            fill.color = palette.cardBackground
            canvas.drawCircle(x, y, 9 * unit, fill)
            fill.color = palette.dark
            canvas.drawCircle(x, y, 6 * unit, fill)
        }
        figurePaint.textSize = 44 * unit
        canvas.drawText(figure, cx, cy + 8 * unit + 10 * unit, figurePaint)
        bandPaint.textSize = 13 * unit
        canvas.drawText(band, cx, cy + 32 * unit, bandPaint)
    }

    private companion object {
        const val START = 135f
        const val SWEEP = 270f
    }
}

/** The thin strip under the dial: a cell for each of the plan's next twelve hours, hot water as a dot, the clock under it. */
@SuppressLint("ViewConstructor") // built in code with the screen's colours
internal class AheadStripView(context: Context, private val palette: Palette, private val colours: SiteColours) : View(context) {
    private var cells: List<AheadCell?> = emptyList()
    private var axis: List<String> = emptyList()
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted
        textSize = 11 * density
    }

    fun show(cells: List<AheadCell?>, axis: List<String>) {
        this.cells = cells
        this.axis = axis
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (36 * density).toInt())
    }

    private fun colour(action: String?): Int = when (action) {
        "lower", "away" -> colours.of(Tone.GRID)
        "preheat", "raise" -> colours.of(Tone.HEAT)
        "night" -> SiteColours.mix(colours.of(Tone.GRID), palette.cardBackground, 0.55f)
        null -> 0
        else -> colours.wire
    }

    override fun onDraw(canvas: Canvas) {
        if (cells.isEmpty()) return
        val gap = 2 * density
        val cell = (width - gap * (cells.size - 1)) / cells.size
        val height = 14 * density
        cells.forEachIndexed { i, c ->
            val left = i * (cell + gap)
            fill.color = if (c == null) SiteColours.mix(colours.wire, palette.cardBackground, 0.45f) else colour(c.action)
            canvas.drawRoundRect(left, 0f, left + cell, height, 3 * density, 3 * density, fill)
            if (c?.hotWater == true) {
                fill.color = palette.dark
                canvas.drawCircle(left + cell / 2, height / 2, 2.5f * density, fill)
            }
        }
        axis.forEachIndexed { i, text -> canvas.drawText(text, i * 3 * (cell + gap), height + 14 * density, label) }
    }
}
