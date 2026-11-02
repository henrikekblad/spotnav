package se.sensnology.spotnav.ui.charging

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chart.ChartTheme
import se.sensnology.spotnav.ui.common.ViewScope

/**
 * What the Plan card says while a slider is held: the plan Home Assistant would make if it were let go (webhook
 * `plan_preview`), as one line, its own status lines and a thin strip of the hours it chose from with the planned
 * quarters marked. Hidden while nothing is held; nothing here is saved.
 */
internal class PlanPreviewPanel(scope: ViewScope) : ViewScope(scope) {
    private val heading = TextView(context).apply {
        textSize = 12f
        setTextColor(muted)
        text = t(R.string.plan_preview_title)
    }
    private val line = TextView(context).apply {
        textSize = 15f
        setTextColor(dark)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, 0)
    }
    private val notes = TextView(context).apply {
        textSize = 13f
        setTextColor(muted)
        setPadding(0, dp(2), 0, 0)
    }
    private val strip = PlanStripView(this)
    private val ends = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val startText = TextView(context).apply { textSize = 11f; setTextColor(muted) }
    private val endText = TextView(context).apply { textSize = 11f; setTextColor(muted); gravity = android.view.Gravity.END }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(dp(1), accent)
            cornerRadius = dp(8).toFloat()
        }
        addView(heading)
        addView(line)
        addView(notes)
        addView(strip.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)).apply { topMargin = dp(6) })
        ends.addView(startText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ends.addView(endText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ends)
        visibility = View.GONE
    }

    /** Show [lines] and [strip]; [dimmed] while a newer answer is awaited. */
    fun show(lines: PlanPreviewText.Lines, stripModel: PlanPreviewText.Strip?, dimmed: Boolean) {
        line.text = lines.plan.orEmpty()
        line.visibility = if (lines.plan == null) View.GONE else View.VISIBLE
        notes.text = lines.notes.joinToString("\n") { it.text }
        notes.setTextColor(if (lines.notes.any { it.warning }) WARNING else muted)
        notes.visibility = if (lines.notes.isEmpty()) View.GONE else View.VISIBLE
        strip.show(stripModel)
        strip.view.visibility = if (stripModel == null) View.GONE else View.VISIBLE
        ends.visibility = strip.view.visibility
        startText.text = stripModel?.startLabel.orEmpty()
        endText.text = stripModel?.endLabel.orEmpty()
        view.alpha = if (dimmed) 0.5f else 1f
        view.visibility = if (lines.plan == null && lines.notes.isEmpty()) View.GONE else View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }

    private companion object {
        const val WARNING = 0xFFD65C5C.toInt()
    }
}

/** The strip: one cell per quarter, cheap or dear against its own day, the planned ones marked by a band under them. */
internal class PlanStripView(scope: ViewScope) : ViewScope(scope) {
    private var model: PlanPreviewText.Strip? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    val view: View = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val strip = model ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val band = h * 0.3f
            for (cell in strip.cells) {
                val left = cell.left * w
                val right = (cell.left + cell.width) * w
                paint.color = when (cell.cheap) {
                    true -> ChartTheme.Dark.cheap
                    false -> ChartTheme.Dark.expensive
                    null -> muted
                }
                paint.alpha = if (cell.planned) 255 else 110
                rect.set(left, 0f, maxOf(right, left + 1f), h - band - 2f)
                canvas.drawRect(rect, paint)
                if (cell.planned) {
                    paint.color = accent
                    paint.alpha = 255
                    rect.set(left, h - band, maxOf(right, left + 1f), h)
                    canvas.drawRect(rect, paint)
                }
            }
        }
    }

    fun show(strip: PlanPreviewText.Strip?) {
        model = strip
        view.invalidate()
    }
}
