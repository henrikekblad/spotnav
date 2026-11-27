package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import se.sensnology.spotnav.ui.common.Energy
import se.sensnology.spotnav.ui.common.EnergyPalette
import kotlin.math.ceil
import kotlin.math.max

/** The colours of the Plan box's layers and lines, from the house page's part colours ([SiteColours]). */
internal class PlanPalette(private val colours: SiteColours) {
    fun of(layer: PlanLayer): Int = when (layer) {
        PlanLayer.HOUSE -> colours.of(Tone.HOUSE)
        PlanLayer.HEAT -> colours.of(Tone.HEAT)
        PlanLayer.WATER_HEATER -> WATER_HEATER
        PlanLayer.CAR -> colours.of(Tone.CAR)
        PlanLayer.POOL_HEAT -> colours.of(Tone.POOL)
        // A lighter step of the pool, beside its darker heat pump (the card's `load-colours.ts`).
        PlanLayer.POOL_CIRCULATION -> EnergyPalette.mixWhite(colours.of(Tone.POOL), 0.55f)
        PlanLayer.SPA -> 0xFF6F8F2F.toInt()
        PlanLayer.SAUNA -> 0xFFA8693F.toInt()
        PlanLayer.BATTERY_IN -> colours.of(Tone.BATTERY)
        PlanLayer.SUN -> colours.of(Tone.SUN)
        PlanLayer.BATTERY_OUT -> SiteColours.mix(colours.of(Tone.BATTERY), colours.card, 0.55f)
    }

    /** A price band's background: the energy palette's cheap, mid or expensive at 35 %. */
    fun tone(tone: PriceTone): Int = withAlpha(
        when (tone) {
            PriceTone.CHEAP -> colours.of(Energy.CHEAP)
            PriceTone.MID -> colours.of(Energy.MID)
            PriceTone.EXPENSIVE -> colours.of(Energy.EXPENSIVE)
        }, 0.35f
    )

    val text: Int get() = colours.text
    val muted: Int get() = colours.muted
    val grid: Int get() = colours.wire

    companion object {
        /** The card's `--spotnav-water-heater` fallback. */
        const val WATER_HEATER = 0xFFC8607A.toInt()

        /** The ceiling's red (the card's `--error-color` fallback). */
        const val CEILING = 0xFFDB4437.toInt()

        /** A watched load's part: faded, as the card draws it. */
        fun faded(colour: Int): Int = withAlpha(colour, 0.4f)

        fun withAlpha(colour: Int, alpha: Float): Int = Color.argb((alpha * 255).toInt(), Color.red(colour), Color.green(colour), Color.blue(colour))
    }
}

/**
 * The Plan box's stacked chart (the card's `house-plan.ts` chart): per hour the house and the loads above zero, the
 * sun and the battery's cover below, the net from the grid as a line with a dot where it goes over the ceiling, the
 * ceiling as a dashed step line labelled once, the hour ticks, a line at midnight with the day's cue, and the price
 * strip under the hours. A watched load's part is faded. Read-only; [contentDescription] says what it is.
 */
@SuppressLint("ViewConstructor")
internal class HousePlanChartView(context: Context, private val palette: PlanPalette) : View(context) {
    var chart: HousePlanModel.Chart? = null
        set(value) {
            field = value
            contentDescription = value?.description
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density }
    private val rect = RectF()
    private val midnightDash = DashPathEffect(floatArrayOf(2f * density, 3f * density), 0f)
    private val ceilingDash = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    private val ceiling = Path()
    private val net = Path()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (HEIGHT * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val c = chart ?: return
        val n = c.hours.size
        if (n == 0) return
        val left = 30f * density
        val right = width - 6f * density
        val top = 18f * density
        val bottom = height - 44f * density
        val step = (right - left) / n
        val bar = max(1f, step - 2f * density)
        val span = c.maxKw - c.minKw
        fun y(kw: Double): Float = (top + (bottom - top) * ((c.maxKw - kw) / span)).toFloat()
        fun x(index: Int): Float = left + step * index

        // The grid, its kW, and the unit.
        label.color = palette.muted
        label.textAlign = Paint.Align.RIGHT
        val gridStep = if (span > 12) 4 else 2
        var kw = ceil(c.minKw / gridStep) * gridStep
        while (kw <= c.maxKw) {
            line.color = if (kw == 0.0) palette.muted else palette.grid
            line.strokeWidth = density
            line.pathEffect = null
            canvas.drawLine(left, y(kw), right, y(kw), line)
            canvas.drawText(kw.toInt().toString(), left - 4f * density, y(kw) + 3f * density, label)
            kw += gridStep
        }
        canvas.drawText("kW", left - 4f * density, top - 6f * density, label)

        c.hours.forEachIndexed { index, hour ->
            var acc = 0.0
            for (part in hour.above) {
                fill.color = colour(part)
                rect.set(x(index) + density, y(acc + part.kw), x(index) + density + bar, y(acc))
                canvas.drawRect(rect, fill)
                acc += part.kw
            }
            var neg = 0.0
            for (part in hour.below) {
                fill.color = colour(part)
                rect.set(x(index) + density, y(neg), x(index) + density + bar, y(neg - part.kw))
                canvas.drawRect(rect, fill)
                neg -= part.kw
            }
            hour.tone?.let { tone ->
                fill.color = palette.tone(tone)
                rect.set(x(index) + density, bottom + 16f * density, x(index) + density + bar, bottom + 24f * density)
                canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)
            }
            hour.dayCue?.let { cue ->
                line.color = palette.muted
                line.strokeWidth = density
                line.pathEffect = midnightDash
                canvas.drawLine(x(index), top, x(index), bottom + 4f * density, line)
                label.textAlign = Paint.Align.LEFT
                canvas.drawText(cue, x(index) + 3f * density, top - 6f * density, label)
            }
            hour.tick?.let { tick ->
                label.textAlign = Paint.Align.CENTER
                label.color = palette.muted
                canvas.drawText(tick, x(index) + step / 2, bottom + 12f * density, label)
            }
        }
        label.textAlign = Paint.Align.LEFT
        label.color = palette.muted
        canvas.drawText(c.priceStrip, left, height - 6f * density, label)

        // The ceiling: a dashed step line where it is known and inside the chart, labelled once at its last hour.
        ceiling.reset()
        var open = false
        c.hours.forEachIndexed { index, hour ->
            val at = hour.ceilingKw
            if (at == null || at > c.maxKw) {
                open = false
                return@forEachIndexed
            }
            if (!open) ceiling.moveTo(x(index), y(at)) else ceiling.lineTo(x(index), y(at))
            ceiling.lineTo(x(index + 1), y(at))
            open = true
        }
        line.color = PlanPalette.CEILING
        line.strokeWidth = 1.6f * density
        line.pathEffect = ceilingDash
        canvas.drawPath(ceiling, line)
        val ceilingKw = c.ceilingLabelKw
        if (c.ceilingLabel != null && ceilingKw != null) {
            label.color = PlanPalette.CEILING
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(c.ceilingLabel, right, y(ceilingKw) - 4f * density, label)
        }

        // The net from the grid, and a dot where it goes over the ceiling.
        net.reset()
        var started = false
        c.hours.forEachIndexed { index, hour ->
            val value = hour.netKw ?: return@forEachIndexed
            if (!started) net.moveTo(x(index) + step / 2, y(value)) else net.lineTo(x(index) + step / 2, y(value))
            started = true
        }
        line.color = palette.text
        line.strokeWidth = 2f * density
        line.pathEffect = null
        if (started) canvas.drawPath(net, line)
        fill.color = PlanPalette.CEILING
        c.hours.forEachIndexed { index, hour ->
            val value = hour.netKw
            if (hour.over && value != null) canvas.drawCircle(x(index) + step / 2, y(value), 3.5f * density, fill)
        }
    }

    private fun colour(part: HousePlanModel.Part): Int = palette.of(part.layer).let { if (part.would) PlanPalette.faded(it) else it }

    private companion object {
        const val HEIGHT = 230f
    }
}

/**
 * One load's lane in the Plan box: a track along the plan's hours with a block where the load runs (the battery's
 * charging and covering in their colours, a heat lane's lowerings and pre-heats, a lowering drawn as a dashed outline).
 * A watched load's blocks are faded.
 */
@SuppressLint("ViewConstructor")
internal class PlanLaneTrackView(context: Context, private val palette: PlanPalette, private val track: Int) : View(context) {
    var lane: HousePlanModel.Lane? = null
        set(value) {
            field = value
            invalidate()
        }
    var hours: Int = 1
        set(value) {
            field = max(1, value)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(3f * resources.displayMetrics.density, 2f * resources.displayMetrics.density), 0f)
    }
    private val rect = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (18 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = track
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 5f * density, 5f * density, fill)
        val shown = lane ?: return
        val step = width.toFloat() / hours
        for (block in shown.blocks) {
            val colour = palette.of(block.layer).let { if (block.would) PlanPalette.faded(it) else it }
            rect.set(step * block.index, 2f * density, step * (block.index + 1), height - 2f * density)
            val level = block.level
            if (level != null && level < 0) {
                outline.color = colour
                rect.inset(density, density)
                canvas.drawRoundRect(rect, 3f * density, 3f * density, outline)
            } else {
                fill.color = colour
                canvas.drawRoundRect(rect, 3f * density, 3f * density, fill)
            }
        }
    }
}
