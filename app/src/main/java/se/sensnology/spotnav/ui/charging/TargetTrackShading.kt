package se.sensnology.spotnav.ui.charging

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.SeekBar

/**
 * The two shaded ends of the target slider's track: below the car's charge and above its limit,
 * painted over the track the `SeekBar` already has.
 */
internal class TargetTrackShading(private val colour: Int) : Drawable() {
    /** What is shaded, for the vehicle the card is currently about. */
    var shading: TargetShading.Shading = TargetShading.of(null)
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        if (width <= 0f) return
        val height = bounds.height().toFloat()
        val radius = height / 2f
        if (shading.below > 0f) {
            rect.set(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.left + shading.below * width, bounds.bottom.toFloat()
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        shading.above?.let { above ->
            if (above < 1f) {
                rect.set(
                    bounds.left + above * width, bounds.top.toFloat(),
                    bounds.right.toFloat(), bounds.bottom.toFloat()
                )
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Kept for the Drawable contract; the SeekBar's track is drawn opaquely over it.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
