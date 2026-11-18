package se.sensnology.spotnav.ui.site

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/** A view that takes sideways drags of its own (a chart's scrub): a page swipe never begins on it. */
internal interface OwnsSidewaysDrag

/** The arithmetic of a page swipe, apart from the views. */
internal object PagerSwipe {
    /** A finger's move since it came down begins a swipe once it is past the slop and clearly sideways. */
    fun begins(dx: Float, dy: Float, slop: Int): Boolean = abs(dx) > slop && abs(dx) > abs(dy) * 2f

    /**
     * The page a released drag settles on: the next one in the fling's direction for a fast release, else the page
     * most of which is in view. [scrollX] is how far the strip is scrolled, [stride] one page's step, [velocity] the
     * finger's (px/s, positive to the right, which shows the page before).
     */
    fun settle(scrollX: Int, stride: Int, velocity: Float, minFling: Int, from: Int, count: Int): Int {
        if (stride <= 0 || count <= 0) return 0
        val nearest = (scrollX.toFloat() / stride).roundToInt()
        val target = when {
            velocity <= -minFling -> from + 1
            velocity >= minFling -> from - 1
            else -> nearest
        }
        // One page at a time, whatever the fling.
        return target.coerceIn(from - 1, from + 1).coerceIn(0, count - 1)
    }

    /** Where a drag may begin: only where nothing under the finger is something to press or drag itself. */
    fun allowedOn(chain: List<Touched>): Boolean = chain.none { it != Touched.PLAIN }

    /** What a view under the finger is to a swipe. */
    enum class Touched { PLAIN, PRESSED, DRAGGED }
}

/**
 * The main screen's pages side by side, one in view: the charger, the house, the heat. An own small ViewGroup (the
 * app has no AndroidX, so no ViewPager). A page is changed by the icon row ([show]) or by a sideways swipe, and a
 * swipe begins only on a header or on empty space: never on a slider, a chart, a switch, a field or anything that is
 * pressed ([swipeAllowed] is asked where the finger came down). With one page there is nothing to swipe.
 *
 * [gap] keeps the neighbours out of the margins the pager is drawn across.
 */
internal class PagerLayout(context: Context) : ViewGroup(context) {
    var gap: Int = 0
    var onPage: (Int) -> Unit = {}
    var page: Int = 0
        private set

    private val scroller = OverScroller(context)
    private val configuration = ViewConfiguration.get(context)
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var mayDrag = false
    private var dragging = false

    private fun stride(): Int = width + gap

    /** Go to [index], sliding there when [animate]. */
    fun show(index: Int, animate: Boolean) {
        val target = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != page
        page = target
        scroller.abortAnimation()
        if (animate && width > 0) {
            scroller.startScroll(scrollX, 0, target * stride() - scrollX, 0, SETTLE_MS)
            postInvalidateOnAnimation()
        } else {
            scrollTo(target * stride(), 0)
        }
        if (changed) onPage(target)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        for (index in 0 until childCount) {
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        for (index in 0 until childCount) {
            val start = index * (width + gap)
            getChildAt(index).layout(start, 0, start + width, bottom - top)
        }
        if (!dragging && scroller.isFinished) scrollTo(page.coerceAtMost((childCount - 1).coerceAtLeast(0)) * (width + gap), 0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(event)
                return false
            }
            MotionEvent.ACTION_MOVE -> if (startsDrag(event)) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
        }
        return dragging
    }

    @Suppress("ClickableViewAccessibility") // a swipe is no click; the pages change by the icon row too
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nothing under the finger took it: empty space of the pager itself.
                if (!mayDrag) begin(event)
                return mayDrag
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (!dragging && !startsDrag(event)) return mayDrag
                val last = (childCount - 1).coerceAtLeast(0) * stride()
                scrollTo((scrollX + (lastX - event.x)).roundToInt().coerceIn(0, last), 0)
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val velocity = tracker?.xVelocity ?: 0f
                    val target = PagerSwipe.settle(scrollX, stride(), velocity, configuration.scaledMinimumFlingVelocity * FLING_FACTOR, page, childCount)
                    end()
                    show(target, animate = true)
                } else end()
            }
            MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                end()
                if (was) show(page, animate = true)
            }
        }
        return true
    }

    private fun begin(event: MotionEvent) {
        downX = event.x
        downY = event.y
        lastX = event.x
        dragging = false
        mayDrag = childCount > 1 && swipeAllowed(event.rawX, event.rawY)
        tracker?.recycle()
        tracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun startsDrag(event: MotionEvent): Boolean {
        if (!mayDrag) return false
        tracker?.addMovement(event)
        if (dragging) return true
        if (!PagerSwipe.begins(event.x - downX, event.y - downY, configuration.scaledTouchSlop)) return false
        dragging = true
        lastX = event.x
        scroller.abortAnimation()
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun end() {
        dragging = false
        mayDrag = false
        tracker?.recycle()
        tracker = null
    }

    /** Whether a swipe may begin at this point of the screen: nothing under it in the page in view claims the finger. */
    private fun swipeAllowed(rawX: Float, rawY: Float): Boolean {
        val shown = getChildAt(page) ?: return false
        val chain = ArrayList<PagerSwipe.Touched>()
        collect(shown, rawX, rawY, chain)
        return PagerSwipe.allowedOn(chain)
    }

    /** Every view under the point, from [view] down to the deepest, as what it is to a swipe. */
    private fun collect(view: View, rawX: Float, rawY: Float, into: MutableList<PagerSwipe.Touched>) {
        if (view.visibility != VISIBLE) return
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        if (rawX < at[0] || rawX >= at[0] + view.width || rawY < at[1] || rawY >= at[1] + view.height) return
        into.add(touched(view))
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) collect(view.getChildAt(index), rawX, rawY, into)
        }
    }

    private fun touched(view: View): PagerSwipe.Touched = when {
        view is OwnsSidewaysDrag || view is AbsSeekBar || view is HorizontalScrollView -> PagerSwipe.Touched.DRAGGED
        view is EditText || view is CompoundButton || view.isClickable || view.isLongClickable -> PagerSwipe.Touched.PRESSED
        else -> PagerSwipe.Touched.PLAIN
    }

    private companion object {
        const val SETTLE_MS = 260
        /** A fling changes the page from a few times the platform's least fling. */
        const val FLING_FACTOR = 4
    }
}
