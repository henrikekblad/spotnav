package se.sensnology.spotnav.ui.charging

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The charger card's slim progress bar: a rounded track, the share done in the accent colour, and
 * faint diagonal stripes on it that drift slowly while current flows. With no share known (a
 * person's Start with no level) a short stretch of the accent glides along the track instead. Still,
 * the stripes stand; with animations removed in the system's settings nothing ever moves.
 */
internal class ChargeBarView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val barHeight = 6f * density
    private val stripeSpacing = 10f * density
    private val stripeWidth = 4f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        alpha = STRIPE_ALPHA
        strokeWidth = stripeWidth
    }
    private val bounds = RectF()
    private val shape = RectF()
    private val clip = Path()

    /** The share done, 0..1, or `null` for the open bar. */
    private var fraction: Float? = null
    private var moving = false

    /** 0..1 through one cycle of the drift (the stripes' one spacing, or the open bar's one pass). */
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** The track's and the share's colours, from the screen's palette. */
    fun colours(track: Int, fill: Int) {
        trackPaint.color = track
        fillPaint.color = fill
        invalidate()
    }

    /** Show [fraction] (`null`: the open bar), moving only when [animate] (see [ChargeBarMotion]). */
    fun show(fraction: Float?, animate: Boolean) {
        this.fraction = fraction?.coerceIn(0f, 1f)
        if (animate != moving) {
            moving = animate
            phase = 0f
        }
        syncAnimator()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((barHeight + paddingTop + paddingBottom).toInt(), heightMeasureSpec)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncAnimator()
    }

    /** One animator while the bar moves and can be seen, none otherwise. */
    private fun syncAnimator() {
        val wanted = moving && isAttachedToWindow && visibility == VISIBLE && windowVisibility == VISIBLE
        if (!wanted) return stopAnimator()
        val open = fraction == null
        val duration = if (open) OPEN_PASS_MS else STRIPE_DRIFT_MS
        val running = animator
        if (running != null && running.duration == duration) return
        running?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val top = paddingTop + (height - paddingTop - paddingBottom - barHeight) / 2f
        bounds.set(paddingLeft.toFloat(), top, (width - paddingRight).toFloat(), top + barHeight)
        if (bounds.width() <= 0f) return
        val radius = barHeight / 2f
        canvas.drawRoundRect(bounds, radius, radius, trackPaint)
        val share = fraction
        if (share != null) {
            if (share <= 0f) return
            shape.set(bounds.left, bounds.top, bounds.left + bounds.width() * share, bounds.bottom)
            fillPaint.alpha = 255
        } else if (moving) {
            // A stretch a third of the track long, entering at the left and leaving at the right.
            val length = bounds.width() / 3f
            val start = bounds.left - length + (bounds.width() + length) * phase
            shape.set(maxOf(bounds.left, start), bounds.top, minOf(bounds.right, start + length), bounds.bottom)
            if (shape.width() <= 0f) return
            fillPaint.alpha = 255
        } else {
            // Still: the whole track, softly, so the open bar still says "a charge is on".
            shape.set(bounds)
            fillPaint.alpha = STILL_OPEN_ALPHA
        }
        clip.reset()
        clip.addRoundRect(shape, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(shape, fillPaint)
        drawStripes(canvas, if (share != null && moving) phase else 0f)
        canvas.restore()
    }

    /** Diagonal stripes across the clipped fill, shifted [shift] of one spacing to the right. */
    private fun drawStripes(canvas: Canvas, shift: Float) {
        val rise = shape.height()
        var x = shape.left - rise - stripeSpacing + shift * stripeSpacing
        while (x < shape.right + rise) {
            canvas.drawLine(x, shape.bottom, x + rise, shape.top, stripePaint)
            x += stripeSpacing
        }
    }

    private companion object {
        /** One stripe spacing per this many ms: slow enough to read as calm, quick enough to notice. */
        const val STRIPE_DRIFT_MS = 1400L

        /** The open bar's one pass along the track. */
        const val OPEN_PASS_MS = 2400L

        const val STRIPE_ALPHA = 0x40
        const val STILL_OPEN_ALPHA = 0x80
    }
}
