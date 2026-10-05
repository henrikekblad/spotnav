package se.sensnology.spotnav.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import se.sensnology.spotnav.app.AppThemeSettings
import se.sensnology.spotnav.app.LauncherActivity
import se.sensnology.spotnav.chart.DayRollover
import se.sensnology.spotnav.ui.charging.LiveRefresh
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.prices.AreaCatalogue
import se.sensnology.spotnav.prices.PricePublications
import se.sensnology.spotnav.ui.charging.ChargingScreen
import se.sensnology.spotnav.ui.common.Palette
import se.sensnology.spotnav.ui.history.HistoryScreen
import se.sensnology.spotnav.ui.prices.PriceTableScreen
import se.sensnology.spotnav.ui.settings.SettingsScreen
import se.sensnology.spotnav.widget.PriceWidgetProvider
import se.sensnology.spotnav.widget.WidgetChartBoundary
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.Instant
import java.time.ZoneId

/**
 * The app's one Activity for a widget: the screen the widget host opens when a widget is added, the
 * screen a tap on a widget opens, and the app from the launcher.
 */
class WidgetConfigActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var shell: ScreenShell
    private val backNavigation = BackNavigation(this) { goBack() }

    // Whether this Activity was opened for an already-placed widget (a tap on it, or the app from
    // the launcher) rather than by the widget host configuring a new one:
    private var existingWidget = false

    // The one authoritative screen. Only `showScreen` writes it; the screens deliberately do not,
    // so an internal refresh can redraw what is on screen without deciding what it is.
    private var currentScreen = Screen.MAIN

    /** The publication listener's handle, so registration and destruction are symmetrical. */
    private var pricePublication: AutoCloseable? = null
    private var resumedOnce = false
    private var foreground = false

    /** The local-midnight appointment of the charging screen: armed while resumed, gone when stopped. */
    private val dayRollover by lazy {
        val main = Handler(Looper.getMainLooper())
        DayRollover(
            timer = object : DayRollover.Timer {
                private var pending: Runnable? = null
                override fun after(delayMs: Long, task: () -> Unit) {
                    cancel()
                    val run = Runnable { pending = null; task() }
                    pending = run
                    main.postDelayed(run, delayMs)
                }
                override fun cancel() { pending?.let(main::removeCallbacks); pending = null }
            },
            now = { Instant.now() },
            zone = { shell.dayZone?.invoke() ?: ZoneId.systemDefault() },
            onRollover = { shell.onDayBoundary?.invoke() }
        )
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The charging screen's re-read while it is in view: armed with [dayRollover], cancelled with it. */
    private val liveRefresh by lazy {
        val main = Handler(Looper.getMainLooper())
        LiveRefresh(
            timer = object : DayRollover.Timer {
                private var pending: Runnable? = null
                override fun after(delayMs: Long, task: () -> Unit) {
                    cancel()
                    val run = Runnable { pending = null; task() }
                    pending = run
                    main.postDelayed(run, delayMs)
                }
                override fun cancel() { pending?.let(main::removeCallbacks); pending = null }
            },
            now = { Instant.now() },
            nextEnd = { shell.liveRefreshEnd?.invoke() },
            onRefresh = { shell.onLiveRefresh?.invoke() }
        ).also { live -> shell.liveRefreshReconsider = { live.reconsider() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeSettings.apply(this)
        super.onCreate(savedInstanceState)
        // Android's widget host can launch this Activity directly when a widget is added, so the
        // launcher's own load is not enough:
        AreaCatalogue.load(this)
        existingWidget = intent.getBooleanExtra(EXTRA_EXISTING_WIDGET, false)
        if (!existingWidget) setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        if (!existingWidget && widgetId > 0) keepNewWidget()
        shell = ScreenShell(
            activity = this,
            palette = Palette.of(this),
            widgetId = widgetId,
            existingWidget = existingWidget,
            navigate = ::showScreen,
            back = ::goBack
        )
        setContentView(shell.root)
        // This phone's own notification check: scheduled while it is on and something is paired.
        LocalNotifications.sync(this)
        // Which screen to show: the one the user was already on, or -- on a fresh launch -- this
        // entry point's default.
        showScreen(Screen.restored(savedInstanceState?.getString(STATE_SCREEN), existingWidget))
        // The screen's interest in a widget publication refresh: registered for this Activity's own
        // lifetime and closed symmetrically in onDestroy.
        pricePublication = PricePublications.process.register { areaId ->
            if (Looper.myLooper() == Looper.getMainLooper()) shell.priceRefresh.published(areaId)
            else mainHandler.post { shell.priceRefresh.published(areaId) }
        }
    }

    /**
     * A new widget is kept however this screen is left: the host drops a widget whose configure
     * Activity does not return RESULT_OK. Defaults are stored now (never over saved settings), the
     * result is OK, and the settings screen applies each change as it is made.
     */
    private fun keepNewWidget() {
        WidgetSettings.seedDefaults(this, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        PriceWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
    }

    /** Transient UI state: which screen is open. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCREEN, currentScreen.storedKey)
    }

    /**
     * The one place a screen is chosen: it records the choice and renders it, so the two can never
     * disagree.
     */
    private fun showScreen(screen: Screen) {
        commitPendingEdit()
        currentScreen = screen
        // The publication hook lives exactly as long as the charging screen does:
        shell.reloadPrices = null
        shell.onForeground = null
        shell.onDayBoundary = null
        shell.dayZone = null
        shell.onLiveRefresh = null
        shell.liveRefreshEnd = null
        shell.onNotificationPermission = null
        dayRollover.cancel()
        liveRefresh.cancel()
        when (screen) {
            Screen.MAIN -> showCharging()
            Screen.PRICE_TABLE -> PriceTableScreen(shell).show()
            Screen.SETTINGS -> SettingsScreen(shell).show()
            Screen.HISTORY -> HistoryScreen(shell).show()
        }
        // A screen the user *navigated* to starts at its top; a refresh of the screen they are
        // already on does not (that is why this is here and not in the screens -- the charging
        // screen redraws itself in place).
        shell.scrollView.scrollTo(0, 0)
        backNavigation.setInterceptsBack(currentScreen.back(existingWidget) != null)
    }

    /** The charging screen, drawn afresh; a new charger chosen on its card calls this to redraw in place. */
    private fun showCharging() {
        ChargingScreen(shell) { showCharging() }.show()
        if (foreground) { dayRollover.arm(); liveRefresh.arm() }
    }

    /**
     * Back, as the screen model says: a panel returns to the main screen, the main screen leaves
     * the Activity, and an unconfigured widget's Settings leaves it too -- which is the widget
     * host's own cancel path, with `RESULT_CANCELED` already set in `onCreate`.
     */
    private fun goBack() {
        commitPendingEdit()
        currentScreen.back(existingWidget)?.let { showScreen(it) } ?: finish()
    }

    /**
     * The platform's own mechanism on each side, so the lint check that asks for AndroidX's
     * dispatcher -- a dependency this app does not have -- has nothing left to say about this one.
     */
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        commitPendingEdit()
        val back = currentScreen.back(existingWidget)
        if (back == null) super.onBackPressed() else showScreen(back)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (::shell.isInitialized) shell.afterDispatch(event)
        return handled
    }

    override fun onResume() {
        super.onResume()
        if (!::shell.isInitialized) return
        // A return to an already-built charging screen: a publication accepted while this Activity
        // was stopped was announced to nobody, so this is the one place that gap closes.
        shell.priceRefresh.resumed()
        // The first resume follows creation, which has just read everything; each later one may follow hours
        // away, across midnight.
        if (resumedOnce) shell.onForeground?.invoke()
        resumedOnce = true
        foreground = true
        if (currentScreen == Screen.MAIN) { dayRollover.arm(); liveRefresh.arm() }
        // The widget draws from held data cut by the local clock, so a redraw here costs no network.
        WidgetChartBoundary.requestRedraw(this)
        if (widgetId < 0) {
            val manager = AppWidgetManager.getInstance(this)
            val component = ComponentName(this, PriceWidgetProvider::class.java)
            if (manager.getAppWidgetIds(component).any { WidgetSettings.isConfigured(this, it) }) {
                startActivity(Intent(this, LauncherActivity::class.java))
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LocalNotifications.PERMISSION_REQUEST && ::shell.isInitialized) {
            shell.onNotificationPermission?.invoke()
        }
    }

    override fun onStop() {
        foreground = false
        dayRollover.cancel()
        liveRefresh.cancel()
        super.onStop()
    }

    override fun onPause() {
        commitPendingEdit()
        super.onPause()
    }

    /**
     * The settings screen applies a typed figure when its field is left, so a field still being
     * edited is left -- and applied -- before the screen, or the app, goes away.
     */
    private fun commitPendingEdit() {
        if (::shell.isInitialized) currentFocus?.clearFocus()
    }

    override fun onDestroy() {
        backNavigation.release()
        if (::shell.isInitialized) shell.close()
        // Symmetrical with onCreate:
        pricePublication?.close()
        pricePublication = null
        super.onDestroy()
    }

    companion object {
        // Which screen the user was on, kept across a recreation. A transient UI fact, not a
        // preference:
        private const val STATE_SCREEN = "screen"
        const val EXTRA_EXISTING_WIDGET = "existing_widget"
    }
}
