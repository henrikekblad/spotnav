package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ui.common.EnergyPalette

/**
 * The Energy flow's Sankey diagram, drawn from [SankeyLayout] as the Home Assistant card draws it
 * (`frontend/src/house-flow.ts`): a block per source and use with its name and share in a lighter pill, its kWh under
 * it (big in a tall block, with the battery's level at its bottom), and a band of the two blocks' colours between each
 * pair. The house block, and the heat block where it holds more than one source, open their pie when tapped
 * ([onOpen] with `house` or `heat`). Sizes are laid out in density-independent pixels and drawn scaled.
 */
@SuppressLint("ViewConstructor") // built in code with the page's words: there is nothing to inflate
internal class SankeyView(context: Context, private val text: SiteText, private val energy: EnergyPalette) : View(context) {
    private var flow: House.EnergyFlow? = null
    private var past = false
    private var layout: SankeyLayout.Layout? = null
    private var laidFor = 0.0
    private var description = ""
    private var openLabels: Map<String, String> = emptyMap()
    var onOpen: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = 158 }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    private val bigLong = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 12f }

    /** Draw [next]; [isPast] says the battery's level is the one at the period's end, not now. */
    fun show(next: House.EnergyFlow, isPast: Boolean, labels: Map<String, String>) {
        flow = next
        past = isPast
        openLabels = labels
        laidFor = 0.0
        layout = null
        contentDescription = null
        requestLayout()
        invalidate()
    }

    private fun total(f: House.EnergyFlow) = SankeyLayout.shown(f).filter { it.source }.sumOf { it.kwh }

    private fun ensureLayout(widthDp: Double): SankeyLayout.Layout? {
        val f = flow ?: return null
        if (layout != null && laidFor == widthDp) return layout
        val total = total(f)
        // Names are measured as drawn (the pill's 12 dp semi-bold), in dp as the canvas is scaled.
        val layoutNow = SankeyLayout.layout(
            f, widthDp, SankeyLayout.flowHeight(widthDp, 5).toDouble().coerceAtMost(SankeyLayout.TAB_FLOW_PX),
            { node -> FlowModel.pillWords(text, node, total) }, { words -> name.measureText(words).toDouble() }
        )
        laidFor = widthDp
        layout = layoutNow
        description = layoutNow?.blocks?.joinToString(", ") {
            "${FlowModel.role(text, it.node.role)} ${FlowModel.kwh(text, it.node.kwh)} (${FlowModel.share(text, it.node.kwh, total)})"
        }.orEmpty()
        contentDescription = description
        return layoutNow
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val laid = ensureLayout(width / density.toDouble())
        setMeasuredDimension(width, if (laid == null) 0 else (laid.height * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val laid = ensureLayout(width / density.toDouble()) ?: return
        val f = flow ?: return
        val total = total(f)
        canvas.save()
        canvas.scale(density, density)
        for (band in laid.bands) {
            val path = Path().apply {
                val mid = ((laid.x0 + laid.x1) / 2).toFloat()
                moveTo(laid.x0.toFloat(), band.a0.toFloat())
                cubicTo(mid, band.a0.toFloat(), mid, band.b0.toFloat(), laid.x1.toFloat(), band.b0.toFloat())
                lineTo(laid.x1.toFloat(), band.b1.toFloat())
                cubicTo(mid, band.b1.toFloat(), mid, band.a1.toFloat(), laid.x0.toFloat(), band.a1.toFloat())
                close()
            }
            fill.shader = LinearGradient(
                laid.x0.toFloat(), 0f, laid.x1.toFloat(), 0f, energy.flowBlock(band.from.node.role, band.from.node.source), energy.flowBlock(band.to.node.role, band.to.node.source), Shader.TileMode.CLAMP
            )
            fill.alpha = 140
            canvas.drawPath(path, fill)
        }
        fill.shader = null
        fill.alpha = 255
        for (block in laid.blocks) drawBlock(canvas, block, total, laid)
        canvas.restore()
    }

    private fun drawBlock(canvas: Canvas, block: SankeyLayout.Block, total: Double, laid: SankeyLayout.Layout) {
        val rect = RectF(block.x.toFloat(), block.y.toFloat(), (block.x + block.w).toFloat(), (block.y + block.h).toFloat())
        fill.color = energy.flowBlock(block.node.role, block.node.source)
        canvas.drawRoundRect(rect, 6f, 6f, fill)
        // A soft light from the top over the block's own colour.
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, intArrayOf(0x52FFFFFF, 0x00FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, 6f, 6f, fill)
        fill.shader = null
        val pad = SankeyLayout.PAD.toFloat()
        val short = block.h < SankeyLayout.TALL_NODE
        val top = (if (short) block.y + (block.h - (SankeyLayout.PILL_H + 4 + 12)) / 2 else block.y + pad).toFloat()
        val h = SankeyLayout.PILL_H.toFloat()
        canvas.drawRoundRect(RectF(rect.left + pad, top, rect.right - pad, top + h), h / 2, h / 2, pill)
        val words = FlowModel.pillWords(text, block.node, total)
        val room = (block.w - 2 * pad - 2 * SankeyLayout.PILL_PAD).toFloat()
        val wide = name.measureText(words)
        name.textAlign = Paint.Align.CENTER
        name.textScaleX = if (wide > room && wide > 0) room / wide else 1f
        canvas.drawText(words, (rect.centerX()), top + h - 5, name)
        name.textScaleX = 1f
        name.textAlign = Paint.Align.LEFT
        val kwh = FlowModel.kwh(text, block.node.kwh)
        if (short) {
            canvas.drawText(kwh, rect.left + pad + 1, top + h + 4 + 10, small)
        } else {
            canvas.drawText(kwh, rect.left + pad + 1, top + h + 19, if (block.node.kwh >= 1000) bigLong else big)
            block.node.socPct?.let { soc ->
                val key = if (past) "house.flow.socEnd" else "house.flow.socNow"
                canvas.drawText(text.words.say(key, "pct" to text.percent(soc)), rect.left + pad + 1, rect.bottom - pad - 1, small)
            }
        }
    }

    private fun opens(block: SankeyLayout.Block): String? = flow?.let { FlowModel.opens(it, block.node) }

    private fun hit(x: Float, y: Float): String? {
        val laid = layout ?: return null
        val px = x / density
        val py = y / density
        return laid.blocks.firstOrNull { px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }?.let(::opens)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = hit(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return target != null
            MotionEvent.ACTION_UP -> if (target != null) {
                performClick()
                onOpen?.invoke(target)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    // A screen reader reaches the pies by actions on the diagram, as there are no child views.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        for ((index, kind) in ACTION_KINDS.withIndex()) {
            val label = openLabels[kind] ?: continue
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_BASE + index, label))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val kind = ACTION_KINDS.getOrNull(action - ACTION_BASE)
        if (kind != null && openLabels.containsKey(kind)) {
            onOpen?.invoke(kind)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private companion object {
        const val INK = 0xFF15151C.toInt()
        const val ACTION_BASE = 0x5A4E0000
        val ACTION_KINDS = listOf("house", "heat")
    }
}
