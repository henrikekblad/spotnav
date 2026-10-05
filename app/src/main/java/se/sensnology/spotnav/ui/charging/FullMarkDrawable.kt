package se.sensnology.spotnav.ui.charging

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The kWh slider's "full" mark: one thin vertical line across the track at the battery's room
 * ([fraction] of the track, 0..1, or `null` for none), reaching a little above and below it, painted
 * over the track the `SeekBar` already has (the card's `spotnav-settings-full-mark`).
 */
internal class FullMarkDrawable(colour: Int, private val lineWidth: Float, private val reach: Float) : Drawable() {
    var fraction: Float? = null
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        strokeWidth = lineWidth
    }

    override fun draw(canvas: Canvas) {
        val at = fraction ?: return
        if (bounds.width() <= 0) return
        val x = bounds.left + at * bounds.width()
        val middle = bounds.exactCenterY()
        val half = bounds.height() / 2f + reach
        canvas.drawLine(x, middle - half, x, middle + half, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Kept for the Drawable contract; the line is drawn over the track.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
