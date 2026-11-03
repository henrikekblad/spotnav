package se.sensnology.spotnav.ui.history

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/** One key of the Power view's legend, drawn as the chart draws what it names. Made in code only. */
@SuppressLint("ViewConstructor")
internal class LegendSwatch(
    context: Context,
    private val key: PowerModel.LegendKey,
    private val colours: PowerBarsView.Colours
) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val dashes = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density; textAlign = Paint.Align.CENTER }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (key) {
            PowerModel.LegendKey.House -> { fill.color = colours.house; canvas.drawRect(0f, 0f, w, h, fill) }
            PowerModel.LegendKey.Car -> { fill.color = colours.car; canvas.drawRect(0f, 0f, w, h, fill) }
            PowerModel.LegendKey.Unsplit -> { fill.color = colours.unsplit; canvas.drawRect(0f, 0f, w, h, fill) }
            PowerModel.LegendKey.Target -> {
                line.color = colours.text
                line.pathEffect = dashes
                canvas.drawLine(0f, h / 2, w, h / 2, line)
            }
            is PowerModel.LegendKey.Counted -> {
                line.color = colours.text
                line.pathEffect = null
                canvas.drawRect(1f, 1f, w - 1f, h - 1f, line)
                text.color = colours.text
                canvas.drawText(key.number.toString(), w / 2, h / 2 + 3.5f * density, text)
            }
            PowerModel.LegendKey.Marks -> {
                fill.color = colours.text
                canvas.drawCircle(w / 4, h / 2, 2.5f * density, fill)
                line.color = colours.text
                line.pathEffect = null
                canvas.drawCircle(3 * w / 4, h / 2, 2.5f * density, line)
            }
        }
    }
}
