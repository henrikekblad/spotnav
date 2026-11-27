package se.sensnology.spotnav.ui.site

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.view.View
import se.sensnology.spotnav.ui.common.Energy
import java.util.EnumMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Now drawing, as the Home Assistant card draws it (`frontend/src/house-now.ts`): only the nodes the site has
 * (the sun, the grid, the battery, the car) around the house, in a row for one or two and at fixed corners for three
 * or four ([SchematicLayout]). Each line is as wide as the square root of its power and carries white chevrons moving
 * from source to target, faster with more power; with the device's animations off they stand still and still show the
 * way. A line without power is a thin dashed idle line. The kW stands beside its line, "≈" before an estimated one.
 * The house has its whole figure in the middle and a ring of its parts (heat, the pool with the spa and the sauna, the
 * rest); the battery its level as a ring and its icon filled to it. Two captions at each node: its state now and
 * today's kWh. The view's description says it all in words, so nothing is colour or motion alone.
 *
 * A fresh [NowPicture] changes the figures, widths and speeds in place: each line keeps how far its chevrons have run,
 * so they never jump back on a refresh. They run only while [running] (the page is in view) and the view is shown.
 */
@SuppressLint("ViewConstructor") // built in code with the screen's colours: there is nothing to inflate
internal class HouseSchematicView(context: Context, private val colours: SiteColours) : View(context) {
    private var picture: NowPicture? = null
    private val offsets = EnumMap<NodeRole, Float>(NodeRole::class.java)
    private var lastFrame = 0L
    private val density = resources.displayMetrics.density
    /** Larger captions for a larger font setting, as far as the drawing has room for them. */
    private val fontScale = resources.configuration.fontScale.coerceIn(1f, 1.3f)

    /** Whether the page is in view: the chevrons run only then. */
    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            lastFrame = 0L
            if (value) invalidate()
        }

    /** Whether the device's animations are on (off with the animator scale at 0, as "Remove animations" sets it); read
     * at each draw, so the setting takes hold at once. Replaced in tests. */
    var animationsOn: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = WHITE; alpha = 71 }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = WHITE; alpha = 217
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val chevron = Path().apply { moveTo(-3f, -4f); lineTo(2f, 0f); lineTo(-3f, 4f) }
    private val arc = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    /** Draw [next]; a refresh hands a new one. */
    fun show(next: NowPicture) {
        val before = picture
        picture = next
        contentDescription = next.description
        // Another set of nodes is another layout, and perhaps another height.
        if (before == null || before.figures.lines.map { it.role } != next.figures.lines.map { it.role }) requestLayout()
        invalidate()
    }

    private fun roles(): List<NodeRole> = picture?.figures?.lines?.map { it.role } ?: listOf(NodeRole.GRID)

    /** The drawing's own width (dp) and how much smaller it is drawn in a box narrower than its least width. */
    private fun frame(widthPx: Int): Pair<Float, Float> {
        val box = widthPx / density
        val drawing = SchematicLayout.drawingWidth(box)
        return drawing to min(1f, box / drawing)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val (drawing, shrink) = frame(width)
        val layout = SchematicLayout.layout(roles(), drawing)
        setMeasuredDimension(width, (layout.drawn * shrink * density).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val shown = picture ?: return
        val (width, shrink) = frame(this.width)
        val layout = SchematicLayout.layout(shown.figures.lines.map { it.role }, width)
        val k = layout.k
        val (wantedCaption, labelSize, bigSize) = SchematicLayout.fontSizes(k)
        val caption = SchematicLayout.captionSize((wantedCaption * fontScale).roundToInt(), layout, shown.captions).toFloat()
        val moving = animationsOn()
        val now = SystemClock.uptimeMillis()
        val elapsed = if (lastFrame == 0L || !running || !moving) 0f else min(now - lastFrame, MAX_FRAME_MS) / 1000f
        lastFrame = now
        canvas.save()
        canvas.scale(density * shrink, density * shrink)
        // A drawing narrower than the box (a wide tablet) stands in its middle.
        canvas.translate(max(0f, (this.width / (density * shrink) - width) / 2f), layout.top)
        val house = layout.spots.getValue(null)
        var flowing = false
        idlePaint.color = colours.of(Energy.IDLE)
        val labels = ArrayList<Triple<String, LabelPlace, Float>>()
        for (line in shown.figures.lines) {
            val spot = layout.spots.getValue(line.role)
            // Drawn from the source to the target, so the chevrons run the way the power flows.
            val ends = if (line.way == Way.OUT) {
                SchematicLayout.lineEnds(house, spot, SchematicLayout.outer(null) * k, SchematicLayout.outer(line.role) * k)
            } else {
                SchematicLayout.lineEnds(spot, house, SchematicLayout.outer(line.role) * k, SchematicLayout.outer(null) * k)
            }
            val kw = line.kw
            if (kw == null || line.way == Way.STILL) {
                idlePaint.strokeWidth = 1.5f
                canvas.drawLine(ends.a.x, ends.a.y, ends.b.x, ends.b.y, idlePaint)
                continue
            }
            flowing = true
            val w = SchematicLayout.lineWidth(kw, k)
            linePaint.strokeWidth = w
            linePaint.color = colours.of(line.tone)
            linePaint.alpha = 230
            canvas.drawLine(ends.a.x, ends.a.y, ends.b.x, ends.b.y, linePaint)
            // A faint light stripe along the line's upper side, so it reads as a tube.
            var nx = -ends.uy
            var ny = ends.ux
            if (ny > 0) { nx = -nx; ny = -ny }
            val sh = w * 0.18f
            stripePaint.strokeWidth = max(1f, w * 0.28f)
            canvas.drawLine(ends.a.x + nx * sh, ends.a.y + ny * sh, ends.b.x + nx * sh, ends.b.y + ny * sh, stripePaint)
            SchematicLayout.chevronScale(kw, k)?.let { scale ->
                // Evenly spaced chevrons in the line's own frame, one more than fit, moving one spacing per lap and
                // clipped to the line, so they flow on without a jump.
                val count = SchematicLayout.chevronCount(ends.length, k)
                val step = ends.length / count
                val offset = ((offsets[line.role] ?: 0f) + elapsed * SchematicLayout.chevronSpeed(kw, k)) % step
                offsets[line.role] = offset
                canvas.save()
                canvas.translate(ends.a.x, ends.a.y)
                canvas.rotate(Math.toDegrees(atan2(ends.uy.toDouble(), ends.ux.toDouble())).toFloat())
                canvas.clipRect(0f, -12f * k, ends.length, 12f * k)
                chevronPaint.strokeWidth = 2f * min(1f, scale / k * 1.4f) * k / scale
                for (index in -1 until count) {
                    canvas.save()
                    canvas.translate((index + 0.5f) * step + offset, 0f)
                    canvas.scale(scale, scale)
                    canvas.drawPath(chevron, chevronPaint)
                    canvas.restore()
                }
                canvas.restore()
            }
            shown.labels[line.role]?.let { labels.add(Triple(it, SchematicLayout.labelPlace(ends.a, ends.b, kw, house.x, k), w)) }
        }
        // The outer nodes, each with its two captions.
        for (line in shown.figures.lines) {
            val spot = layout.spots.getValue(line.role)
            val tone = colours.of(line.tone)
            if (line.role == NodeRole.BATTERY) {
                ringPaint.strokeWidth = 4f * k
                ringPaint.strokeCap = Paint.Cap.BUTT
                ringPaint.color = colours.of(Energy.RING)
                canvas.drawCircle(spot.x, spot.y, SchematicLayout.SOC_R * k, ringPaint)
                val soc = (shown.socPct ?: 0.0).coerceIn(0.0, 100.0)
                if (soc > 0) {
                    ringPaint.color = colours.of(Tone.BATTERY)
                    ringPaint.strokeCap = Paint.Cap.ROUND
                    val r = SchematicLayout.SOC_R * k
                    arc.set(spot.x - r, spot.y - r, spot.x + r, spot.y + r)
                    canvas.drawArc(arc, -90f, (soc * 3.6).toFloat(), false, ringPaint)
                }
            }
            fillPaint.color = colours.card
            canvas.drawCircle(spot.x, spot.y, SchematicLayout.NODE_R * k, fillPaint)
            ringPaint.strokeCap = Paint.Cap.BUTT
            ringPaint.strokeWidth = 2.5f * k
            ringPaint.color = tone
            canvas.drawCircle(spot.x, spot.y, SchematicLayout.NODE_R * k, ringPaint)
            canvas.save()
            canvas.translate(spot.x - 12f * k, spot.y - 12f * k)
            canvas.scale(k, k)
            icon(canvas, line.role, tone, shown.socPct)
            canvas.restore()
            val words = shown.captions.getValue(line.role)
            val below = spot.y >= house.y
            val (x, align) = SchematicLayout.captionAnchor(spot.x, width)
            val step = caption + 3f
            listOf(words.state, words.today).forEachIndexed { index, text ->
                val y = if (below) spot.y + 42f * k + index * step else spot.y - 31f * k - (1 - index) * step
                val captionTone = if (line.role == NodeRole.GRID) Tone.IMPORT else line.tone
                write(canvas, text, x, y, align, caption, if (index == 0) colours.muted else figure(captionTone), bold = index == 1)
            }
        }
        // The house: the ring of its parts, the whole house in the middle and its day under the ring.
        val ringR = SchematicLayout.RING_R * k
        ringPaint.strokeCap = Paint.Cap.BUTT
        ringPaint.strokeWidth = SchematicLayout.RING_W * k
        ringPaint.color = colours.of(Energy.RING)
        canvas.drawCircle(house.x, house.y, ringR, ringPaint)
        val parts = shown.figures.parts.filter { it.kw > 0 }
        val whole = parts.sumOf { it.kw }
        if (whole > 0) {
            val around = (2 * Math.PI * ringR).toFloat()
            arc.set(house.x - ringR, house.y - ringR, house.x + ringR, house.y + ringR)
            var done = 0f
            for (part in parts) {
                val share = (part.kw / whole).toFloat()
                val length = max(0.5f, around * share - if (share < 1f) 2f else 0f)
                ringPaint.color = colours.of(HouseNowModel.tone(part.role))
                canvas.drawArc(arc, -90f + done * 360f, length / around * 360f, false, ringPaint)
                done += share
            }
        }
        fillPaint.color = colours.card
        canvas.drawCircle(house.x, house.y, SchematicLayout.INNER_R * k, fillPaint)
        canvas.save()
        canvas.translate(house.x - 7.44f * k, house.y - 24.44f * k)
        canvas.scale(0.62f * k, 0.62f * k)
        icon(canvas, null, colours.of(Tone.HOUSE), null)
        canvas.restore()
        write(canvas, shown.houseFigure, house.x, house.y + 8f * k, LabelPlace.Align.MIDDLE, bigSize.toFloat(), colours.text, bold = true)
        write(canvas, shown.houseName, house.x, house.y + 8f * k + caption + 2f, LabelPlace.Align.MIDDLE, caption, colours.muted)
        // In a row, under the outer nodes' two captions, so the three never meet.
        val todayY = if (layout.kind == LayoutKind.ROW) house.y + 42f * k + 2 * (caption + 3f) else house.y + ringR + 19f * k
        write(canvas, shown.houseToday, house.x, todayY, LabelPlace.Align.MIDDLE, caption, SiteColours.mix(colours.of(Tone.HOUSE), colours.text, 0.68f), bold = true)
        // The kW figures last, on a halo in the card's colour, so they read across a line.
        for ((text, place, _) in labels) {
            words.textSize = labelSize.toFloat()
            words.typeface = medium
            words.textAlign = paintAlign(place.align)
            words.style = Paint.Style.STROKE
            words.strokeWidth = 5f
            words.strokeJoin = Paint.Join.ROUND
            words.color = colours.card
            canvas.drawText(text, place.at.x, place.at.y, words)
            words.style = Paint.Style.FILL
            words.color = colours.text
            canvas.drawText(text, place.at.x, place.at.y, words)
        }
        canvas.restore()
        if (flowing && moving && running && isShown) postInvalidateDelayed(FRAME_MS)
    }

    /** Words in a part's colour, a third toward the text colour, so they keep their contrast on both screens. */
    private fun figure(tone: Tone): Int = SiteColours.mix(colours.of(tone), colours.text, 0.68f)

    private fun paintAlign(align: LabelPlace.Align): Paint.Align = when (align) {
        LabelPlace.Align.START -> Paint.Align.LEFT
        LabelPlace.Align.MIDDLE -> Paint.Align.CENTER
        LabelPlace.Align.END -> Paint.Align.RIGHT
    }

    private fun write(canvas: Canvas, text: String, x: Float, y: Float, align: LabelPlace.Align, size: Float, colour: Int, bold: Boolean = false) {
        words.style = Paint.Style.FILL
        words.textSize = size
        words.typeface = if (bold) medium else Typeface.DEFAULT
        words.textAlign = paintAlign(align)
        words.color = colour
        canvas.drawText(text, x, y, words)
    }

    /** The card's line icons in a 24-unit box, stroked in the node's colour; the battery's filled to its level. */
    private fun icon(canvas: Canvas, role: NodeRole?, tone: Int, soc: Double?) {
        iconPaint.color = tone
        fillPaint.color = tone
        when (role) {
            NodeRole.SUN -> {
                canvas.drawCircle(12f, 12f, 4.2f, iconPaint)
                for (index in 0 until 8) {
                    val angle = index * Math.PI / 4
                    canvas.drawLine(
                        (12 + cos(angle) * 7).toFloat(), (12 + sin(angle) * 7).toFloat(),
                        (12 + cos(angle) * 9.6).toFloat(), (12 + sin(angle) * 9.6).toFloat(), iconPaint
                    )
                }
            }
            NodeRole.GRID -> canvas.drawPath(GRID_ICON, iconPaint)
            NodeRole.BATTERY -> {
                canvas.drawRoundRect(6f, 5f, 18f, 22f, 2.5f, 2.5f, iconPaint)
                canvas.drawRoundRect(9.5f, 2.5f, 14.5f, 5f, 1f, 1f, fillPaint)
                val h = max(1f, (13 * (soc ?: 0.0) / 100).toFloat())
                fillPaint.alpha = 217
                canvas.drawRoundRect(8.5f, 7.5f + 13f - h, 15.5f, 20.5f, 1f, 1f, fillPaint)
                fillPaint.alpha = 255
            }
            NodeRole.CAR -> {
                canvas.drawPath(CAR_ICON, iconPaint)
                fillPaint.color = colours.card
                for (x in listOf(7.5f, 16.5f)) {
                    canvas.drawCircle(x, 16.8f, 1.9f, fillPaint)
                    canvas.drawCircle(x, 16.8f, 1.9f, iconPaint)
                }
            }
            null -> canvas.drawPath(HOUSE_ICON, iconPaint)
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val FRAME_MS = 33L
        /** A frame after a pause moves the chevrons no further than this. */
        const val MAX_FRAME_MS = 100L

        /** "M8 22 L12 3 L16 22 M9.2 16 H14.8 M10.2 11 H13.8 M5 7 H19 M7 7 L9.2 16 M17 7 L14.8 16" */
        val GRID_ICON = Path().apply {
            moveTo(8f, 22f); lineTo(12f, 3f); lineTo(16f, 22f)
            moveTo(9.2f, 16f); lineTo(14.8f, 16f)
            moveTo(10.2f, 11f); lineTo(13.8f, 11f)
            moveTo(5f, 7f); lineTo(19f, 7f)
            moveTo(7f, 7f); lineTo(9.2f, 16f)
            moveTo(17f, 7f); lineTo(14.8f, 16f)
        }

        /** "M3.5 15.5 V12.5 L6 7.5 Q6.6 6.5 7.8 6.5 H16.2 Q17.4 6.5 18 7.5 L20.5 12.5 V15.5 Q20.5 16.5 19.5 16.5 H4.5 Q3.5 16.5 3.5 15.5 Z M5 12.3 H19" */
        val CAR_ICON = Path().apply {
            moveTo(3.5f, 15.5f); lineTo(3.5f, 12.5f); lineTo(6f, 7.5f)
            quadTo(6.6f, 6.5f, 7.8f, 6.5f); lineTo(16.2f, 6.5f)
            quadTo(17.4f, 6.5f, 18f, 7.5f); lineTo(20.5f, 12.5f); lineTo(20.5f, 15.5f)
            quadTo(20.5f, 16.5f, 19.5f, 16.5f); lineTo(4.5f, 16.5f)
            quadTo(3.5f, 16.5f, 3.5f, 15.5f); close()
            moveTo(5f, 12.3f); lineTo(19f, 12.3f)
        }

        /** "M4 11 L12 4 L20 11 V20 H14.5 V14.5 H9.5 V20 H4 Z" */
        val HOUSE_ICON = Path().apply {
            moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); lineTo(20f, 20f); lineTo(14.5f, 20f)
            lineTo(14.5f, 14.5f); lineTo(9.5f, 14.5f); lineTo(9.5f, 20f); lineTo(4f, 20f); close()
        }
    }
}
