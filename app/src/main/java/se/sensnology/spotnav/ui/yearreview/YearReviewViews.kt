package se.sensnology.spotnav.ui.yearreview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Whether anything may move: off when the system's animation scale is 0 ("Remove animations"). */
internal fun motionAllowed(): Boolean = ValueAnimator.areAnimatorsEnabled()

/** The chips: laid out in rows, wrapping where a row is full (the card's `flex-wrap`). */
internal class ChipRow(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            x += child.measuredWidth + gap
            rowHeight = max(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = max(rowHeight, child.measuredHeight)
        }
    }
}

/**
 * Fireworks behind the cover and the thanks page (the card's `year-review-fx.ts`): rockets that rise and burst into
 * sparks. [start] and [stop]; nothing at all when motion is off. A frame is asked for only while it runs and the view
 * is attached.
 */
internal class FireworksView(context: Context, private val density: Float) : View(context) {
    private class Rocket(val x: Float, var y: Float, var vy: Float, val colour: Int)
    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val colour: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rockets = mutableListOf<Rocket>()
    private val sparks = mutableListOf<Spark>()
    private var lastLaunch = Long.MIN_VALUE / 2
    private val random = java.util.Random()

    var running = false
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start() {
        if (running || !motionAllowed()) return
        running = true
        lastLaunch = Long.MIN_VALUE / 2
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        rockets.clear()
        sparks.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        // The card's figures are CSS px a frame; the same motion here in dp.
        val d = density
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch > LAUNCH_EVERY_MS) {
            rockets += Rocket(width * (0.15f + random.nextFloat() * 0.7f), height.toFloat(), -(5.5f + random.nextFloat() * 2.5f) * d, COLOURS[random.nextInt(COLOURS.size)])
            lastLaunch = now
        }
        val rocketIterator = rockets.iterator()
        while (rocketIterator.hasNext()) {
            val rocket = rocketIterator.next()
            rocket.y += rocket.vy
            rocket.vy += 0.07f * d
            paint.color = rocket.colour
            paint.alpha = 255
            canvas.drawRect(rocket.x - 1.5f * d, rocket.y, rocket.x + 1.5f * d, rocket.y + 7 * d, paint)
            if (rocket.vy > -1.2f * d) {
                repeat(SPARKS) {
                    val angle = random.nextDouble() * PI * 2
                    val speed = (1 + random.nextDouble() * 3.2) * d
                    sparks += Spark(rocket.x, rocket.y, (cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat(), 1f, rocket.colour)
                }
                rocketIterator.remove()
            }
        }
        val sparkIterator = sparks.iterator()
        while (sparkIterator.hasNext()) {
            val spark = sparkIterator.next()
            spark.x += spark.vx
            spark.y += spark.vy
            spark.vy += 0.045f * d
            spark.vx *= 0.985f
            spark.life -= 0.014f
            if (spark.life <= 0) {
                sparkIterator.remove()
                continue
            }
            paint.color = spark.colour
            paint.alpha = (spark.life * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(spark.x, spark.y, 1.8f * d, paint)
        }
        if (running && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private companion object {
        const val LAUNCH_EVERY_MS = 650L
        const val SPARKS = 46
        val COLOURS = listOf("#ffe066", "#ec6fb5", "#7ff0de", "#9ab8ff", "#ffffff", "#ffb36b").map(Color::parseColor)
    }
}
