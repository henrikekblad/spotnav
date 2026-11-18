package se.sensnology.spotnav.ui.site

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardHeading
import se.sensnology.spotnav.ui.common.cardTitle
import java.util.Locale

/**
 * The Intäkt box of the house page (the card's Earnings tab, [HouseEarn]): its name, the headline "Intäkt X kr" (the sum of
 * the parts drawn), the stacked bars of the shared period with the parts as legend toggles (each with its sum), and the
 * notes of what each figure counts. The parts the legend hid are remembered per site. It always reads the history of the
 * period (a day's hours too); a period with nothing saved leaves the box out.
 */
internal class HouseEarnPanel(
    private val scope: ViewScope,
    private val store: HouseHistoryStore,
    private val preferences: SitePreferences,
    private val colours: SiteColours,
    private val text: SiteText,
    private val locale: Locale,
    private val site: () -> String,
    private val house: () -> House?
) : HousePeriodBox {
    private val words get() = text.words
    private var selectedBar: String? = null

    /** The headline "Intäkt X kr" is the title's text while there is a total; otherwise the box's name. */
    private val title = scope.cardTitle(words.say("house.tab.earn"))
    private val heading = TextView(scope.context).apply {
        textSize = 17f
        setTextColor(scope.dark)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val status = TextView(scope.context).apply {
        textSize = 14f
        setTextColor(scope.muted)
    }
    private val bars = StackedBarsView(scope, colours)
    private val notes = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(scope.muted)
    }

    override val panel: LinearLayout = LinearLayout(scope.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(scope.dp(14), scope.dp(12), scope.dp(14), scope.dp(12))
        background = GradientDrawable().apply {
            setColor(scope.cardBackground)
            cornerRadius = scope.dp(10).toFloat()
        }
        visibility = View.GONE
        addView(scope.cardHeading(R.drawable.ic_tab_earn, title), wide())
        addView(heading, wide().apply { topMargin = scope.dp(6) })
        addView(status, wide().apply { topMargin = scope.dp(10) })
        addView(bars, wide().apply { topMargin = scope.dp(8) })
        addView(notes, wide().apply { topMargin = scope.dp(6) })
    }

    init {
        bars.onSelect = { selectedBar = it }
        bars.onHiddenChange = { hidden ->
            preferences.setHidden(site(), HouseEarn.VIEW, hidden)
            render()
        }
    }

    override fun periodChanged() {
        selectedBar = null
    }

    override fun open(force: Boolean) = store.request(store.choice, needsHistory = true, force = force)

    override fun refresh() = open(force = store.choice.date == null)

    private fun hidden(): Set<String> = preferences.hidden(site(), HouseEarn.VIEW).filter { EarnPart.of(it) != null }.toSet()

    override fun render(): Boolean {
        val now = house() ?: return false
        listOf(heading, status, bars, notes).forEach { it.visibility = View.GONE }
        title.text = words.say("house.tab.earn")
        val shown = when (val loaded = store.history(store.choice)) {
            null, Load.Loading -> say("hist.loading")
            is Load.Failed -> say(if (loaded.unsupported) "hist.unsupported" else "hist.failed")
            is Load.Ready -> draw(loaded.value, now.money)
        }
        panel.visibility = if (shown) View.VISIBLE else View.GONE
        return shown
    }

    private fun say(key: String): Boolean {
        status.text = words.say(key)
        status.visibility = View.VISIBLE
        return true
    }

    /** The headline, bars and notes of a period; `false` where it has no part at all. */
    private fun draw(history: HouseHistory, fallback: House.Money?): Boolean {
        if (HouseEarn.parts(history).isEmpty()) return false
        val money = history.money ?: fallback
        val hidden = hidden()
        val total = HouseEarn.total(history, hidden)
        // The headline says "Intäkt X kr", so it is the title; the box's name stays where it does not (no part drawn).
        if (total != null) title.text = words.say("house.earn.total", "amount" to text.amount(total, money))
        else {
            heading.text = words.say("house.earn.noneShown")
            heading.visibility = View.VISIBLE
        }
        val drawn = HouseEarn.bars(text, locale, history, hidden, money)
        val spec = HouseEarn.series(history, colours, text, money)
        // With nothing to draw the legend stays, so a part can be turned on again; the bars do not.
        bars.show(
            BarsSpec(
                label = words.say("house.earn.label"), bars = drawn.orEmpty(), series = spec, hidden = hidden,
                scale = { text.amount(it, money) }, hint = words.say("hist.house.hint"), selected = selectedBar
            )
        )
        bars.visibility = View.VISIBLE
        val lines = HouseEarn.notes(text, history, hidden, money)
        if (lines.isNotEmpty()) {
            notes.text = lines.joinToString(" ")
            notes.visibility = View.VISIBLE
        }
        return true
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
