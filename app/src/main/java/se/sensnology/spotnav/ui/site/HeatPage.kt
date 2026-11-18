package se.sensnology.spotnav.ui.site

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatWrite
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords
import se.sensnology.spotnav.ui.common.headerTitle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** The heat page's tabs in the card's order; only Now is built (Plan and Use come with their step). */
internal enum class HeatTab(val key: String) {
    NOW("now"), PLAN("plan"), USE("use");

    companion object {
        val BUILT: List<HeatTab> = listOf(NOW)
    }
}

/**
 * The heat page: the header ("Värme · Test · Värmer 1,1 kW") and the Now tab as the Home Assistant card has it: the
 * thermostat dial with its buttons (the target in half degrees, 16 to 25), Home or Away until a date, what the heat
 * pump does and what SpotNav would do, the plan's next twelve hours, today's figures and the notes the household can
 * act on.
 *
 * It reads only while in view with the app in the foreground: when it opens, then every minute, and at once after a
 * write ([SitePolling]). The dial's figure waits 0.7 s after the last press and is written then; a write carries what
 * was last read for the same key, so someone else's change is never overwritten (the card's compare-and-set).
 * The tab row is drawn from the second tab on: with Now alone there is none.
 */
internal class HeatPage(
    scope: ViewScope,
    private val session: HaSession,
    private val memory: SiteMemory
) : ViewScope(scope), SitePage {
    override val kind = PageKind.HEAT

    private val words = cardWords()
    private val text = SiteText(words, AppLanguageSettings.locale(context), AppLanguageSettings.numberLocale(context))
    private val colours = SiteColours(palette)
    private val main = Handler(Looper.getMainLooper())
    private var failed = false
    private var unsupported = false
    private var testOpen = false
    private var inFlight = false
    /** The dial's figure while a change waits to be written. */
    private var pendingTarget: Double? = null
    private var error: String? = null
    private var settle: Runnable? = null

    private val title = TextView(context).apply {
        textSize = 21f
        setTextColor(dark)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(40)
        isClickable = false
        setOnClickListener {
            testOpen = !testOpen
            render()
        }
    }
    private val testExplain = note()
    private val state = line().apply { setTextColor(muted) }
    private val dial = HeatDialView(context, palette, colours)
    private val lower = stepButton("−", "heat.dial.lower", -HeatNowModel.TARGET_STEP)
    private val raise = stepButton("+", "heat.dial.raise", HeatNowModel.TARGET_STEP)
    private val dialRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(lower, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(dial, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        addView(raise, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    private val errorLine = line().apply { setTextColor(0xFFD64541.toInt()); gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val measured = line().apply { gravity = Gravity.CENTER }
    private val home = modeButton("heat.mode.home") { goHome() }
    private val away = modeButton("heat.mode.away") { chooseAway() }
    private val modes = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(home, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(6), 0) })
        addView(away, LinearLayout.LayoutParams(0, dp(44), 1f))
    }
    private val awayLine = line().apply { gravity = Gravity.CENTER; setTextColor(muted) }
    private val change = Button(context).apply {
        text = words.say("heat.mode.change")
        isAllCaps = false
        setOnClickListener { chooseAway() }
    }
    private val shadow = note()
    private val statusBlock = line()
    private val ahead = AheadStripView(context, palette, colours)
    private val aheadLine = note()
    private val today = line()
    private val notes = note()

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(cardBackground)
            cornerRadius = dp(10).toFloat()
        }
    }

    override val view: View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, wide().apply { bottomMargin = dp(8) })
            addView(testExplain, wide().apply { bottomMargin = dp(8) })
            addView(body, wide().apply { bottomMargin = dp(14) })
        })
    }

    private val polling = SitePolling(
        timer = MainTimer(),
        now = { android.os.SystemClock.elapsedRealtime() },
        // The heat page has no live tab: one read when it opens, then every minute.
        live = { false },
        readLive = {},
        readSlow = ::read
    )

    init {
        // The tab row is drawn from the second tab on; Now alone has none.
        check(!HeatNowModel.tabRowShown(HeatTab.BUILT.size))
        body.addView(state, wide())
        body.addView(dialRow, wide())
        body.addView(errorLine, wide())
        body.addView(measured, wide().apply { topMargin = dp(4) })
        body.addView(modes, wide().apply { topMargin = dp(10) })
        body.addView(awayLine, wide().apply { topMargin = dp(6) })
        body.addView(change, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        body.addView(shadow, wide().apply { topMargin = dp(8) })
        body.addView(statusBlock, wide().apply { topMargin = dp(8) })
        body.addView(ahead, wide().apply { topMargin = dp(12) })
        body.addView(aheadLine, wide().apply { topMargin = dp(4) })
        body.addView(today, wide().apply { topMargin = dp(10) })
        body.addView(notes, wide().apply { topMargin = dp(6) })
        render()
    }

    override fun setInView(inView: Boolean) {
        if (inView) polling.start() else {
            polling.stop()
            // A press waiting to be written is written at once rather than lost.
            settle?.let { main.removeCallbacks(it); it.run() }
        }
    }

    /** Read the heating now (the page's minute, and after every write). */
    private fun read() {
        if (inFlight) return
        inFlight = true
        session.heat { outcome ->
            inFlight = false
            when (outcome) {
                is SiteRead.Read -> {
                    memory.heat = outcome.value
                    memory.heatShown = outcome.value.hasSomething
                    failed = false
                    unsupported = false
                }
                is SiteRead.Unsupported -> { unsupported = memory.heat == null; failed = false }
                else -> failed = memory.heat == null
            }
            render()
        }
    }

    private fun step(delta: Double) {
        val heat = memory.heat ?: return
        val next = HeatNowModel.stepped(pendingTarget ?: heat.config.targetC, delta)
        pendingTarget = next
        error = null
        render()
        settle?.let(main::removeCallbacks)
        val run = Runnable {
            settle = null
            val wanted = pendingTarget
            val held = memory.heat
            if (wanted == null || held == null || wanted == held.config.targetC) {
                pendingTarget = null
                render()
                return@Runnable
            }
            write(HeatWrite.Change.Target(wanted, held.config.targetC))
        }
        settle = run
        main.postDelayed(run, SETTLE_MS)
    }

    private fun goHome() {
        val heat = memory.heat ?: return
        if (heat.config.mode == "home") return
        write(HeatWrite.Change.Mode("home", null, heat.config.mode, heat.config.awayUntilRaw))
    }

    /** Away until a date and time: the platform's pickers, from the stored return time or tomorrow 16:00. */
    private fun chooseAway() {
        val heat = memory.heat ?: return
        val first: ZonedDateTime = heat.comfort.awayUntil?.atZone(heat.zone)
            ?: heat.now.atZone(heat.zone).toLocalDate().plusDays(1).atTime(LocalTime.of(16, 0)).atZone(heat.zone)
        val picker = DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                val until = LocalDate.of(y, m + 1, d).atTime(h, min).atZone(heat.zone).toInstant()
                if (!until.isAfter(Instant.now())) {
                    error = words.say("heat.error.past")
                    render()
                } else {
                    val held = memory.heat ?: heat
                    write(HeatWrite.Change.Mode("away", until, held.config.mode, held.config.awayUntilRaw))
                }
            }, first.hour, first.minute, true).apply { setTitle(words.say("heat.away.title")) }.show()
        }, first.year, first.monthValue - 1, first.dayOfMonth)
        picker.setTitle(words.say("heat.away.title"))
        picker.datePicker.minDate = System.currentTimeMillis() - 1000
        picker.show()
    }

    private fun write(change: HeatWrite.Change) {
        session.updateHeat(change) { outcome ->
            pendingTarget = null
            error = if (outcome is HeatWrite.Outcome.Written) null else HeatNowModel.errorWords(text, outcome)
            // Whatever came of it, what Home Assistant holds is read at once.
            render()
            inFlight = false
            read()
        }
    }

    private fun render() {
        val heat = memory.heat
        title.text = headerTitle(words.say("heat.title"), heat?.let { HeatNowModel.statusAfterName(text, it) })
        val test = heat != null && !HeatNowModel.controls(heat)
        title.isClickable = test
        testExplain.visibility = if (test && testOpen) View.VISIBLE else View.GONE
        if (test) {
            val lead = words.say("header.testExplain")
            testExplain.text = SpannableString("$lead ${words.say("heat.test.explain")}").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (heat == null) {
            state.text = words.say(if (unsupported) "heat.unsupported" else if (failed) "heat.failed" else "heat.loading")
            state.visibility = View.VISIBLE
            listOf(dialRow, errorLine, measured, modes, awayLine, change, shadow, statusBlock, ahead, aheadLine, today, notes).forEach { it.visibility = View.GONE }
            return
        }
        state.visibility = View.GONE
        val target = pendingTarget ?: heat.comfort.targetC
        dialRow.visibility = View.VISIBLE
        dial.show(
            target, heat.indoor.valueC, "${text.fixed(target, 1)}°", HeatNowModel.bandLine(text, target),
            "${words.say("heat.dial.label")}, ${HeatNowModel.degrees(text, target)}" +
                (heat.indoor.valueC?.let { ", ${HeatNowModel.degrees(text, it)}" } ?: "")
        )
        lower.isEnabled = target - HeatNowModel.TARGET_STEP >= HeatNowModel.TARGET_MIN - 1e-9
        raise.isEnabled = target + HeatNowModel.TARGET_STEP <= HeatNowModel.TARGET_MAX + 1e-9
        say(errorLine, error)
        measured.text = HeatNowModel.measuredLine(text, heat)
        measured.visibility = View.VISIBLE
        modes.visibility = View.VISIBLE
        val awayNow = heat.comfort.mode == "away"
        paint(home, !awayNow)
        paint(away, awayNow)
        val awayWords = HeatNowModel.awayLine(text, heat)
        say(awayLine, awayWords)
        change.visibility = if (awayWords == null) View.GONE else View.VISIBLE
        say(shadow, if (heat.shadow) words.say("heat.shadow") else null)
        val live = HeatNowModel.live(text, heat)
        // The header says what the pump does; here only while the header is taken by SpotNav's own lowering or raise.
        val pump = if (live == null || live.first == "lower" || live.first == "raise") HeatNowModel.pumpLine(text, heat) else null
        say(statusBlock, listOfNotNull(pump, HeatNowModel.wouldLine(text, heat), HeatNowModel.relayLine(text, heat, heat.now.coerceAtLeastNow())).joinToString("\n").ifEmpty { null })
        val line = HeatNowModel.aheadLine(text, heat)
        ahead.visibility = if (line == null) View.GONE else View.VISIBLE
        ahead.show(HeatNowModel.aheadCells(heat), HeatNowModel.aheadAxis(text, heat))
        say(aheadLine, line)
        say(today, HeatNowModel.todayLine(text, heat))
        say(notes, HeatNowModel.noteLines(text, heat).joinToString("\n") { it.second }.ifEmpty { null })
    }

    /** The relay's hours are read against the clock, not the answer's moment: a page left open moves on. */
    private fun Instant.coerceAtLeastNow(): Instant = maxOf(this, Instant.now())

    private fun paint(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.setTextColor(if (selected) 0xFFFFFFFF.toInt() else dark)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            if (selected) setColor(accent) else { setColor(0); setStroke(dp(1), colours.wire) }
        }
    }

    private fun say(view: TextView, words: String?) {
        view.text = words.orEmpty()
        view.visibility = if (words == null) View.GONE else View.VISIBLE
    }

    private fun stepButton(label: String, description: String, delta: Double) = Button(context).apply {
        text = label
        textSize = 22f
        setPadding(0, 0, 0, 0)
        contentDescription = words.say(description)
        setOnClickListener { step(delta) }
    }

    private fun modeButton(key: String, onTap: () -> Unit) = Button(context).apply {
        text = words.say(key)
        isAllCaps = false
        setOnClickListener { onTap() }
    }

    private fun line() = TextView(context).apply {
        textSize = 15f
        setTextColor(dark)
    }

    private fun note() = TextView(context).apply {
        textSize = 13f
        setTextColor(muted)
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private companion object {
        /** The dial's figure is written this long after the last press (the card's `DIAL_SETTLE_MS`). */
        const val SETTLE_MS = 700L
    }
}
