package se.sensnology.spotnav.ui.site

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardHeading
import se.sensnology.spotnav.ui.common.cardTitle
import java.util.Locale

private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** A box like the Now box's: a card with its heading and its picture. */
private fun box(scope: ViewScope): LinearLayout = LinearLayout(scope.context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(scope.dp(14), scope.dp(12), scope.dp(14), scope.dp(12))
    background = GradientDrawable().apply {
        setColor(scope.cardBackground)
        cornerRadius = scope.dp(10).toFloat()
    }
    visibility = View.GONE
}

private fun heading(scope: ViewScope, words: String, marker: Int) = scope.cardHeading(marker, scope.cardTitle(words))

/**
 * The Plan box (the card's Plan tab): the day's chart, today's from the page's own answer with the plan ahead (the hot
 * water's planned boosts and peaks on it) and under it yesterday's review and the hot water's lines (the gate's
 * progress, level A's blocks, yesterday per peak), a past day's from its history. Day only: while the shared picker is on Månad or År the box is
 * left out. Left out too where the day has nothing to draw.
 */
internal class HeatPlanPanel(
    private val scope: ViewScope,
    private val store: HeatHistoryStore,
    private val text: SiteText
) {
    private val words get() = text.words
    private val chart = HeatDayChart(scope, SiteColours(scope.palette))
    private val status = TextView(scope.context).apply {
        textSize = 14f
        setTextColor(scope.muted)
    }
    private val review = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(scope.muted)
    }
    private var selectedMs: Long? = null

    val panel: LinearLayout = box(scope).apply {
        addView(heading(scope, words.say("heat.tab.plan"), R.drawable.ic_plan_clock), wide())
        addView(status, wide().apply { topMargin = scope.dp(8) })
        addView(chart, wide().apply { topMargin = scope.dp(8) })
        addView(review, wide().apply { topMargin = scope.dp(8) })
    }

    init {
        chart.onSelect = { selectedMs = it }
    }

    /** The period changed: forget the time chosen on the old one. */
    fun periodChanged() {
        selectedMs = null
    }

    /** Draw the box; `false` where it is left out. */
    fun render(heat: Heat?, choice: PeriodChoice, nowMs: Long): Boolean {
        status.visibility = View.GONE
        chart.visibility = View.GONE
        review.visibility = View.GONE
        val shown = draw(heat, choice, nowMs)
        panel.visibility = if (shown) View.VISIBLE else View.GONE
        return shown
    }

    private fun draw(heat: Heat?, choice: PeriodChoice, nowMs: Long): Boolean {
        if (heat == null || choice.period != HistoryPeriod.DAY || !HeatBoxes.plan(heat, nowMs)) return false
        if (choice.date == null) {
            val today = HeatPlanModel.today(heat, nowMs, text) ?: return false
            drawChart(today)
            val lines = HeatPlanModel.reviewLines(text, heat) + HeatPlanModel.hotWaterLines(text, heat)
            review.text = lines.joinToString("\n") { it.second }
            review.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            return true
        }
        return when (val loaded = store.history(choice)) {
            null, Load.Loading -> say("hist.loading")
            is Load.Failed -> say(if (loaded.unsupported) "hist.unsupported" else "hist.failed")
            is Load.Ready -> HeatPlanModel.past(loaded.value)?.let { drawChart(it); true } ?: false
        }
    }

    private fun say(key: String): Boolean {
        status.text = words.say(key)
        status.visibility = View.VISIBLE
        return true
    }

    private fun drawChart(day: DayChart) {
        chart.show(
            HeatDaySpec(
                chart = day, label = words.say("heat.chart.label"), hint = words.say("heat.chart.hint"),
                words = { HeatPlanModel.figures(text, day, it) }, legend = HeatPlanModel.legend(text, day),
                highEdge = HeatNowModel.degrees(text, day.highC), lowEdge = HeatNowModel.degrees(text, day.lowC),
                selectedMs = selectedMs
            )
        )
        chart.visibility = View.VISIBLE
    }
}

/**
 * The Förbrukning box (the card's Use tab): what the heating used over the period shown, a stack per bar by part, in
 * kWh or money. The headline is what SpotNav saved on heat, else the total; the parts are legend toggles and the unit
 * a switch, both remembered per site through [preferences]. Left out where the period has no heat meter or no energy.
 */
internal class HeatUsePanel(
    private val scope: ViewScope,
    private val store: HeatHistoryStore,
    private val preferences: SitePreferences,
    private val text: SiteText,
    private val locale: Locale,
    private val site: () -> String
) {
    private val words get() = text.words
    private val colours = SiteColours(scope.palette)
    private val headline = TextView(scope.context).apply {
        textSize = 17f
        setTextColor(scope.dark)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val sub = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(scope.muted)
    }
    private val status = TextView(scope.context).apply {
        textSize = 14f
        setTextColor(scope.muted)
    }
    private val notes = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(scope.muted)
    }
    private val bars = StackedBarsView(scope, colours)
    private var selectedBar: String? = null
    private val kwhButton = unitButton("kWh") { setMoney(false) }
    private val moneyButton = unitButton("") { setMoney(true) }
    private val units = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(scope.dp(2), scope.dp(2), scope.dp(2), scope.dp(2))
        background = GradientDrawable().apply {
            setColor(SiteColours.mix(scope.muted, scope.cardBackground, 0.14f))
            cornerRadius = scope.dp(9).toFloat()
        }
        contentDescription = words.say("heat.use.unit")
        addView(kwhButton)
        addView(moneyButton)
    }
    private val head = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(headline, wide())
            addView(sub, wide())
        }
        addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(units, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = scope.dp(8) })
    }

    val panel: LinearLayout = box(scope).apply {
        addView(heading(scope, words.say("heat.tab.use"), R.drawable.ic_tab_graph), wide())
        addView(status, wide().apply { topMargin = scope.dp(8) })
        addView(head, wide().apply { topMargin = scope.dp(6) })
        addView(bars, wide().apply { topMargin = scope.dp(8) })
        addView(notes, wide().apply { topMargin = scope.dp(8) })
    }

    init {
        bars.onSelect = { selectedBar = it }
        bars.onHiddenChange = { next ->
            // Only parts the answer has are kept, so a zone that went away is not remembered for ever.
            preferences.setHidden(site(), CHART, next.filter { it in shownKeys }.toSet())
            redraw()
        }
    }

    private var lastHeat: Heat? = null
    private var shownKeys: Set<String> = emptySet()

    private fun unitButton(label: String, onTap: () -> Unit) = TextView(scope.context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        minimumHeight = scope.dp(32)
        minimumWidth = scope.dp(44)
        setPadding(scope.dp(9), 0, scope.dp(9), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun setMoney(on: Boolean) {
        preferences.setFlag(site(), FLAG_MONEY, on)
        redraw()
    }

    private fun wantMoney() = preferences.flag(site(), FLAG_MONEY, false)

    /** The period changed: forget the bar chosen on the old one. */
    fun periodChanged() {
        selectedBar = null
    }

    private fun redraw() {
        render(lastHeat)
    }

    /** Draw the box; `false` where it is left out. */
    fun render(heat: Heat?): Boolean {
        lastHeat = heat
        status.visibility = View.GONE
        head.visibility = View.GONE
        bars.visibility = View.GONE
        notes.visibility = View.GONE
        val shown = draw(heat)
        panel.visibility = if (shown) View.VISIBLE else View.GONE
        return shown
    }

    private fun draw(heat: Heat?): Boolean {
        if (!HeatBoxes.use(heat)) return false
        return when (val loaded = store.history()) {
            null, Load.Loading -> say("hist.loading")
            is Load.Failed -> say(if (loaded.unsupported) "hist.unsupported" else "hist.failed")
            is Load.Ready -> drawAnswer(loaded.value)
        }
    }

    private fun say(key: String): Boolean {
        status.text = words.say(key)
        status.visibility = View.VISIBLE
        return true
    }

    private fun drawAnswer(answer: se.sensnology.spotnav.ha.heat.HeatHistory): Boolean {
        if (HeatUseModel.content(answer) != HeatUseContent.Ready) return false
        shownKeys = answer.parts.map { it.key }.toSet()
        val hidden = preferences.hidden(site(), CHART)
        val money = HeatUseModel.inMoney(answer, wantMoney())
        val line = HeatUseModel.headline(text, answer, hidden, money)
        headline.text = line.head
        sub.text = line.sub.orEmpty()
        sub.visibility = if (line.sub == null) View.GONE else View.VISIBLE
        units.visibility = if (HeatUseModel.hasMoney(answer)) View.VISIBLE else View.GONE
        moneyButton.text = answer.money?.majorUnit ?: answer.money?.currency.orEmpty()
        moneyButton.contentDescription = words.say("heat.use.unitMoney", "unit" to moneyButton.text.toString())
        paint(kwhButton, !money)
        paint(moneyButton, money)
        head.visibility = View.VISIBLE
        val series = HeatUseModel.series(text, answer, money) { slotColour(it) }
        bars.show(
            BarsSpec(
                label = words.say("heat.use.label"), bars = HeatUseModel.bars(text, locale, answer, hidden, money), series = series,
                hidden = hidden, scale = HeatUseModel.scale(text, answer, money), hint = words.say("hist.heat.hint"), selected = selectedBar
            )
        )
        bars.visibility = View.VISIBLE
        val said = HeatUseModel.notes(text, answer, money)
        notes.text = said.joinToString(" ")
        notes.visibility = if (said.isEmpty()) View.GONE else View.VISIBLE
        return true
    }

    private fun paint(button: TextView, pressed: Boolean) {
        button.isSelected = pressed
        button.setTextColor(if (pressed) scope.dark else scope.muted)
        button.typeface = if (pressed) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        button.background = if (!pressed) null else GradientDrawable().apply {
            setColor(scope.cardBackground)
            cornerRadius = scope.dp(7).toFloat()
        }
    }

    private fun slotColour(slot: String): Int = when (slot) {
        "pump" -> colours.of(Tone.HEAT)
        "hot_water" -> 0xFF2A8FBD.toInt()
        "zone-0" -> 0xFFE0A030.toInt()
        "zone-1" -> 0xFF8E6BBF.toInt()
        "zone-2" -> 0xFF4C9F70.toInt()
        "zone-3" -> 0xFFC06C9A.toInt()
        "pool" -> 0xFF2FA39A.toInt()
        "spa" -> 0xFFC4703F.toInt()
        "sauna" -> 0xFF8C5A3A.toInt()
        else -> SiteColours.mix(scope.muted, scope.cardBackground, 0.7f)
    }

    companion object {
        /** The legend's remembered choice and the unit switch, per site (the card's `heat.use`). */
        const val CHART = "heat.use"
        const val FLAG_MONEY = "heat.use.money"
    }
}
