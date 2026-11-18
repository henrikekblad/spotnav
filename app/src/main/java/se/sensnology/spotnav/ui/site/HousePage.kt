package se.sensnology.spotnav.ui.site

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords
import se.sensnology.spotnav.ui.common.headerTitle
import java.time.Instant

/** The house page's tabs, in the Home Assistant card's fixed order. [key] is what is remembered. */
internal enum class HouseTab(val key: String, val icon: Int) {
    NOW("now", R.drawable.ic_tab_now),
    FLOW("flow", R.drawable.ic_tab_flow),
    GRAPH("graph", R.drawable.ic_tab_graph),
    EARN("earn", R.drawable.ic_tab_earn),
    PRICE("price", R.drawable.ic_tab_price);

    companion object {
        /** The tabs the page has so far: Now. Energy flow, Energy chart, Earnings and Price come with their steps. */
        val BUILT: List<HouseTab> = listOf(NOW)
    }
}

/**
 * The house page: the header ("Huset · Laddar från nätet"), and under it the Now tab as the Home Assistant card has
 * it: the live schematic, one sentence of what the battery does and does next, today's money, and the small notes.
 *
 * It reads only while it is in view with the app in the foreground ([setInView]): the live flows every 10 s, the
 * house when it comes into view and then every minute ([SitePolling]). The live figures are dropped after three
 * reads in a row without an answer, so an old power is never shown as now; the house's own figures stay until the
 * next answer.
 */
internal class HousePage(
    scope: ViewScope,
    private val session: HaSession,
    private val memory: SiteMemory,
    private val preferences: SitePreferences,
    /** What the remembered choices are kept under until the house has said its site. */
    private val profileId: String
) : ViewScope(scope), SitePage {
    override val kind = PageKind.HOUSE

    private val words = cardWords()
    private val text = SiteText(words, AppLanguageSettings.locale(context), AppLanguageSettings.numberLocale(context))
    private val colours = SiteColours(palette)
    private val tabs = HouseTab.BUILT
    private var tab = HouseTab.NOW
    private var failed = false
    private var testOpen = false
    private var houseInFlight = false
    private var liveInFlight = false
    private var liveMisses = 0

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
    private var tabRow: TabRowView? = null

    // The period boxes under the Now box (the card's tabs, stacked as boxes in the app): one shared picker drives them.
    private val history = HouseHistoryStore(session) { render() }
    private val locale = AppLanguageSettings.locale(context)
    private val boxes = HouseBox.entries.associateWith { box ->
        HouseHistoryPanel(this, box, history, preferences, colours, text, locale, ::siteKey) { memory.house }
    }
    private var boxesOpen = false
    private val picker = PeriodPickerView(this, words, locale) { next ->
        history.choice = next
        boxes.values.forEach { it.periodChanged() }
        openBoxes()
        render()
    }
    private val periodRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        visibility = View.GONE
        addView(picker, wide())
    }

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
            // The page's heading: its name and what the battery does now. The mark and the gear are the top row's.
            addView(title, wide().apply { bottomMargin = dp(8) })
            addView(testExplain, wide().apply { bottomMargin = dp(8) })
            addView(body, wide().apply { bottomMargin = dp(14) })
            // One picker above the period boxes, under the Now box; it drives every box below it.
            addView(periodRow, wide().apply { bottomMargin = dp(6) })
            boxes.values.forEach { addView(it.panel, wide().apply { bottomMargin = dp(14) }) }
        })
    }

    private val polling = SitePolling(
        timer = MainTimer(),
        now = { android.os.SystemClock.elapsedRealtime() },
        live = { tab == HouseTab.NOW },
        readLive = ::readLive,
        readSlow = ::readHouse
    )

    init {
        tab = preferences.tab(siteKey(), PAGE, tabs.map { it.key })?.let { key -> tabs.first { it.key == key } } ?: tabs.first()
        // A row of one tab says nothing: it is drawn from the second tab on.
        if (tabs.size > 1) {
            tabRow = TabRowView(
                this,
                tabs.map { TabSpec(it.key, words.say("house.tab.${it.key}"), words.say("house.tab.short.${it.key}"), it.icon) },
                tab.key,
                colours.wire
            ) { key -> choose(tabs.first { it.key == key }) }.apply { contentDescription = words.say("house.tabs") }
            body.addView(tabRow, wide().apply { bottomMargin = dp(10) })
        }
        body.addView(state, wide())
        body.addView(schematic, wide())
        body.addView(plan, wide().apply { topMargin = dp(10) })
        body.addView(money, wide().apply { topMargin = dp(6) })
        body.addView(notes, wide().apply { topMargin = dp(6) })
        body.addView(learning, wide().apply { topMargin = dp(4) })
        render()
    }

    /** Ask for what the visible period boxes draw (the house has answered, or the period changed). */
    private fun openBoxes(force: Boolean = false) {
        val shown = HouseBoxes.shown(memory.house)
        boxes.forEach { (box, panel) -> if (box in shown) panel.open(force) }
    }

    override fun setInView(inView: Boolean) {
        schematic.running = inView
        if (inView) polling.start() else polling.stop()
    }

    private fun siteKey(): String = memory.house?.siteId ?: profileId

    private fun choose(next: HouseTab) {
        tab = next
        preferences.setTab(siteKey(), PAGE, next.key)
        tabRow?.select(next.key)
        render()
    }

    private fun readHouse() {
        // The running period is read again with the house, every minute.
        if (boxesOpen) boxes.values.forEach { it.refresh() }
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
        // "Test" opens its own explanation, as on the card.
        title.isClickable = test
        testExplain.visibility = if (test && testOpen) View.VISIBLE else View.GONE
        if (test) {
            val lead = words.say("header.testExplain")
            testExplain.text = SpannableString("$lead ${words.say("house.test.explain")}").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (house == null) {
            state.text = words.say(if (failed) "house.failed" else "house.loading")
            state.visibility = View.VISIBLE
            listOf(schematic, plan, money, notes, learning).forEach { it.visibility = View.GONE }
            periodRow.visibility = View.GONE
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

    private fun renderBoxes(house: House) {
        if (!boxesOpen) {
            boxesOpen = true
            openBoxes()
        }
        val shown = HouseBoxes.shown(house)
        var any = false
        boxes.forEach { (box, panel) -> if (box in shown) any = panel.render() || any else panel.panel.visibility = View.GONE }
        periodRow.visibility = if (any) View.VISIBLE else View.GONE
        if (any) picker.show(history.choice, java.time.LocalDate.now(house.zone), null)
    }

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
