package se.sensnology.spotnav.ui

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.chart.ChartDismissal
import se.sensnology.spotnav.chart.PlanChartView
import se.sensnology.spotnav.chart.ViewBounds
import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.prices.PriceScreenRefresh
import se.sensnology.spotnav.ui.common.Palette
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.addPanelHeader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The panel shell every screen is drawn into, and the state the screens share for as long as the
 * Activity lives: chrome above, one scrolling body below, the screen generation, the io executor.
 */
internal class ScreenShell(
    activity: Activity,
    palette: Palette,
    val widgetId: Int,
    val existingWidget: Boolean,
    /** Go to another screen: the Activity's own choice, the one place a screen is picked. */
    val navigate: (Screen) -> Unit,
    /** What the Back action in a panel header does: exactly what Android's own Back would. */
    val back: () -> Unit
) : ViewScope(activity, palette) {
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** The whole page: chrome above the scrolling body, padded off the status bar. */
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(20))
        setBackgroundColor(palette.appBackground)
        setOnApplyWindowInsetsListener { _, insets ->
            setPadding(dp(20), insets.systemWindowInsetTop + dp(8), dp(20), dp(20))
            insets
        }
    }

    // The panel shell: chrome above, one scrolling body below.
    val chrome = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val scrollView = ScrollView(activity).apply { addView(content) }

    /**
     * The screen generation: late async work checks it before touching the views, so a load started
     * for a screen the user has left cannot fill the next one.
     */
    var generation = 0
        private set

    /** The screen's interest in a widget publication refresh, and the guarded reload behind it. */
    val priceRefresh = PriceScreenRefresh(
        reload = { reloadPrices?.invoke() },
        currentArea = { authorityController?.priceArea() },
        built = { reloadPrices != null }
    )

    /** The charging screen's own price-load path, while that screen is the one on display. */
    var reloadPrices: (() -> Unit)? = null

    /**
     * What the charging screen does when the app comes back to the foreground: draw again from what it
     * holds (cut by the local clock) and ask for a fresh dashboard now. Cleared with [reloadPrices].
     */
    var onForeground: (() -> Unit)? = null

    /**
     * The charging screen's day change: what it does at the next local midnight of its market while the app
     * stays open, and which zone that is. The Activity keeps the timer, armed while it is resumed and
     * cancelled when it stops; cleared with [reloadPrices].
     */
    var onDayBoundary: (() -> Unit)? = null

    /**
     * The charging screen's re-read while it is in view (`LiveRefresh`), and the end Home Assistant
     * named for a passing state of its own; the Activity keeps the timer, armed while it is resumed.
     * Cleared with [reloadPrices].
     */
    var onLiveRefresh: (() -> Unit)? = null
    var liveRefreshEnd: (() -> java.time.Instant?)? = null

    /** Whether a Start or Stop awaits the charger's report, so the re-read runs every few seconds. */
    var liveRefreshFast: (() -> Boolean)? = null

    /** Set by the Activity: the charging screen calls it when an answer arrives, so a sooner end is kept. */
    var liveRefreshReconsider: (() -> Unit)? = null
    var dayZone: (() -> java.time.ZoneId)? = null

    /** The settings screen's answer to the Android 13+ notification prompt; cleared with [reloadPrices]. */
    var onNotificationPermission: (() -> Unit)? = null

    /**
     * a tap that begins anywhere else puts the readout away (see [afterDispatch] and
     * ChartDismissal). Both are dropped by [begin], which is what happens whenever the screen is
     * rebuilt -- so nothing here outlives the views it points at.
     */
    var planChart: PlanChartView? = null
    var planCallout: TextView? = null

    /** This screen's authority controller for the generation on screen. */
    var authorityController: AuthorityController? = null

    // The screen generation a gesture in flight began on, when that gesture is one that must put
    // the readout away when it ends; null while the gesture began inside the graph or its readout,
    // while there was nothing to put away, and after the gesture is over.
    private var dismissGesture: Int? = null

    init {
        root.addView(chrome, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Start a screen: one generation, one empty scrolling body, and the panel chrome taken down. A
     * panel puts its own chrome back through [showPanel].
     */
    fun begin() {
        generation++
        scrollView.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
        content.removeAllViews()
        chrome.removeAllViews()
        chrome.visibility = View.GONE
        // The views just removed are the ones these pointed at, and whatever is built next hands
        // over its own:
        planChart = null
        planCallout = null
    }

    /** Invalidate everything in flight: the Activity is going away. */
    fun close() {
        generation++
        io.shutdownNow()
        reloadPrices = null
    }

    /** Show a panel: */
    fun showPanel(title: String) {
        chrome.visibility = View.VISIBLE
        addPanelHeader(chrome, title, back)
    }

    /**
     * A touch that begins outside the graph and its readout puts the readout away -- and is
     * otherwise dispatched exactly as it was:
     */
    fun afterDispatch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dismissGesture = gestureThatDismisses(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> {
                if (ChartDismissal.clearsOnUp(dismissGesture, generation)) planChart?.clearSelection()
                dismissGesture = null
            }
            MotionEvent.ACTION_CANCEL -> dismissGesture = null
            else -> Unit
        }
    }

    /**
     * The generation to remember for a gesture beginning at this point, or `null` when this gesture
     * is the chart's or the readout's own business (or there is nothing to put away): see
     * [ChartDismissal.clearsSelection].
     */
    private fun gestureThatDismisses(x: Float, y: Float): Int? {
        val chart = planChart ?: return null
        // A readout that is not shown has no place on screen:
        val callout = planCallout?.takeIf { it.visibility == View.VISIBLE }
        val clears = ChartDismissal.clearsSelection(
            selectionActive = chart.hasSelection,
            chart = boundsInScreen(chart),
            callout = callout?.let { boundsInScreen(it) },
            x = x,
            y = y
        )
        return if (clears) generation else null
    }

    /**
     * A view's own box in the coordinates a touch event reports: `rawX`/`rawY` are screen
     * coordinates, and so is the location this reads.
     */
    private fun boundsInScreen(view: View): ViewBounds {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return ViewBounds(
            left = location[0].toFloat(),
            top = location[1].toFloat(),
            right = (location[0] + view.width).toFloat(),
            bottom = (location[1] + view.height).toFloat()
        )
    }
}

/**
 * One screen's controller: a [ViewScope] that draws into the shell and reads the shell's shared
 * state -- the body, the generation, the io executor -- by the same names the shell gives them.
 */
internal abstract class ScreenPart(internal val shell: ScreenShell) : ViewScope(shell) {
    internal val content: LinearLayout get() = shell.content
    internal val scrollView: ScrollView get() = shell.scrollView
    internal val ioExecutor: ExecutorService get() = shell.io
    internal val viewGeneration: Int get() = shell.generation
    internal val widgetId: Int get() = shell.widgetId
    internal var authorityController: AuthorityController?
        get() = shell.authorityController
        set(value) { shell.authorityController = value }

    internal fun beginScreen() = shell.begin()
    internal fun showPanel(title: String) = shell.showPanel(title)
    internal fun showScreen(screen: Screen) = shell.navigate(screen)
}
