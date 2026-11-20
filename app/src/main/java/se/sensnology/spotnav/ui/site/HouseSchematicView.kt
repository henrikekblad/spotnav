package se.sensnology.spotnav.ui.site

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import se.sensnology.spotnav.R
import java.util.EnumMap
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Now schematic: the house in the middle and around it what the site has, a line from each with dots running the
 * way the power flows and the live kW beside it, and the day's figures in the corners. The dots alone show the way
 * while they run; where animations are off they stand still and a filled arrowhead on the line's middle shows it, and
 * the view's description says each line's way in words, so the direction is never colour or motion alone.
 *
 * A fresh [Schematic] changes the figures, colours and speeds in place: each line keeps how far its dots have run, so
 * they never jump back on a refresh. The dots run only while [running] (the page is in view) and the view is shown.
 */
@SuppressLint("ViewConstructor") // built in code with the screen's colours: there is nothing to inflate
internal class HouseSchematicView(
    context: Context,
    private val colours: SiteColours,
    private val locale: Locale
) : View(context) {
    private var source: ((wide: Boolean) -> Schematic)? = null
    private var schematic: Schematic? = null
    private val phases = EnumMap<NodeRole, Float>(NodeRole::class.java)
    private var lastFrame = 0L
    private val density = resources.displayMetrics.density
    /** Larger text for a larger font setting, as far as the corners have room for. */
    private val fontScale = resources.configuration.fontScale.coerceIn(1f, 1.3f)

    /** Whether the page is in view: the dots run only then. */
    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            lastFrame = 0L
            if (value) invalidate()
        }

    /** Whether the device's animations are on; read at each draw, so the setting takes hold at once. Replaced in tests. */
    var animationsOn: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }

    private val icons: Map<NodeRole?, Drawable> = mapOf(
        null to R.drawable.ic_now_house,
        NodeRole.SUN to R.drawable.ic_now_sun,
        NodeRole.GRID to R.drawable.ic_now_grid,
        NodeRole.BATTERY to R.drawable.ic_now_battery,
        NodeRole.CAR to R.drawable.ic_now_car,
        NodeRole.HEAT to R.drawable.ic_now_heat
    ).mapValues { (role, id) ->
        context.getDrawable(id)!!.mutate().apply { setTint(colours.of(role?.let(HouseNowModel::tone) ?: Tone.HOUSE)) }
    }

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; color = colours.wire
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val discFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colours.card }
    private val discStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * fontScale; letterSpacing = 0.05f; color = colours.muted
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * fontScale; typeface = medium }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * fontScale }
    private val kwPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * fontScale; typeface = medium; strokeJoin = Paint.Join.ROUND }
    private val arrow = Path().apply { moveTo(-5f, -5f); lineTo(5f, 0f); lineTo(-5f, 5f); close() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    /** Draw what [source] gives for this view's width; a refresh hands a new one. */
    fun show(source: (wide: Boolean) -> Schematic) {
        this.source = source
        build()
    }

    private fun widthDp(): Float = width / density

    private fun build() {
        val before = schematic
        val next = source?.invoke(widthDp() >= WIDE_DP) ?: return
        schematic = next
        contentDescription = next.description
        // Another height (a part came or went and the site is, or is no longer, a house alone) is another layout.
        if (before == null || before.present.alone != next.present.alone) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val alone = schematic?.present?.alone ?: false
        val height = SchematicLayout.height(width / density, alone) + (fontScale - 1f) * EXTRA_PER_SCALE
        setMeasuredDimension(width, (height * density).roundToInt())
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth) build()
    }

    override fun onDraw(canvas: Canvas) {
        val shown = schematic ?: return
        val width = widthDp()
        val height = this.height / density
        val alone = shown.present.alone
        val spots = SchematicLayout.spots(width, height, alone)
        val moving = animationsOn()
        val now = SystemClock.uptimeMillis()
        val elapsed = if (lastFrame == 0L || !running || !moving) 0f else min(now - lastFrame, MAX_FRAME_MS) / 1000f
        lastFrame = now
        canvas.save()
        canvas.scale(density, density)
        var flowing = false
        val drawn = shown.lines.associate { it.role to SchematicLayout.line(it.role, it.way, spots, alone) }
        for (line in shown.lines) {
            val place = drawn.getValue(line.role)
            canvas.drawLine(place.from.x, place.from.y, place.to.x, place.to.y, wirePaint)
            if (line.way == Way.STILL || line.kw == null) continue
            flowing = true
            val phase = ((phases[line.role] ?: 0f) + elapsed / HouseNowModel.dotLap(line.kw).toFloat()) % 1f
            phases[line.role] = phase
            dotPaint.color = colours.of(line.tone)
            val ux = (place.to.x - place.from.x) / place.length
            val uy = (place.to.y - place.from.y) / place.length
            for (at in SchematicLayout.dots(place.length, phase)) {
                canvas.drawCircle(place.from.x + ux * at, place.from.y + uy * at, DOT_R, dotPaint)
            }
            if (!moving) {
                // The dots stand still: a filled arrowhead on the line's middle shows the way.
                canvas.save()
                canvas.translate(place.mid.x, place.mid.y)
                canvas.rotate(place.angleDegrees)
                canvas.drawPath(arrow, dotPaint)
                canvas.restore()
            }
        }
        for (role in NodeRole.entries) {
            val there = when (role) {
                NodeRole.SUN -> shown.present.sun
                NodeRole.GRID -> true
                NodeRole.BATTERY -> shown.present.battery
                NodeRole.CAR -> shown.present.car
                NodeRole.HEAT -> shown.present.heat
            }
            if (there) disc(canvas, role, spots.getValue(role), alone, dimmed = role == NodeRole.CAR && shown.carDimmed)
        }
        val house = spots.getValue(null)
        disc(canvas, null, house, alone, dimmed = false)
        shown.houseKw?.let { kw ->
            kwPaint.style = Paint.Style.FILL
            kwPaint.color = colours.text
            kwPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(kw, house.x, house.y + 17f + (fontScale - 1f) * 3f, kwPaint)
        }
        for (corner in shown.corners) corner(canvas, corner, width, height, alone)
        for (line in shown.lines) {
            val label = shown.labels[line.role] ?: continue
            val place = drawn.getValue(line.role).label
            kwPaint.textAlign = when (place.align) {
                LabelPlace.Align.START -> Paint.Align.LEFT
                LabelPlace.Align.MIDDLE -> Paint.Align.CENTER
                LabelPlace.Align.END -> Paint.Align.RIGHT
            }
            // On a halo in the card's colour, so the figure reads across a line.
            kwPaint.style = Paint.Style.STROKE
            kwPaint.strokeWidth = 4f
            kwPaint.color = colours.card
            canvas.drawText(label, place.at.x, place.at.y + 4f, kwPaint)
            kwPaint.style = Paint.Style.FILL
            kwPaint.color = colours.text
            canvas.drawText(label, place.at.x, place.at.y + 4f, kwPaint)
        }
        canvas.restore()
        if (flowing && moving && running && isShown) postInvalidateDelayed(FRAME_MS)
    }

    private fun disc(canvas: Canvas, role: NodeRole?, spot: Spot, alone: Boolean, dimmed: Boolean) {
        val r = SchematicLayout.radius(role, alone)
        val alpha = if (dimmed) DIM_ALPHA else 255
        canvas.drawCircle(spot.x, spot.y, r, discFill)
        discStroke.color = colours.of(role?.let(HouseNowModel::tone) ?: Tone.HOUSE)
        discStroke.alpha = alpha
        canvas.drawCircle(spot.x, spot.y, r, discStroke)
        // The icons are drawn around their middle in a 24-unit box; the house's sits above its figure.
        val scale = when {
            role != null -> 0.85f
            alone -> 0.8f
            else -> 0.95f
        }
        val lift = when {
            role != null -> 0f
            alone -> -1f
            else -> -7f
        }
        val icon = icons.getValue(role)
        canvas.save()
        canvas.translate(spot.x, spot.y + lift)
        canvas.scale(scale, scale)
        icon.alpha = alpha
        icon.setBounds(-12, -12, 12, 12)
        icon.draw(canvas)
        canvas.restore()
    }

    private fun corner(canvas: Canvas, words: CornerWords, width: Float, height: Float, alone: Boolean) {
        // In the low row only the grid (left) and the house (right).
        val column = when {
            alone -> if (words.role == NodeRole.GRID) 0 else 2
            words.role == NodeRole.SUN || words.role == NodeRole.BATTERY -> 0
            words.role == null || words.role == NodeRole.HEAT -> 1
            else -> 2
        }
        val top = alone || words.role == null || words.role == NodeRole.SUN || words.role == NodeRole.GRID
        val align = when (column) {
            0 -> Paint.Align.LEFT
            1 -> Paint.Align.CENTER
            else -> Paint.Align.RIGHT
        }
        val x = when (column) {
            0 -> 0f
            1 -> width / 2f
            else -> width
        }
        val room = if (alone) width / 2f - 8f else width / 3f - 6f
        val nameLine = 11f * fontScale
        val valueLine = 18f * fontScale
        val underLine = 14f * fontScale
        val total = nameLine + valueLine + if (words.under == null) 0f else underLine
        var y = if (top) 0f else height - total
        namePaint.textAlign = align
        val name = TextUtils.ellipsize(words.name.uppercase(locale), namePaint, room, TextUtils.TruncateAt.END).toString()
        canvas.drawText(name, x, y + nameLine - 1f, namePaint)
        y += nameLine + valueLine
        valuePaint.color = colours.figure(words.tone)
        smallPaint.typeface = medium
        smallPaint.color = colours.figure(words.besideTone ?: words.tone)
        val beside = words.beside
        if (beside == null) {
            valuePaint.textAlign = align
            canvas.drawText(words.value, x, y - 3f, valuePaint)
        } else {
            // The figure and the smaller one beside it, as one run of text from the corner's edge.
            val wide = valuePaint.measureText(words.value) + smallPaint.measureText(beside)
            val start = when (align) {
                Paint.Align.LEFT -> x
                Paint.Align.CENTER -> x - wide / 2f
                else -> x - wide
            }
            valuePaint.textAlign = Paint.Align.LEFT
            smallPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(words.value, start, y - 3f, valuePaint)
            canvas.drawText(beside, start + valuePaint.measureText(words.value), y - 3f, smallPaint)
        }
        words.under?.let { under ->
            y += underLine
            smallPaint.typeface = Typeface.DEFAULT
            smallPaint.color = words.underTone?.let(colours::figure) ?: colours.muted
            smallPaint.textAlign = align
            canvas.drawText(under, x, y - 2f, smallPaint)
        }
    }

    private companion object {
        /** From this width (dp) the grid's sold part stands beside the bought figure. */
        const val WIDE_DP = 480f
        const val DOT_R = 2.25f
        const val DIM_ALPHA = 102
        const val FRAME_MS = 33L
        /** A frame after a pause moves the dots no further than this. */
        const val MAX_FRAME_MS = 100L
        /** The height added for the largest text (dp per whole step of the font scale). */
        const val EXTRA_PER_SCALE = 60f
    }
}
