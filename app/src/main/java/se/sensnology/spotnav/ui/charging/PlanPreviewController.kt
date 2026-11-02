package se.sensnology.spotnav.ui.charging

import android.os.Handler
import android.os.Looper
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.client.PlanPreview
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.StatusFormat
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ui.common.ViewScope
import java.time.Instant

/**
 * The live plan preview while a slider is held (webhook `plan_preview`, the card's Plan dialog twin): one request about
 * 300 ms after the last move, an answer to an older move dropped, the last shown plan kept (dimmed) while a newer
 * one is awaited, and everything put away when the slider is let go (the edit is then written and the dashboard says
 * the plan). A refused or failed request shows nothing new; nothing is saved by any of it.
 */
internal class PlanPreviewController(
    scope: ViewScope,
    private val panel: PlanPreviewPanel,
    private val session: () -> HaSession?,
    private val dashboard: () -> Dashboard?,
    private val post: (Runnable, Long) -> Unit = { run, delay -> handler.postDelayed(run, delay) },
    private val cancel: (Runnable) -> Unit = { handler.removeCallbacks(it) }
) : ViewScope(scope) {
    private var sequence = 0
    private var pending: Runnable? = null
    private var shown: Pair<PlanPreviewText.Lines, PlanPreviewText.Strip?>? = null

    /** Whether the charger answers `plan_preview`: an older Home Assistant leaves the preview out. */
    fun offered(): Boolean = dashboard()?.capabilities?.planPreview == true

    /** The settings a Save of the held value would make, or `null` when the edit makes none the contract accepts. */
    fun request(replacement: HaPlanningSettings?) {
        if (!offered() || replacement == null) return
        val mine = ++sequence
        pending?.let(cancel)
        shown?.let { (lines, strip) -> panel.show(lines, strip, dimmed = true) }
        val run = Runnable {
            pending = null
            val held = session() ?: return@Runnable
            held.planPreview(replacement) { outcome -> answer(mine, outcome) }
        }
        pending = run
        post(run, DEBOUNCE_MS)
    }

    /** The slider was let go: nothing of the preview stays, and an answer on its way is dropped. */
    fun clear() {
        sequence += 1
        pending?.let(cancel)
        pending = null
        shown = null
        panel.hide()
    }

    private fun answer(mine: Int, outcome: PlanPreview.Outcome) {
        if (mine != sequence || isDestroyed) return
        val held = dashboard() ?: return
        when (outcome) {
            is PlanPreview.Outcome.Previewed -> {
                val format = StatusFormat.of(AppLanguageSettings.language(context), held.market, AppLanguageSettings.numberLocale(context))
                val lines = PlanPreviewText.lines(outcome.preview, format, Instant.now())
                val strip = outcome.preview.strip?.let { PlanPreviewText.strip(it, format.zone) }
                shown = lines to strip
                panel.show(lines, strip, dimmed = false)
            }
            // A refused, rate-limited or failed ask shows nothing new: the last plan stays, no longer awaited.
            else -> shown?.let { (lines, strip) -> panel.show(lines, strip, dimmed = false) }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        private val handler = Handler(Looper.getMainLooper())
    }
}
