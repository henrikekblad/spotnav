package se.sensnology.spotnav.chart

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceResult
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * What one graph would be drawn from, and at what size. The view keeps the request it last drew and
 * re-renders only when this differs, so equality is the invalidation rule and every fact that can change
 * a pixel or a readout is in it. "No shading" is an empty [bands] list (no `showPlan` flag). [footer] is
 * part of the identity because equal bands do not imply an equal plan (see [ChartFooterPlan]).
 */
internal data class ChartRequest(
    val profile: ChartProfile,
    val market: ChartMarket,
    val result: PriceResult,
    /** The shading, already normalized (see [ChartBand]); empty means nothing is shaded. */
    val bands: List<ChartBand>,
    /** The plan's facts for the footer, or `null`; part of the request because it changes the picture. */
    val footer: ChartFooterPlan? = null,
    val widthPx: Int,
    val heightPx: Int,
    /**
     * The selection line's minute of the day, or `null` for no line. Excluded from the frame a selection
     * is validated against (see `ChartSelection.stillValid`), so a fresh selection cannot invalidate itself.
     */
    val selectionMinute: Float? = null,
    /**
     * The current interval's mark as a minute of the day (see `ChartNow.currentMarkMinute`), or `null` when
     * no row of today contains the frame's instant. The clock is read once, where this value is built, so
     * two draws of one request are one picture.
     */
    val nowMinute: Float? = null
)

/**
 * When the plan card can draw the widget's own graph, and how big it is. Android-free: no graph is
 * rendered before there is a measured width, and empty price data still produces a request so the
 * "nothing fetched" state is drawn rather than a zero-height hole.
 */
internal object ChartRequests {
    /** The widget canvas' own 420x220 shape. */
    const val WIDGET_HEIGHT_PER_WIDTH = 220f / 420f

    /** The plan card's shape, matching how the widget is displayed (about 0.41), so marks and text keep the widget's proportions. */
    const val PLAN_CARD_HEIGHT_PER_WIDTH = 0.41f

    /** The width below which there is nothing to draw in; a width of 0 means the view is not laid out yet. */
    const val MIN_WIDTH_PX = 180

    /** The graph for no prices yet; shared so a re-render does not look like a change. */
    private val NOTHING_FETCHED = PriceResult(emptyList(), emptyList(), 0L)

    /** The height per width a graph is drawn at, per profile: the one place they differ. */
    fun heightPerWidth(profile: ChartProfile): Float = when (profile) {
        ChartProfile.WIDGET -> WIDGET_HEIGHT_PER_WIDTH
        ChartProfile.PLAN_CARD -> PLAN_CARD_HEIGHT_PER_WIDTH
    }

    /** The height a graph [widthPx] wide is drawn at, or 0 before there is a width. */
    fun heightFor(widthPx: Int, profile: ChartProfile): Int =
        if (widthPx < MIN_WIDTH_PX) 0 else (widthPx * heightPerWidth(profile)).roundToInt()

    /**
     * The request for these inputs, or `null` while there is no width to draw into. `null` [prices] become
     * [NOTHING_FETCHED], so the card shows the empty state from the first frame.
     */
    fun of(
        market: ChartMarket,
        bands: List<ChartBand>,
        prices: PriceResult?,
        widthPx: Int,
        profile: ChartProfile,
        footer: ChartFooterPlan? = null,
        selectionMinute: Float? = null,
        nowMinute: Float? = null
    ): ChartRequest? {
        val height = heightFor(widthPx, profile)
        return if (height <= 0) null
        else ChartRequest(
            profile, market, prices ?: NOTHING_FETCHED, bands, footer, widthPx, height, selectionMinute, nowMinute
        )
    }
}

/**
 * What the view is showing: exactly the facts a request compares, minus the profile and the size, which
 * are the view's own dimensions at draw time.
 */
internal data class ShownChart(
    val market: ChartMarket,
    val bands: List<ChartBand>,
    val footer: ChartFooterPlan?,
    val prices: PriceResult?
)

/**
 * The widget's graph inside the charging-plan card: the same [ChartRenderer] and the same prices the plan
 * was computed from, so the two cannot drift.
 *
 * The bitmap is drawn at the view's measured size, replaced (and the previous one released) only when the
 * request changed, and dropped on detach. A selection is part of the request; what it is validated
 * against is the frame without the line. The finger selects by tap or by a sideways scrub (see
 * [ChartScrub] and [onTouchEvent]); any other drag belongs to the page.
 */
internal class PlanChartView(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    /** What is on screen, selection line included. */
    private var lastRequest: ChartRequest? = null
    /** The frame on screen without the line: what a selection is made on. */
    private var frameRequest: ChartRequest? = null
    /** What this graph is showing, or `null` when it has nothing to draw (see [showUnavailable]). */
    private var chart: ShownChart? = null
    private var frame: Bitmap? = null
    private var plot: ChartMetrics? = null
    private var selection: ChartReadout? = null
    private var selectionMadeOn: ChartRequest? = null

    /** The clock the current interval is read from, once per draw at the request boundary; injectable for tests (see `ChartNow`). */
    var now: () -> OffsetDateTime = { OffsetDateTime.now() }

    /** The one pending boundary redraw: replaced on every draw, cancelled on detach (see [ChartBoundaryRefresh]). */
    private val boundaryRefresh = ChartBoundaryRefresh(
        post = { runnable, delay -> postDelayed(runnable, delay) },
        cancel = { runnable -> removeCallbacks(runnable) }
    )

    /** The gesture in flight (see [ChartScrub]) and where it went down, kept across `ACTION_MOVE`s. */
    private var gesture = ChartGesture.UNDECIDED
    private var downX = 0f
    private var downY = 0f

    /**
     * Told when the selection changes, with the inputs it was made against; the card owns the localized
     * words for it.
     */
    var onSelectionChanged: ((ChartReadout?, ChartMarket, PriceResult) -> Unit)? = null

    private companion object {
        /** The in-app chart is always the plan-card profile (denser text, no footer). */
        val PROFILE = ChartProfile.PLAN_CARD
    }

    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // Focusable and clickable so a screen reader can reach it.
        isClickable = true
        isFocusable = true
    }

    /** Shows what these inputs draw; called from the screen's render, where the generation guard lives. */
    fun show(market: ChartMarket?, bands: List<ChartBand>, footer: ChartFooterPlan?, prices: PriceResult?) {
        if (market == null) {
            showUnavailable()
            return
        }
        chart = ShownChart(market, bands, footer, prices)
        draw()
    }

    /**
     * Nothing to draw from yet (for example an unresolved area, see `LocalPlanningInputs.ofOrNull`): shows
     * the renderer's placeholder and no selection.
     */
    private fun showUnavailable() {
        // Nothing to wait for: cancel the boundary appointment now.
        boundaryRefresh.cancel()
        chart = null
        lastRequest = null
        frameRequest = null
        plot = null
        selection = null
        selectionMadeOn = null
        val previous = frame
        val drawn = ChartRenderer.emptyState(context, width, ChartRequests.heightFor(width, PROFILE))
        frame = drawn
        setImageBitmap(drawn)
        previous?.recycle()
    }

    /** Width from the parent, height from the width, so the card cannot ask for a shape the graph would be stretched into. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = ChartRequests.heightFor(width, PROFILE).coerceAtLeast(suggestedMinimumHeight)
        // The card asks for wrap_content, so the shape is ours; an exact spec still wins.
        val exact = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        setMeasuredDimension(width, if (exact) MeasureSpec.getSize(heightMeasureSpec) else height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The first layout pass is where the width exists; `of()` refuses before it.
        draw()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Re-attached after a detach released the frame: redraw, which also re-arms the boundary.
        if (lastRequest == null) draw()
    }

    private fun draw() {
        val shown = chart ?: return
        val market = shown.market
        val bands = shown.bands
        val footer = shown.footer
        val prices = shown.prices
        // The frame a selection is made on and validated against: without the selection line, so a fresh selection cannot invalidate itself.
        val drawnOn = ChartRequests.of(market, bands, prices, width, PROFILE, footer) ?: return
        // A selection belongs to the frame it was made on; changed data, size or profile drop it.
        if (!ChartSelection.stillValid(selection, selectionMadeOn, drawnOn)) {
            val hadSelection = selection != null
            selection = null
            selectionMadeOn = null
            // The card owns the words for a selection, so it is told the words are gone.
            if (hadSelection) prices?.let { onSelectionChanged?.invoke(null, market, it) }
        }
        // The current mark is read from the clock here and carried in the request; left out of `drawnOn`
        // like the selection, since an interval passing is not the data moving. `null` prices mean no line.
        val nowMinute = prices?.let { ChartNow.currentMarkMinute(market, it, now()) }
        val request = ChartRequests.of(
            market, bands, prices, width, PROFILE, footer, selection?.markMinute, nowMinute
        ) ?: return
        // Unchanged request: the frame on screen is this one.
        if (request == lastRequest) return
        lastRequest = request
        frameRequest = drawnOn
        val drawn = ChartRenderer.renderFrame(
            context, request.widthPx, request.heightPx, oneSp(), request.market, request.result,
            request.bands, request.footer, request.profile, request.selectionMinute, request.nowMinute
        )
        plot = drawn.metrics
        val previous = frame
        frame = drawn.bitmap
        setImageBitmap(drawn.bitmap)
        // Set first, released second.
        previous?.recycle()
        // One appointment at the end of the interval the line identifies.
        armBoundary()
    }

    /** Arms the single boundary redraw, or cancels it when no row of today contains now. */
    private fun armBoundary() {
        val shown = chart
        val delay = shown?.prices?.let { prices ->
            ChartNow.untilNextIntervalMillis(shown.market, prices, now())
        }
        boundaryRefresh.arm(delay) {
            // Redraw first, then re-arm: the draw may be a no-op, and an ended interval still needs its successor scheduled.
            draw()
            armBoundary()
        }
    }

    /** Puts the selection away; the readout is not a mode. */
    fun clearSelection() {
        if (selection == null) return
        chart?.let { shown -> if (shown.prices != null) apply(null, shown.market, shown.prices) }
    }

    /** Whether this graph is showing a selection (see `ChartDismissal`). */
    val hasSelection: Boolean get() = selection != null

    /**
     * A tap picks the nearest interval (outside the plot, or on the same mark again, it clears), and a
     * sideways drag scrubs. [ChartScrub] decides which after the touch slop and keeps the decision; a
     * sideways drag takes the gesture from the page, a downward one leaves everything to it. The click is
     * emitted via `super.performClick()` so a coordinate tap does not also advance (see [performClick]).
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Remembered, not acted on: the movement that follows decides.
                downX = event.x
                downY = event.y
                gesture = ChartGesture.UNDECIDED
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val wasScrubbing = gesture == ChartGesture.SCRUB
                gesture = ChartScrub.onMove(gesture, event.x - downX, event.y - downY, touchSlop())
                if (gesture == ChartGesture.SCRUB) {
                    // An established scrub owns the gesture, so the page must not take it back on drift.
                    if (!wasScrubbing) parent?.requestDisallowInterceptTouchEvent(true)
                    scrubTo(event.x)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val scrubbed = gesture == ChartGesture.SCRUB
                gesture = ChartGesture.UNDECIDED
                // A scrub ends where the finger left it; only a tap runs the tap path.
                if (!scrubbed) {
                    super.performClick()
                    select(event.x, event.y)
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                // The page took the gesture: leave the selection as it was.
                gesture = ChartGesture.UNDECIDED
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    /**
     * The scrub's selection: the mark nearest the finger's x, clamped into the plot. Does nothing when
     * that mark is already chosen, so a drag costs one bitmap per mark, not per touch event.
     */
    private fun scrubTo(x: Float) {
        val metrics = plot ?: return
        val shown = chart ?: return
        if (shown.prices == null) return
        val readout = ChartSelection.nearest(
            ChartScrub.clampedX(x, metrics.left, metrics.right), metrics, shown.market, shown.prices
        ) ?: return
        if (readout == selection) return
        apply(readout, shown.market, shown.prices)
    }

    /** The platform's touch slop. */
    private fun touchSlop() = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /**
     * Activation (TalkBack double tap, keyboard enter) moves to the next displayed position, wrapping
     * around: the non-spatial route for those who cannot make a coordinate tap. Repeated occurrences share
     * one position (see `ChartSelection.positions`).
     */
    override fun performClick(): Boolean {
        super.performClick()
        val shown = chart ?: return true
        if (shown.prices == null) return true
        val next = ChartSelection.next(selection?.time, ChartSelection.positions(shown.market, shown.prices)) ?: return true
        apply(ChartSelection.readoutAt(next, shown.market, shown.prices), shown.market, shown.prices)
        return true
    }

    private fun select(x: Float, y: Float) {
        val metrics = plot
        val shown = chart ?: return
        if (metrics == null || shown.prices == null) return
        // Only inside the plot: header, axis labels and the card around it clear the selection.
        if (y < metrics.top || y > metrics.bottom) {
            clearSelection()
            return
        }
        val readout = ChartSelection.nearest(x, metrics, shown.market, shown.prices)
        if (readout != null && readout == selection) {
            clearSelection()
            return
        }
        apply(readout, shown.market, shown.prices)
    }

    /** Records a selection, tells the card, and redraws, since the line is part of the picture. */
    private fun apply(readout: ChartReadout?, market: ChartMarket, prices: PriceResult) {
        selection = readout
        selectionMadeOn = if (readout == null) null else frameRequest
        onSelectionChanged?.invoke(readout, market, prices)
        draw()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A detach pauses the boundary appointment; `dispose` is terminal and would stop scheduling after a reattach.
        boundaryRefresh.cancel()
        // Off screen: drop the full-size bitmap.
        lastRequest = null
        frameRequest = null
        val previous = frame
        frame = null
        setImageBitmap(null)
        previous?.recycle()
    }

    /** One scaled pixel via `TypedValue` (`scaledDensity` is deprecated), keeping text the same physical size as on the home screen. */
    private fun oneSp() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
}
