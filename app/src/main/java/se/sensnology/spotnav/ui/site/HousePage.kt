package se.sensnology.spotnav.ui.site

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardHeading
import se.sensnology.spotnav.ui.common.cardTitle
import se.sensnology.spotnav.ui.common.cardWords
import se.sensnology.spotnav.ui.common.headerTitle
import java.time.Instant

/**
 * The house page: the header ("Huset · Laddar från nätet"), and under it the Now box as the Home Assistant card's Now tab has
 * it: the live schematic, one sentence of what the battery does and does next, today's money, and the small notes; then
 * the Plan box where Home Assistant serves `get_plan` ([HousePlanBox]).
 *
 * It reads only while it is in view with the app in the foreground ([setInView]): the live flows every 10 s, the
 * house when it comes into view and then every minute ([SitePolling]). The live figures are dropped after three
 * reads in a row without an answer, so an old power is never shown as now; the house's own figures stay until the
 * next answer.
 *
 * Under the Now box the page stacks boxes, scrolled like the charger page's: one shared period picker and then a box
 * per period view (so far the Energy flow). A box with nothing to show is left out, and with no period box the
 * picker is too ([HouseBoxes]).
 */
internal class HousePage(
    scope: ViewScope,
    shell: ScreenShell,
    private val session: HaSession,
    private val memory: SiteMemory,
    private val preferences: SitePreferences,
    /** What the remembered choices are kept under until the house has said its site. */
    private val profileId: String,
    /** A charger page's own charger (its dashboard's id), for the Plan box's choices; `null` on a home's own screen. */
    private val ownChargerId: (() -> String?)? = null
) : ViewScope(scope), SitePage {
    override val kind = PageKind.HOUSE

    private val words = cardWords()
    private val text = SiteText(words, AppLanguageSettings.locale(context), AppLanguageSettings.numberLocale(context))
    private val colours = SiteColours(palette)
    private var failed = false
    private var testOpen = false
    private var houseInFlight = false
    private var liveInFlight = false
    private var liveMisses = 0

    // The period boxes under the Now box (the card's tabs, stacked as boxes): one shared period choice and one picker.
    private val history = HouseHistoryStore(session) { render() }
    private val locale = AppLanguageSettings.locale(context)
    private val choice: PeriodChoice get() = history.choice
    private val boxes: Map<HouseBox, HousePeriodBox> = HouseBox.entries.associateWith { box ->
        if (box == HouseBox.EARN) HouseEarnPanel(this, history, preferences, colours, text, locale, ::siteKey) { memory.house }
        else HouseHistoryPanel(this, box, history, preferences, colours, text, locale, ::siteKey) { memory.house }
    }
    private var boxesOpen = false
    /** The shared period moved, in the page's picker or the details sheet's: read it for every box and the sheet. */
    private fun periodChosen(next: PeriodChoice) {
        history.choice = next
        boxes.values.forEach { it.periodChanged() }
        openBoxes()
        if (details.isOpen) history.request(next, needsHistory = true, force = false)
        render()
    }

    private val picker = PeriodPickerView(this, words, locale) { periodChosen(it) }
    private val details = HouseDetailsDialog(this, shell, history, text, colours, locale, { memory.house }, ::todayDate, ::periodChosen)
    private val periodRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        visibility = View.GONE
        addView(picker, wide())
    }
    private val loads = LoadsDialog(this, session, text, colours)
    /** The Plan box, under Now as the card's Plan tab follows Now; not driven by the period picker. */
    private val planBox = HousePlanBox(this, session, memory, text, colours, preferences) {
        ownChargerId?.let { PlanChoiceScope.Charger(it(), session.namesCharger) } ?: PlanChoiceScope.Home
    }
    private val flowStatus = line().apply { setTextColor(muted) }
    private val sankey = SankeyView(context, text, colours.energy).apply { onOpen = ::openLoads }
    private val tiles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val flowHint = note()
    private val flowTitle = cardTitle("")
    private val flowGroup = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(cardBackground)
            cornerRadius = dp(10).toFloat()
        }
    }

    private val title = TextView(context).apply {
        textSize = 21f
        setTextColor(dark)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(40)
        setOnClickListener {
            testOpen = !testOpen
            render()
        }
        isClickable = false
    }
    private val testExplain = note()
    private val state = line().apply { setTextColor(muted) }
    private val schematic = HouseSchematicView(context, colours, AppLanguageSettings.locale(context))
    private val plan = line()
    private val money = line()
    private val notes = note()
    private val learning = note()
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(cardBackground)
            cornerRadius = dp(10).toFloat()
        }
    }

    /** The "i" that opens the details sheet (the card's header button): a period's cost, the shares of perfect, the forecasts' errors. */
    private val infoButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_info)
        setColorFilter(muted)
        background = null
        contentDescription = words.say("house.details.open")
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        visibility = View.GONE
        setOnClickListener { details.open() }
    }
    private val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(infoButton, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    override val view: View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The page's heading: its name and what the battery does now. The mark and the gear are the top row's.
            addView(headerRow, wide().apply { bottomMargin = dp(8) })
            addView(testExplain, wide().apply { bottomMargin = dp(8) })
            addView(body, wide().apply { bottomMargin = dp(14) })
            addView(planBox.panel, wide().apply { bottomMargin = dp(14) })
            // One picker above the period boxes, under the Now box; it drives every box below it.
            addView(periodRow, wide().apply { bottomMargin = dp(6) })
            addView(flowGroup, wide().apply { bottomMargin = dp(14) })
            boxes.values.forEach { addView(it.panel, wide().apply { bottomMargin = dp(14) }) }
        })
    }

    private val polling = SitePolling(
        timer = MainTimer(),
        now = { android.os.SystemClock.elapsedRealtime() },
        live = { true },
        readLive = ::readLive,
        readSlow = ::readHouse
    )

    init {
        body.addView(state, wide())
        body.addView(schematic, wide())
        body.addView(plan, wide().apply { topMargin = dp(10) })
        body.addView(money, wide().apply { topMargin = dp(6) })
        body.addView(notes, wide().apply { topMargin = dp(6) })
        body.addView(learning, wide().apply { topMargin = dp(4) })
        flowGroup.addView(cardHeading(R.drawable.ic_tab_flow, flowTitle), wide())
        flowGroup.addView(flowStatus, wide().apply { topMargin = dp(8) })
        flowGroup.addView(sankey, wide().apply { topMargin = dp(8) })
        flowGroup.addView(tiles, wide().apply { topMargin = dp(8) })
        flowGroup.addView(flowHint, wide().apply { topMargin = dp(8) })
        render()
    }

    /** Ask for what the visible period boxes draw (the house has answered, or the period changed). */
    private fun openBoxes(force: Boolean = false) {
        readFlow(force)
        val shown = HouseBoxes.shown(memory.house)
        boxes.forEach { (box, panel) -> if (box in shown) panel.open(force) }
    }

    override fun setInView(inView: Boolean) {
        schematic.running = inView
        if (inView) planBox.cameIntoView()
        if (inView) polling.start() else polling.stop()
    }

    /** The Energy flow's history of the period shown (today's own day is the house's answer); read through the shared store. */
    private fun readFlow(force: Boolean) {
        val c = choice
        if (!HouseBoxes.flow(memory.house) || (c.period == HistoryPeriod.DAY && c.date == null)) return
        history.request(c, needsHistory = true, force = force)
    }

    private fun flowHistory(): HouseHistory? = (history.history() as? Load.Ready)?.value
    private fun flowFailure(): FlowModel.FlowFailure? = (history.history() as? Load.Failed)?.let { FlowModel.FlowFailure(null, unsupportedVersion = it.unsupported) }

    private fun openLoads(kind: String) {
        val flow = FlowModel.content(choice, memory.house, flowHistory(), flowFailure()).let { (it as? FlowContent.Diagram)?.flow } ?: return
        val roles = SankeyLayout.shown(flow).filter { !it.source }.map { it.role }
        val running = choice.date == null || flowHistory()?.running == true
        this.loads.open(kind, choice, todayDate(), running, "car" in roles, "heat" in roles)
    }

    private fun todayDate(): java.time.LocalDate = memory.house?.zone?.let { java.time.LocalDate.now(it) } ?: java.time.LocalDate.now()

    private fun readHouse() {
        // The running period is read again with the house, every minute.
        if (boxesOpen) {
            readFlow(force = choice.date == null)
            boxes.values.forEach { it.refresh() }
        }
        // The plan is read with the house: when the page comes into view, then about once a minute.
        planBox.read()
        if (houseInFlight) return
        houseInFlight = true
        session.house(null) { outcome ->
            houseInFlight = false
            when (outcome) {
                is SiteRead.Read -> {
                    memory.house = outcome.value
                    failed = false
                }
                // What is held stays on screen; with nothing held, the page says it could not be read.
                else -> failed = memory.house == null
            }
            render()
        }
    }

    private fun readLive() {
        if (liveInFlight) return
        liveInFlight = true
        session.houseNow { outcome ->
            liveInFlight = false
            if (outcome is SiteRead.Read) {
                memory.live = outcome.value
                liveMisses = 0
            } else if (++liveMisses >= LIVE_MISSES) {
                memory.live = null
            }
            render()
        }
    }

    private fun render() {
        val house = memory.house
        val status = house?.let { HouseNowModel.status(words, it) }
        val test = status is HouseStatus.Test
        title.text = headerTitle(
            words.say("house.title"),
            when (status) {
                is HouseStatus.Said -> status.text
                HouseStatus.Test -> words.say("header.test")
                null -> null
            }
        )
        infoButton.visibility = if (house != null) View.VISIBLE else View.GONE
        planBox.render()
        details.refresh()
        // "Test" opens its own explanation, as on the card.
        title.isClickable = test
        testExplain.visibility = if (test && testOpen) View.VISIBLE else View.GONE
        if (test) {
            val lead = words.say("header.testExplain")
            testExplain.text = SpannableString("$lead ${words.say("house.test.explain")}").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // The boxes under Now: left out when there is nothing to show; the picker only with a box to drive.
        if (house == null) {
            state.text = words.say(if (failed) "house.failed" else "house.loading")
            state.visibility = View.VISIBLE
            listOf(schematic, plan, money, notes, learning).forEach { it.visibility = View.GONE }
            periodRow.visibility = View.GONE
            flowGroup.visibility = View.GONE
            boxes.values.forEach { it.panel.visibility = View.GONE }
            return
        }
        state.visibility = View.GONE
        renderBoxes(house)
        val live = memory.live
        schematic.visibility = View.VISIBLE
        schematic.show { wide -> HouseNowModel.schematic(text, house, live, wide) }
        say(plan, HouseNowModel.planSentence(text, house, Instant.now()))
        say(money, HouseNowModel.moneyLine(text, house))
        say(notes, HouseNowModel.notesLine(text, house, live))
        say(learning, HouseNowModel.learningLines(text, house).joinToString("\n").ifEmpty { null })
    }

    /** The Energy flow box: its heading, then the diagram, or the line saying why there is none, or the tiles. */
    private fun renderFlow(house: House) {
        flowTitle.text = words.say("house.tab.flow")
        val content = FlowModel.content(choice, house, flowHistory(), flowFailure())
        flowStatus.visibility = View.GONE
        sankey.visibility = View.GONE
        tiles.visibility = View.GONE
        flowHint.visibility = View.GONE
        fun status(key: String) {
            flowStatus.text = words.say(key)
            flowStatus.visibility = View.VISIBLE
        }
        when (content) {
            FlowContent.Loading -> status(if (house == null && failed) "house.failed" else if (house == null) "house.loading" else "hist.loading")
            is FlowContent.Failed -> status(if (content.unsupported) "hist.unsupported" else "hist.failed")
            FlowContent.NoMeters -> status("hist.house.noMeters")
            is FlowContent.Tiles -> {
                status(if (content.waiting) "house.flow.waiting" else "hist.house.noEnergy")
                tiles.removeAllViews()
                tiles.visibility = if (content.tiles.isEmpty()) View.GONE else View.VISIBLE
                content.tiles.chunked(2).forEach { pair ->
                    tiles.addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        pair.forEach { addView(tile(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                        if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                    }, wide().apply { topMargin = dp(6) })
                }
            }
            is FlowContent.Diagram -> {
                val labels = buildMap {
                    put("house", words.say("house.loads.open", "figures" to FlowModel.role(text, "house")))
                    if (content.flow.heatSources > 1) put("heat", words.say("house.loads.openHeat", "figures" to FlowModel.role(text, "heat")))
                }
                sankey.show(content.flow, content.past, labels)
                sankey.visibility = View.VISIBLE
                val heat = content.flow.heatSources > 1 && SankeyLayout.shown(content.flow).any { !it.source && it.role == "heat" }
                val opens = SankeyLayout.shown(content.flow).any { !it.source && it.role == "house" } || heat
                if (opens) {
                    flowHint.text = words.say(if (heat) "house.loads.tapHeat" else "house.loads.tap")
                    flowHint.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Decide each box: the picker is shown only when some period box is. */
    private fun renderBoxes(house: House) {
        if (!boxesOpen) {
            boxesOpen = true
            openBoxes()
        }
        val flowShown = HouseBoxes.flow(house)
        flowGroup.visibility = if (flowShown) View.VISIBLE else View.GONE
        if (flowShown) renderFlow(house)
        val shown = HouseBoxes.shown(house)
        var any = flowShown
        boxes.forEach { (box, panel) -> if (box in shown) any = panel.render() || any else panel.panel.visibility = View.GONE }
        periodRow.visibility = if (any) View.VISIBLE else View.GONE
        if (any) {
            val running = choice.period == HistoryPeriod.DAY && choice.date == null
            val through = if (running) house.energyFlow?.through?.let { text.clock(it, house.zone) } else null
            picker.show(choice, todayDate(), through)
        }
    }

    private fun tile(tile: FlowModel.Tile) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colours.text)
            this.text = FlowModel.kwh(this@HousePage.text, tile.kwh)
        })
        addView(TextView(context).apply {
            textSize = 13f
            setTextColor(muted)
            this.text = words.say(tile.word)
        })
    }

    private fun siteKey(): String = memory.house?.siteId ?: profileId

    private fun say(view: TextView, words: String?) {
        view.text = words.orEmpty()
        view.visibility = if (words == null) View.GONE else View.VISIBLE
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
        const val PAGE = "house"
        /** The live figures go after this many reads in a row without an answer (half a minute). */
        const val LIVE_MISSES = 3
    }
}
