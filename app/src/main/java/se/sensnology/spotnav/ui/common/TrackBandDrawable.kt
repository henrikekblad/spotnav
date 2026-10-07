package se.sensnology.spotnav.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * One part of a slider's track, [from] to [to] (fractions of it, 0..1; `null` [to] draws nothing), painted
 * over the track the `SeekBar` already has: solid in [colour], or with [stripes] hatched over it in diagonal
 * lines [stripeWidth] apart. Rounded only at the track's own ends.
 */
internal class TrackBandDrawable(
    colour: Int,
    private val stripes: Int? = null,
    private val stripeWidth: Float = 0f
) : Drawable() {
    var from: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }
    var to: Float? = null
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stripes ?: 0
        strokeWidth = stripeWidth
    }
    private val rect = RectF()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        val end = to ?: return
        val width = bounds.width().toFloat()
        if (width <= 0f || end <= from) return
        val left = bounds.left + from.coerceIn(0f, 1f) * width
        val right = bounds.left + end.coerceIn(0f, 1f) * width
        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()
        val radius = bounds.height() / 2f
        val radii = floatArrayOf(
            if (from <= 0f) radius else 0f, if (from <= 0f) radius else 0f,
            if (end >= 1f) radius else 0f, if (end >= 1f) radius else 0f,
            if (end >= 1f) radius else 0f, if (end >= 1f) radius else 0f,
            if (from <= 0f) radius else 0f, if (from <= 0f) radius else 0f
        )
        rect.set(left, top, right, bottom)
        clip.reset()
        clip.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(clip, paint)
        if (stripes != null && stripeWidth > 0f) {
            val save = canvas.save()
            canvas.clipPath(clip)
            val height = bottom - top
            var x = left - height
            while (x < right) {
                canvas.drawLine(x, bottom, x + height, top, stripePaint)
                x += stripeWidth * 2f
            }
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        stripePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        stripePaint.colorFilter = colorFilter
    }

    @Deprecated("Kept for the Drawable contract; the band is drawn over the track.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** [colour] darkened toward black by [amount] (0..1), keeping its alpha: the fill's own darker tone. */
internal fun darker(colour: Int, amount: Float): Int {
    val keep = 1f - amount.coerceIn(0f, 1f)
    val r = ((colour shr 16 and 0xFF) * keep).toInt()
    val g = ((colour shr 8 and 0xFF) * keep).toInt()
    val b = ((colour and 0xFF) * keep).toInt()
    return (colour and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
}

/** One more layer of a slider's track, drawn in the rect the track paints itself in (see the target slider). */
internal fun addTrackLayer(seek: android.widget.SeekBar, layer: Drawable, height: Int): Boolean {
    val layers = seek.progressDrawable as? android.graphics.drawable.LayerDrawable ?: return false
    val insets = android.graphics.Rect()
    layers.getPadding(insets)
    val index = layers.addLayer(layer)
    layers.setLayerInset(index, insets.left, 0, insets.right, 0)
    layers.setLayerHeight(index, height)
    layers.setLayerGravity(index, android.view.Gravity.FILL_HORIZONTAL or android.view.Gravity.CENTER_VERTICAL)
    seek.progressDrawable = layers
    return true
}
