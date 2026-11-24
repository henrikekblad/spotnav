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
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatWrite
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords
import se.sensnology.spotnav.ui.common.headerTitle
import se.sensnology.spotnav.ui.common.iconAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The heat page: the header ("Värme · Test · Värmer 1,1 kW") and the Now tab as the Home Assistant card has it: the
 * thermostat dial with its buttons (the target in half degrees, 16 to 25), Home or Away until a date, what the heat
 * pump does and what SpotNav would do, the Boost button (hot water now, where the pump has a boost that ends by
 * itself), the plan's next 24 hours, today's figures and the notes the household can act on.
 *
 * It reads only while in view with the app in the foreground: when it opens, then every minute, and at once after a
 * write ([SitePolling]). The dial's figure waits 0.7 s after the last press and is written then; a write carries what
 * was last read for the same key, so someone else's change is never overwritten (the card's compare-and-set).
 * The tab row is drawn from the second tab on: with Now alone there is none.
 */
internal class HeatPage(
    scope: ViewScope,
    private val session: HaSession,
    private val memory: SiteMemory,
    private val preferences: SitePreferences,
    /** What the remembered choices are kept under until the heating has said its site. */
    private val profileId: String,
    /** Home Assistant's address, for "Ställ in i Home Assistant" under a load SpotNav only watches. */
    homeAssistantUrl: String? = null
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

    // The boxes under the Now box (the card's tabs, stacked): one shared period choice and one picker, then Plan and Förbrukning.
    private val locale = AppLanguageSettings.locale(context)
    private val store = HeatHistoryStore(session) { render() }
    private val planBox = HeatPlanPanel(this, store, text)
    private val useBox = HeatUsePanel(this, store, preferences, text, locale, ::siteKey)
    private val details = HeatDetailsDialog(this, session, text, colours, locale, ::todayDate)
    // The pool, the spa and the sauna under them, as the card's last tabs.
    private val loadBoxes = HeatLoadBoxes(this, session, text, homeAssistantUrl) { inFlight = false; read() }
    private var boxesOpen = false
    private fun changePeriod(next: PeriodChoice) {
        store.choice = next
        planBox.periodChanged()
        useBox.periodChanged()
        readHistory(force = false)
        render()
    }
    private val picker = PeriodPickerView(this, words, locale, onChange = ::changePeriod)
    private val dayPicker = PeriodPickerView(this, words, locale, kinds = listOf(HistoryPeriod.DAY), onChange = ::changePeriod)
    private val periodRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        visibility = View.GONE
        addView(picker, wide())
        addView(dayPicker, wide())
    }

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
    // The Boost button with its state beside it (only where Home Assistant says the pump has a boost that ends by itself).
    private var boostBusy = false
    private var boostError: String? = null
    private var boostPolls = 0
    private var boostPoll: Runnable? = null
    private val boostLine = note().apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val boostButton = Button(context).apply {
        isAllCaps = false
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_drop, 0, 0, 0)
        compoundDrawablePadding = dp(6)
        setOnClickListener { pressBoost() }
    }
    private val boostErrorLine = note()
    private val boostRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(boostLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(boostButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(boostErrorLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
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

    /** The "i" beside the name: the details sheet. Left out where the history has nothing to say. */
    private val info = iconAction(R.drawable.ic_info, words.say("hist.title.heat")) { details.open() }.apply { visibility = View.GONE }

    override val view: View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(info)
            }, wide().apply { bottomMargin = dp(8) })
            addView(testExplain, wide().apply { bottomMargin = dp(8) })
            addView(body, wide().apply { bottomMargin = dp(14) })
            // One picker above the period boxes, under the Now box; it drives every box below it.
            addView(periodRow, wide().apply { bottomMargin = dp(6) })
            addView(planBox.panel, wide().apply { bottomMargin = dp(14) })
            addView(useBox.panel, wide().apply { bottomMargin = dp(14) })
            loadBoxes.panels.forEach { addView(it, wide().apply { bottomMargin = dp(14) }) }
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
        body.addView(state, wide())
        body.addView(dialRow, wide())
        body.addView(errorLine, wide())
        body.addView(measured, wide().apply { topMargin = dp(4) })
        body.addView(modes, wide().apply { topMargin = dp(10) })
        body.addView(awayLine, wide().apply { topMargin = dp(6) })
        body.addView(change, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        body.addView(shadow, wide().apply { topMargin = dp(8) })
        body.addView(statusBlock, wide().apply { topMargin = dp(8) })
        body.addView(boostRow, wide().apply { topMargin = dp(8) })
        body.addView(ahead, wide().apply { topMargin = dp(12) })
        body.addView(aheadLine, wide().apply { topMargin = dp(4) })
        body.addView(today, wide().apply { topMargin = dp(10) })
        body.addView(notes, wide().apply { topMargin = dp(6) })
        render()
    }

    override fun setInView(inView: Boolean) {
        if (inView) polling.start() else {
            polling.stop()
            boostPoll?.let(main::removeCallbacks)
            boostPoll = null
            // A press waiting to be written is written at once rather than lost.
            settle?.let { main.removeCallbacks(it); it.run() }
            loadBoxes.flush()
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
                    // The running period is read again with the heating, every minute.
                    readHistory(force = store.choice.date == null)
                }
                is SiteRead.Unsupported -> { unsupported = memory.heat == null; failed = false }
                else -> failed = memory.heat == null
            }
            render()
        }
    }

    /** Ask for what the boxes under the Now box draw: Förbrukning's period, and a past day's plan. */
    private fun readHistory(force: Boolean) {
        if (HeatBoxes.needsHistory(memory.heat, store.choice, System.currentTimeMillis())) store.request(store.choice, force)
    }

    private fun siteKey(): String = memory.heat?.siteId ?: profileId

    private fun todayDate(): LocalDate = memory.heat?.zone?.let { LocalDate.now(it) } ?: LocalDate.now()

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
        session.updateHeat(HeatWrite.request(change)) { outcome ->
            pendingTarget = null
            error = if (outcome is HeatWrite.Outcome.Written) null else HeatNowModel.errorWords(text, outcome)
            // Whatever came of it, what Home Assistant holds is read at once.
            render()
            inFlight = false
            read()
        }
    }

    /** The Boost button pressed: the start or stop sent, its answer read back, and while it starts (read back, up to a
     * few minutes through a cloud) the heating is read again every [BOOST_POLL_MS]. */
    private fun pressBoost() {
        val heat = memory.heat ?: return
        val action = HeatNowModel.boostWords(text, heat, Instant.now())?.action ?: return
        if (boostBusy) return
        boostBusy = true
        boostError = null
        render()
        session.heatBoost(action) { outcome ->
            boostBusy = false
            boostError = HeatNowModel.boostError(text, outcome)
            inFlight = false
            read()
            boostPolls = BOOST_POLLS
            scheduleBoostPoll()
        }
    }

    /** Look again while the boost is starting, a set number of times. */
    private fun scheduleBoostPoll() {
        boostPoll?.let(main::removeCallbacks)
        boostPoll = null
        if (boostPolls <= 0) return
        val run = Runnable {
            boostPoll = null
            if (memory.heat?.hotWater?.boost?.state != "starting") return@Runnable
            boostPolls--
            read()
            scheduleBoostPoll()
        }
        boostPoll = run
        main.postDelayed(run, BOOST_POLL_MS)
    }

    private fun renderBoost(heat: Heat) {
        val boost = HeatNowModel.boostWords(text, heat, Instant.now())
        if (boost == null) {
            boostRow.visibility = View.GONE
            return
        }
        boostRow.visibility = View.VISIBLE
        say(boostLine, boost.text)
        if (boost.label == null) {
            boostButton.visibility = View.GONE
        } else {
            boostButton.visibility = View.VISIBLE
            boostButton.text = if (boostBusy && boost.action == "start") words.say("heat.boost.starting") else boost.label
            boostButton.isEnabled = boost.action != null && !boostBusy
        }
        say(boostErrorLine, boostError)
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
            listOf(dialRow, errorLine, measured, modes, awayLine, change, shadow, statusBlock, boostRow, ahead, aheadLine, today, notes).forEach { it.visibility = View.GONE }
            periodRow.visibility = View.GONE
            info.visibility = View.GONE
            planBox.panel.visibility = View.GONE
            useBox.panel.visibility = View.GONE
            loadBoxes.render(null)
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
        // On heating SpotNav can only read, the target is shown without − and +, and Home/Away is left out.
        val readOnly = HeatNowModel.readOnly(heat)
        lower.visibility = if (readOnly) View.GONE else View.VISIBLE
        raise.visibility = if (readOnly) View.GONE else View.VISIBLE
        lower.isEnabled = target - HeatNowModel.TARGET_STEP >= HeatNowModel.TARGET_MIN - 1e-9
        raise.isEnabled = target + HeatNowModel.TARGET_STEP <= HeatNowModel.TARGET_MAX + 1e-9
        say(errorLine, error)
        measured.text = HeatNowModel.measuredLine(text, heat)
        measured.visibility = View.VISIBLE
        modes.visibility = if (readOnly) View.GONE else View.VISIBLE
        val awayNow = heat.comfort.mode == "away"
        paint(home, !awayNow)
        paint(away, awayNow)
        val awayWords = if (readOnly) null else HeatNowModel.awayLine(text, heat)
        say(awayLine, awayWords)
        change.visibility = if (awayWords == null) View.GONE else View.VISIBLE
        // Read-only: one line on it in place of the Test line, which promises a control that cannot come.
        say(shadow, if (readOnly) HeatNowModel.readOnlyLine(words, heat) else if (heat.shadow) words.say("heat.shadow") else null)
        val live = HeatNowModel.live(text, heat)
        // The header says what the pump does; here only while the header is taken by SpotNav's own lowering or raise.
        val pump = if (live == null || live.first == "lower" || live.first == "raise") HeatNowModel.pumpLine(text, heat) else null
        say(statusBlock, (listOfNotNull(pump, HeatNowModel.wouldLine(text, heat), HeatNowModel.relayLine(text, heat, heat.now.coerceAtLeastNow())) + HeatNowModel.holdLines(text, heat).map { it.second }).joinToString("\n").ifEmpty { null })
        renderBoost(heat)
        val line = HeatNowModel.aheadLine(text, heat)
        ahead.visibility = if (line == null) View.GONE else View.VISIBLE
        ahead.show(HeatNowModel.aheadCells(heat), HeatNowModel.aheadAxis(text, heat))
        say(aheadLine, line)
        say(today, HeatNowModel.todayLine(text, heat))
        say(notes, HeatNowModel.noteLines(text, heat).joinToString("\n") { it.second }.ifEmpty { null })
        renderBoxes(heat)
        loadBoxes.render(heat)
    }

    /** Plan and Förbrukning under the shared picker; a box with nothing to show is left out, and the picker with them. */
    private fun renderBoxes(heat: Heat) {
        val now = System.currentTimeMillis()
        if (!boxesOpen) {
            boxesOpen = true
            readHistory(force = false)
        }
        info.visibility = if (HeatBoxes.details(heat)) View.VISIBLE else View.GONE
        val plan = planBox.render(heat, store.choice, now)
        val use = useBox.render(heat)
        val kinds = HeatBoxes.pickerKinds(heat, now)
        // With Förbrukning absent the picker offers days alone, and one of Månad or År left from before falls back to a day.
        periodRow.visibility = if (kinds.isEmpty() || !(plan || use || store.choice != PeriodChoice(HistoryPeriod.DAY))) View.GONE else View.VISIBLE
        val full = kinds.size > 1
        picker.visibility = if (full) View.VISIBLE else View.GONE
        dayPicker.visibility = if (full) View.GONE else View.VISIBLE
        val shownPicker = if (full) picker else dayPicker
        shownPicker.show(store.choice, todayDate())
    }

    /** The relay's hours are read against the clock, not the answer's moment: a page left open moves on. */
    private fun Instant.coerceAtLeastNow(): Instant = maxOf(this, Instant.now())

    private fun paint(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.setTextColor(if (selected) onAccent() else dark)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            if (selected) setColor(accent) else { setColor(0); setStroke(dp(1), colours.wire) }
        }
    }

    /** Text on the accent: dark on the light accent of a dark screen, white on the dark one of a light screen. */
    private fun onAccent(): Int {
        val c = accent
        val luminance = (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)) / 255.0
        return if (luminance > 0.55) 0xFF10151B.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun say(view: TextView, words: String?) {
        view.text = words.orEmpty()
        view.visibility = if (words == null) View.GONE else View.VISIBLE
    }

    /** A round outlined button as the Away button is, the accent on press; 48 dp to touch. */
    private fun stepButton(label: String, description: String, delta: Double) = Button(context).apply {
        text = label
        textSize = 22f
        isAllCaps = false
        stateListAnimator = null
        minWidth = dp(48)
        minHeight = dp(48)
        setPadding(0, 0, 0, 0)
        setTextColor(dark)
        contentDescription = words.say(description)
        fun ring(fill: Int, stroke: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(1), stroke)
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ring(SiteColours.mix(accent, cardBackground, 0.35f), accent))
            addState(intArrayOf(-android.R.attr.state_enabled), ring(0, SiteColours.mix(colours.wire, cardBackground, 0.5f)))
            addState(intArrayOf(), ring(0, colours.wire))
        }
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

        /** While the Boost button's start waits for its read-back: look again this often, this many times (the card's). */
        const val BOOST_POLL_MS = 5_000L
        const val BOOST_POLLS = 40
    }
}
