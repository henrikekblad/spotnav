package se.sensnology.spotnav.ui.site

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.ha.heat.HeatHistory
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import java.time.LocalDate
import java.util.Locale

/**
 * The heat details sheet, opened from the "i" in the heat page's header (the card's history dialog): the page's own
 * period picker over a day, month or year (a month to begin with, as the card), and under it every section open:
 * the use in figures, kWh per degree day with its trend, comfort, the shadow comparison, a day's review, how well the
 * house model fits, and a day's hot-water runs ([HeatDetailsModel]). Read when opened and when the period changes, and
 * every minute while the running period is shown.
 */
internal class HeatDetailsDialog(
    private val scope: ViewScope,
    private val session: HaSession,
    private val text: SiteText,
    private val colours: SiteColours,
    private val locale: Locale,
    private val today: () -> LocalDate
) {
    private val words = text.words
    private var dialog: AlertDialog? = null
    private val main = Handler(Looper.getMainLooper())
    private var store: HeatHistoryStore? = null
    private var choice = PeriodChoice(HistoryPeriod.MONTH)
    private var content: LinearLayout? = null
    private var picker: PeriodPickerView? = null
    private val minute = object : Runnable {
        override fun run() {
            if (dialog?.isShowing != true) return
            if (choice.date == null) store?.request(choice, force = true)
            main.postDelayed(this, MINUTE_MS)
        }
    }

    fun open() {
        dismiss()
        val held = HeatHistoryStore(session) { render() }
        store = held
        choice = PeriodChoice(HistoryPeriod.MONTH)
        val column = LinearLayout(scope.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(scope.dp(8), scope.dp(8), scope.dp(8), scope.dp(4))
        }
        val bar = PeriodPickerView(scope, words, locale) { next ->
            choice = next
            held.request(next)
            render()
        }
        picker = bar
        val results = LinearLayout(scope.context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(bar, wide())
        column.addView(results, wide().apply { topMargin = scope.dp(8) })
        content = results
        dialog = AlertDialog.Builder(scope.context)
            .setTitle(words.say("hist.title.heat"))
            .setView(ScrollView(scope.context).apply { addView(column) })
            .setPositiveButton(scope.t(android.R.string.ok), null)
            .setOnDismissListener { main.removeCallbacks(minute) }
            .create().also { it.show(); it.useFullWidth() }
        held.request(choice)
        render()
        main.postDelayed(minute, MINUTE_MS)
    }

    fun dismiss() {
        main.removeCallbacks(minute)
        dialog?.dismiss()
        dialog = null
    }

    private fun render() {
        val results = content ?: return
        if (dialog?.isShowing != true) return
        picker?.show(choice, today())
        results.removeAllViews()
        when (val loaded = store?.history(choice)) {
            null, Load.Loading -> results.addView(note(words.say("hist.loading")))
            is Load.Failed -> results.addView(note(words.say(if (loaded.unsupported) "hist.unsupported" else "hist.failed")))
            is Load.Ready -> fill(results, loaded.value)
        }
    }

    private fun fill(results: LinearLayout, answer: HeatHistory) {
        for (section in HeatDetailsModel.sections(text, answer)) {
            results.addView(TextView(scope.context).apply {
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(colours.text)
                this.text = section.title
            }, wide().apply { topMargin = scope.dp(14) })
            for (row in section.rows) {
                when (row) {
                    is DetailRow.Figure -> results.addView(figure(row), wide().apply { topMargin = scope.dp(2) })
                    is DetailRow.Note -> results.addView(note(row.text), wide().apply { topMargin = scope.dp(4) })
                    is DetailRow.Bars -> results.addView(costBars(row), wide().apply { topMargin = scope.dp(4) })
                    is DetailRow.Trend -> {
                        results.addView(note(row.title), wide().apply { topMargin = scope.dp(8) })
                        results.addView(trend(row), wide().apply { topMargin = scope.dp(4) })
                    }
                }
            }
        }
        results.addView(note(HeatDetailsModel.footnote(text)), wide().apply { topMargin = scope.dp(14) })
    }

    /** The cost bars: the filled cost in the heat's colour, the plan's shadow saving hatched on top. */
    private fun costBars(row: DetailRow.Bars) = StackedBarsView(scope, colours).apply {
        val heat = colours.of(Tone.HEAT)
        show(
            BarsSpec(
                label = row.label, bars = row.bars, series = row.series.map { BarSeries(it.key, it.label, heat, hatched = it.hatched) }
                    .ifEmpty { listOf(BarSeries(HeatDetailsModel.COST, "", heat)) },
                scale = row.scale, hint = row.hint, toggles = false
            )
        )
    }

    private fun trend(row: DetailRow.Trend) = StackedBarsView(scope, colours, plotHeightDp = 110).apply {
        show(
            BarsSpec(
                label = row.title, bars = row.bars, series = listOf(BarSeries(HeatDetailsModel.TREND, "", colours.of(Tone.HEAT))),
                scale = { v -> "${text.fixed(v, 2)} ${row.unit}" }, hint = words.say("hist.heat.trendHint"), toggles = false
            )
        )
    }

    private fun figure(row: DetailRow.Figure) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(colours.text)
            this.text = row.name
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            textSize = 14f
            gravity = Gravity.END
            setTextColor(colours.text)
            this.text = row.value
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = scope.dp(8) })
    }

    private fun note(sentence: String) = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(colours.muted)
        text = sentence
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
