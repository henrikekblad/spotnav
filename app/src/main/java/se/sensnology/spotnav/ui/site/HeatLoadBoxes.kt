package se.sensnology.spotnav.ui.site

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatLoadPress
import se.sensnology.spotnav.ha.heat.HeatLoads
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardHeading
import se.sensnology.spotnav.ui.common.cardTitle

/**
 * The heat page's Pool, Spa and Bastu boxes, under Plan and Förbrukning as the card's tabs follow them (the app stacks
 * the tabs): each only where `get_heat` has the load. What the household may do is offered as on the card, for anyone
 * who sees it: the pool's season and Extra filtrering (+2 h, +4 h, Avbryt), "Bada nu" / "Värm nu" and Avbryt, and the
 * answers to a bath or a session that will not be ready. The opt-ins and the setup stay in Home Assistant: a load SpotNav
 * only watches says so, with "Ställ in i Home Assistant". After every press the heating is read again ([reread]).
 */
internal class HeatLoadBoxes(
    scope: ViewScope,
    private val session: HaSession,
    private val text: SiteText,
    private val homeAssistantUrl: String?,
    private val reread: () -> Unit
) : ViewScope(scope) {
    private val words get() = text.words
    private var busy: String? = null
    private var shown: Heat? = null
    private val errors = mutableMapOf<String, String>()
    /** The temperatures stepped to and not yet written, by load. */
    private val pending = mutableMapOf<String, Double>()
    private val settles = mutableMapOf<String, Runnable>()
    /** The "Allow the peak" buttons pressed once: the second press sends it. */
    private val askedPeak = mutableSetOf<String>()

    private val poolBox = box()
    private val spaBox = box()
    private val saunaBox = box()

    /** The three boxes, in the card's tab order. */
    val panels: List<View> = listOf(poolBox, spaBox, saunaBox)

    /** Draw the boxes the answer has; the others are left out. */
    fun render(heat: Heat?) {
        shown = heat
        val said = heat?.let { HeatLoadsText.of(text, it) }
        fill(poolBox, heat?.pool?.let { pool -> { pool(it, said!!, pool) } })
        fill(spaBox, heat?.spa?.let { spa -> { deadline(it, said!!, spa) } })
        fill(saunaBox, heat?.sauna?.let { sauna -> { deadline(it, said!!, sauna) } })
    }

    private fun fill(box: LinearLayout, draw: ((LinearLayout) -> Unit)?) {
        box.removeAllViews()
        box.visibility = if (draw == null) View.GONE else View.VISIBLE
        draw?.invoke(box)
    }

    // --- the pool ---------------------------------------------------------------------------------

    private fun pool(box: LinearLayout, said: HeatLoadsText, pool: HeatLoads.Pool) {
        val kind = "pool"
        box.addView(cardHeading(R.drawable.ic_now_heat, cardTitle(words.say("heat.tab.pool"))), wide())
        box.addView(targetRow(kind, words.say("heat.pool.dial"), pool.targetC, said), wide(top = 8))
        box.addView(note(said.poolWater(pool)), wide(top = 2))
        said.poolSource(pool)?.let { box.addView(note(it), wide(top = 0)) }
        // The season: open (SpotNav plans the pool) or closed (it touches nothing).
        box.addView(note(words.say("heat.pool.season")), wide(top = 10))
        box.addView(row(
            pill(words.say("heat.pool.seasonOpen"), pool.seasonOpen) { if (!pool.seasonOpen) press(kind, HeatLoadPress.pool("season", mapOf("open" to true))) },
            pill(words.say("heat.pool.seasonClosed"), !pool.seasonOpen) { if (pool.seasonOpen) press(kind, HeatLoadPress.pool("season", mapOf("open" to false))) }
        ), wide(top = 4))
        box.addView(line(said.poolStatus(pool)), wide(top = 10))
        said.poolWouldShown(pool)?.let { box.addView(line(it), wide(top = 2)) }
        if (pool.seasonHint) box.addView(note(words.say("heat.pool.seasonHint")), wide(top = 6))
        // Extra filtrering: only with a circulation pump and the season open.
        if (pool.seasonOpen && pool.circulation != null) {
            val extra = said.poolExtra(pool)
            if (extra == null) {
                box.addView(line(words.say("heat.pool.extra.label")), wide(top = 10))
                box.addView(row(*listOf(2, 4).map { hours ->
                    button(words.say("heat.pool.extra.add", "hours" to hours.toString())) { press(kind, HeatLoadPress.pool("extra_filtering", mapOf("hours" to hours))) }
                }.toTypedArray()), wide(top = 2))
            } else {
                box.addView(line(extra), wide(top = 10))
                box.addView(row(button(words.say("heat.pool.extra.cancel")) { press(kind, HeatLoadPress.pool("cancel_extra")) }), wide(top = 2))
            }
        }
        said.poolPlan(pool)?.let { box.addView(note(it), wide(top = 8)) }
        said.poolToday(pool).forEach { box.addView(line(it), wide(top = 4)) }
        if (pool.seasonOpen) {
            val controlled = said.poolControl(pool)
            if (controlled.isEmpty()) watched(box, words.say("heat.pool.watched"))
            else controlled.forEach { box.addView(note(it), wide(top = 6)) }
        }
        errorLine(box, kind)
    }

    // --- the spa and the sauna --------------------------------------------------------------------

    private fun deadline(box: LinearLayout, said: HeatLoadsText, load: HeatLoads.Deadline) {
        val kind = load.kind
        box.addView(cardHeading(R.drawable.ic_now_heat, cardTitle(words.say("heat.tab.$kind"))), wide())
        box.addView(targetRow(kind, words.say("heat.$kind.dial"), load.targetC, said), wide(top = 8))
        box.addView(note(said.deadlineTemp(load)), wide(top = 2))
        box.addView(line(said.deadlineStatus(load)), wide(top = 10))
        if (load.locked == true) box.addView(line(words.say("heat.sauna.locked")), wide(top = 2))
        said.deadlineWouldShown(load)?.let { box.addView(line(it), wide(top = 2)) }
        // A bath or a session that will not be ready: its words and the choices that answer it.
        load.notReady?.let { notReady ->
            box.addView(line(said.notReadyLine(load, notReady)), wide(top = 10))
            box.addView(column(*said.notReadyChoices(load, notReady).map { choice ->
                val key = "$kind:${choice.action}"
                val label = if (choice.action == "accept_peak" && key in askedPeak) words.say("doesNotFit.choice.acceptPeakAgain") else choice.label
                button(label) {
                    if (choice.action == "accept_peak" && askedPeak.add(key)) {
                        render(shown)
                        return@button
                    }
                    askedPeak.remove(key)
                    press(kind, HeatLoadPress.deadline(kind, choice.action))
                }
            }.toTypedArray()), wide(top = 4))
        }
        // Bada nu / Värm nu, or the press that runs with its end.
        val running = said.nowLine(load)
        if (running == null) {
            box.addView(row(button(said.nowLabel(load)) { press(kind, HeatLoadPress.deadline(kind, said.nowAction(load))) }), wide(top = 8))
        } else {
            box.addView(line(running), wide(top = 8))
            box.addView(row(button(words.say("heat.spa.cancelNow")) { press(kind, HeatLoadPress.deadline(kind, "cancel_now")) }), wide(top = 2))
        }
        box.addView(line(said.times(load)), wide(top = 8))
        box.addView(row(button(words.say("heat.mode.change")) { editTimes(load) }.apply {
            contentDescription = "${words.say("heat.$kind.times")}: ${words.say("heat.mode.change")}"
        }), wide(top = 2))
        said.deadlinePlan(load)?.let { box.addView(note(it), wide(top = 8)) }
        val control = said.deadlineControl(load)
        if (load.control.controlling) box.addView(note(control), wide(top = 6)) else watched(box, control)
        errorLine(box, kind)
    }

    // --- the temperature dial ---------------------------------------------------------------------

    /**
     * The load's temperature with − and + beside it, as the card's dial steps it: each press moves the figure, and
     * the last one is written [SETTLE_MS] after it (`pool_action: target`, `bath_c`, `target_c`).
     */
    private fun targetRow(kind: String, label: String, stored: Double, said: HeatLoadsText): View {
        val shownValue = pending[kind] ?: stored
        val figure = line("$label: ${said.target(shownValue)}").apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(stepButton("−", words.say("heat.$kind.lower")) { step(kind, stored, up = false) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(figure, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
            addView(stepButton("+", words.say("heat.$kind.raise")) { step(kind, stored, up = true) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    private fun step(kind: String, stored: Double, up: Boolean) {
        val next = LoadTimes.stepped(kind, pending[kind] ?: stored, up)
        pending[kind] = next
        errors.remove(kind)
        render(shown)
        settles.remove(kind)?.let(poolBox::removeCallbacks)
        val run = object : Runnable {
            override fun run() {
                settles.remove(kind)
                val wanted = pending[kind]
                if (wanted == null || wanted == storedTarget(kind)) {
                    pending.remove(kind)
                    render(shown)
                    return
                }
                // Another press on its way: this one is written once it has answered.
                if (busy != null) {
                    settles[kind] = this
                    poolBox.postDelayed(this, SETTLE_MS)
                    return
                }
                val body = if (kind == "pool") HeatLoadPress.poolTarget(wanted) else HeatLoadPress.deadlineTarget(kind, wanted)
                press(kind, body) { pending.remove(kind) }
            }
        }
        settles[kind] = run
        poolBox.postDelayed(run, SETTLE_MS)
    }

    private fun storedTarget(kind: String): Double? = when (kind) {
        "pool" -> shown?.pool?.targetC
        "spa" -> shown?.spa?.targetC
        else -> shown?.sauna?.targetC
    }

    /** A press waiting to be written is written at once (the page leaves view). */
    fun flush() {
        settles.values.toList().forEach { poolBox.removeCallbacks(it); it.run() }
    }

    // --- the times editor --------------------------------------------------------------------------

    /** The spa's bath times or the sauna's sessions in an editor of their own, as the card's (any user's). */
    private fun editTimes(load: HeatLoads.Deadline) {
        val heat = shown ?: return
        LoadTimesEditor(this, words, load, heat.now, heat.zone, text.locale) { bodies, done ->
            sendAll(load.kind, bodies, done)
        }.open()
    }

    /** Send [bodies] one after the other, stopping at the first refusal; [done] gets its words, or `null`. */
    private fun sendAll(kind: String, bodies: List<org.json.JSONObject>, done: (String?) -> Unit) {
        if (bodies.isEmpty()) { done(null); return }
        session.heatLoadPress(bodies.first()) { outcome ->
            val error = HeatLoadsText.pressError(outcome, t(R.string.load_press_failed), t(R.string.load_press_rate_limited))
            if (error != null || bodies.size == 1) {
                done(error)
                reread()
            } else {
                sendAll(kind, bodies.drop(1), done)
            }
        }
    }

    // --- presses ------------------------------------------------------------------------------------

    private fun press(kind: String, body: org.json.JSONObject, after: () -> Unit = {}) {
        if (busy != null) return
        busy = kind
        errors.remove(kind)
        render(shown)
        session.heatLoadPress(body) { outcome ->
            busy = null
            after()
            HeatLoadsText.pressError(outcome, t(R.string.load_press_failed), t(R.string.load_press_rate_limited))?.let { errors[kind] = it }
            render(shown)
            // Whatever came of it, what Home Assistant holds is read at once.
            reread()
        }
    }

    // --- views ----------------------------------------------------------------------------------------

    /** The line a watched load says, and that its switch is set in Home Assistant: plain text, as everywhere else. */
    private fun watched(box: LinearLayout, said: String) {
        box.addView(note(said), wide(top = 6))
        box.addView(note(t(R.string.site_admin_in_home_assistant)), wide(top = 2))
    }

    private fun errorLine(box: LinearLayout, kind: String) {
        errors[kind]?.let { box.addView(line(it).apply { setTextColor(ERROR) }, wide(top = 6)) }
    }

    private fun box() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(cardBackground)
            cornerRadius = dp(10).toFloat()
        }
        visibility = View.GONE
    }

    private fun button(label: String, onTap: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        isEnabled = busy == null
        setOnClickListener { onTap() }
    }

    /** A round − or + beside the temperature, as the heat page's dial has. */
    private fun stepButton(label: String, description: String, onTap: () -> Unit) = Button(context).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        stateListAnimator = null
        minWidth = dp(44)
        minHeight = dp(44)
        setPadding(0, 0, 0, 0)
        setTextColor(dark)
        contentDescription = description
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(1), muted) }
        setOnClickListener { onTap() }
    }

    /** A choice between two states, the chosen one marked. */
    private fun pill(label: String, chosen: Boolean, onTap: () -> Unit) = button(label, onTap).apply {
        isSelected = chosen
        setTextColor(if (chosen) cardBackground else dark)
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            if (chosen) setColor(accent) else { setColor(0); setStroke(dp(1), muted) }
        }
    }

    private fun row(vararg views: View) = WrapRow(context, dp(8), dp(4)).apply { views.forEach { addView(it) } }

    private fun column(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun line(words: String) = TextView(context).apply {
        textSize = 15f
        setTextColor(dark)
        text = words
    }

    private fun note(words: String) = TextView(context).apply {
        textSize = 13f
        setTextColor(muted)
        text = words
    }

    private fun wide(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private companion object {
        const val ERROR = 0xFFD64541.toInt()
        /** The figure is written this long after the last press (the card's `DIAL_SETTLE_MS`, the heat page's). */
        const val SETTLE_MS = 700L
    }
}
