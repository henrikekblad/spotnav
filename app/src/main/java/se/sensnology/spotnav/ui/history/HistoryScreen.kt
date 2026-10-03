package se.sensnology.spotnav.ui.history

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.chart.ChartTheme
import se.sensnology.spotnav.ha.client.SessionsOutcome
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.sessions.ChargeSessionRow
import se.sensnology.spotnav.ha.sessions.SessionDay
import se.sensnology.spotnav.ha.sessions.SessionsMonth
import se.sensnology.spotnav.ui.ScreenPart
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.headerSpinnerAdapter
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueRow
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.ui.haSession
import se.sensnology.spotnav.widget.WidgetChargerResolver
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The charge history of a paired charger, one month at a time: a month picker, the month's totals,
 * a bar for every day, and the month's charges. Everything is read from Home Assistant's `sessions`
 * action; nothing is stored on the phone, and the only thing it sends is a CSV to the share sheet.
 */
internal class HistoryScreen(shell: ScreenShell) : ScreenPart(shell) {
    private val numberLocale: Locale get() = AppLanguageSettings.numberLocale(context)
    private val textLocale: Locale get() = AppLanguageSettings.locale(context).let { if (it.language == "en") Locale.UK else it }

    private var session: HaSession? = null
    private var current: YearMonth? = null
    private var shown: YearMonth? = null
    private var data: SessionsMonth? = null
    private var loading = false
    private var exporting = false

    private lateinit var previous: TextView
    private lateinit var next: TextView
    private lateinit var picker: Spinner
    private lateinit var results: LinearLayout
    private lateinit var export: Button
    private var repopulating = false

    fun show() {
        beginScreen()
        showPanel(t(R.string.history_title))
        val settings = WidgetSettings.loadStored(context, widgetId) ?: WidgetSettings.load(context, widgetId)
        val profile = WidgetChargerResolver.resolve(settings, ChargerProfileStore.forContext(applicationContext))
            ?.takeIf { it.configured }
        if (profile == null) {
            content.addView(note(t(R.string.history_not_paired)))
            return
        }
        session = shell.haSession(profile)
        buildPicker()
        results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        export = Button(context).apply {
            text = t(R.string.history_export)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { exportCsv() }
        }
        content.addView(export, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })
        load(null)
    }

    // --- The month picker --------------------------------------------------------------------

    private fun arrow(glyph: String, description: String, go: () -> Unit) = TextView(context).apply {
        text = glyph
        textSize = 28f
        setTextColor(accent)
        gravity = Gravity.CENTER
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { go() }
    }

    private fun buildPicker() {
        previous = arrow("‹", t(R.string.history_previous_month)) {
            val month = shown; val now = current
            if (month != null && now != null) HistoryMonths.previous(month, now)?.let(::load)
        }
        next = arrow("›", t(R.string.history_next_month)) {
            val month = shown; val now = current
            if (month != null && now != null) HistoryMonths.next(month, now)?.let(::load)
        }
        picker = Spinner(context).apply {
            contentDescription = t(R.string.history_month_picker)
            adapter = headerSpinnerAdapter(emptyList())
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (repopulating) return
                    val choice = choices.getOrNull(position) ?: return
                    if (choice != shown) load(choice)
                }
            }
        }
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previous)
            addView(picker, weight())
            addView(next)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        paintPicker()
    }

    private var choices: List<YearMonth> = emptyList()

    private fun monthLabel(month: YearMonth): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", textLocale).format(month)
            .replaceFirstChar { it.titlecase(textLocale) }

    private fun paintPicker() {
        val month = shown
        val now = current
        choices = if (month != null && now != null) HistoryMonths.choices(now, data?.availableMonths.orEmpty(), month) else emptyList()
        repopulating = true
        picker.adapter = headerSpinnerAdapter(choices.map(::monthLabel))
        picker.setSelection(choices.indexOf(month).coerceAtLeast(0), false)
        picker.post { repopulating = false }
        val canBack = month != null && now != null && HistoryMonths.previous(month, now) != null
        val canForward = month != null && now != null && HistoryMonths.next(month, now) != null
        previous.isEnabled = canBack && !loading
        previous.alpha = if (previous.isEnabled) 1f else 0.35f
        next.isEnabled = canForward && !loading
        next.alpha = if (next.isEnabled) 1f else 0.35f
    }

    // --- Loading ---------------------------------------------------------------------------------

    /** Read [month] (`null` for the current month, whose name Home Assistant's answer then gives). */
    private fun load(month: YearMonth?) {
        val session = session ?: return
        loading = true
        if (month != null) shown = month
        paintPicker()
        export.visibility = View.GONE
        results.removeAllViews()
        results.addView(ProgressBar(context).apply { isIndeterminate = true })
        session.sessionsMonth(month) { outcome ->
            loading = false
            results.removeAllViews()
            when (outcome) {
                is SessionsOutcome.Loaded -> {
                    val loaded = outcome.value
                    data = loaded
                    shown = loaded.month
                    if (month == null) current = loaded.month
                    paintPicker()
                    render(loaded)
                }
                SessionsOutcome.Unsupported -> {
                    paintPicker()
                    results.addView(note(t(R.string.history_unsupported)))
                }
                SessionsOutcome.Failed -> {
                    paintPicker()
                    results.addView(note(t(R.string.history_unreadable)))
                    results.addView(Button(context).apply {
                        text = t(R.string.history_retry)
                        isAllCaps = false
                        setOnClickListener { load(month ?: shown) }
                    })
                }
            }
        }
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(muted)
        setPadding(0, dp(12), 0, dp(12))
    }

    // --- The month -----------------------------------------------------------------------------

    private fun render(month: SessionsMonth) {
        val figures = HistoryFigures(numberLocale, month.summary)
        addSummary(month, figures)
        addChart(month, figures)
        if (month.sessions.isNotEmpty()) addSessions(month, figures)
        export.visibility = View.VISIBLE
        export.isEnabled = !exporting
    }

    private fun readRow(parent: LinearLayout, label: String, value: String?) {
        if (value == null) return
        valueRow(parent, label, valueLabel().apply { text = value })
    }

    private fun addSummary(month: SessionsMonth, figures: HistoryFigures) {
        val summary = month.summary
        val card = card(results, monthLabel(month.month), R.drawable.ic_plan_clock)
        if (summary.isEmpty) {
            card.body.addView(note(t(R.string.history_none)))
            return
        }
        readRow(card.body, t(R.string.history_energy), figures.energy(summary.energyKwh))
        readRow(card.body, t(R.string.history_cost), figures.cost(summary.cost))
        readRow(card.body, t(R.string.history_average_price), figures.price(summary.averagePriceMinorPerKwh))
        readRow(card.body, t(R.string.history_sessions), summary.sessions.toString())
        readRow(card.body, t(R.string.history_solar), figures.share(summary.solarShare))
        readRow(card.body, t(R.string.history_saving), figures.saving(summary.savings))
        val notes = listOfNotNull(
            if (summary.estimated) t(R.string.history_estimated_note) else null,
            if (summary.savings != null) t(R.string.history_saving_note) else null
        )
        if (notes.isNotEmpty()) card.body.addView(TextView(context).apply {
            text = notes.joinToString(" ")
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun addChart(month: SessionsMonth, figures: HistoryFigures) {
        val bars = DayBars.of(month.days)
        val card = card(results, t(R.string.history_chart_title), R.drawable.ic_card_plan)
        val detail = TextView(context).apply {
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(8), 0, 0)
            text = t(R.string.history_chart_hint)
        }
        val chart = DayBarsView(context).apply {
            contentDescription = t(
                R.string.history_chart_description, monthLabel(month.month), figures.energy(month.summary.energyKwh)
            )
            show(
                bars = bars,
                topLabel = bars.maxOfOrNull { it.day.energyKwh }?.takeIf { it > 0.0 }?.let { figures.energy(it) }.orEmpty(),
                colour = { bar ->
                    bar.price?.let { DayBars.colour(it, ChartTheme.Dark.cheap, ChartTheme.Dark.expensive) } ?: accent
                },
                text = dark,
                muted = muted,
                outline = dark
            )
            onSelect = { index ->
                val bar = index?.let { bars.getOrNull(it) }
                detail.setTextColor(if (bar == null) muted else dark)
                detail.text = bar?.let { dayText(it.day, figures) } ?: t(R.string.history_chart_hint)
            }
        }
        card.body.addView(chart, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        card.body.addView(detail)
    }

    private fun dayName(day: SessionDay): String =
        DateTimeFormatter.ofPattern("EEE d MMM", textLocale).format(day.date)

    /** One day's figures: the date, then whatever Home Assistant could state for it. */
    private fun dayText(day: SessionDay, figures: HistoryFigures): String {
        if (day.energyKwh <= 0.0) return "${dayName(day)}: ${t(R.string.history_day_none)}"
        val facts = listOfNotNull(
            figures.energy(day.energyKwh),
            figures.cost(day.cost),
            figures.price(day.averagePriceMinorPerKwh),
            figures.share(day.solarShare)?.let { t(R.string.history_day_solar, it) }
        )
        return "${dayName(day)}: ${facts.joinToString(" · ")}"
    }

    private fun addSessions(month: SessionsMonth, figures: HistoryFigures) {
        val card = card(results, t(R.string.history_sessions_title), R.drawable.ic_card_charger)
        month.sessions.forEach { row ->
            card.body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(context).apply {
                    text = sessionTitle(row)
                    textSize = 15f
                    setTextColor(dark)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(context).apply {
                    text = sessionFacts(row, figures)
                    textSize = 13f
                    setTextColor(muted)
                })
            })
        }
    }

    private fun sessionTitle(row: ChargeSessionRow): String {
        val day = DateTimeFormatter.ofPattern("EEE d MMM", textLocale).format(row.start)
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        val times = row.end?.let { "${clock.format(row.start)}–${clock.format(it)}" } ?: clock.format(row.start)
        return "$day $times"
    }

    private fun sessionFacts(row: ChargeSessionRow, figures: HistoryFigures): String {
        val totals = HistoryFigures(numberLocale, data!!.summary)
        return listOfNotNull(
            (if (row.estimated) "~" else "") + totals.energy(row.energyKwh),
            totals.cost(row.cost),
            totals.price(row.averagePriceMinorPerKwh),
            totals.share(row.solarShare)?.let { t(R.string.history_day_solar, it) },
            row.vehicle
        ).joinToString(" · ").ifEmpty { figures.energy(row.energyKwh) }
    }

    // --- Export ------------------------------------------------------------------------------------

    private fun exportCsv() {
        val month = shown ?: return
        val session = session ?: return
        if (exporting) return
        exporting = true
        export.isEnabled = false
        session.sessionsCsv(month) { outcome ->
            exporting = false
            export.isEnabled = true
            when (outcome) {
                is SessionsOutcome.Loaded -> share(outcome.value.filename, outcome.value.csv)
                else -> export.text = t(R.string.history_export_failed)
            }
        }
    }

    /** Hand the CSV to Android's share sheet as a file. */
    private fun share(filename: String, csv: String) {
        val uri: Uri = CsvExportProvider.write(applicationContext, filename, csv)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, CsvExportProvider.safeName(filename))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(CsvExportProvider.safeName(filename), uri)
        }
        startActivity(Intent.createChooser(send, t(R.string.history_export)))
        export.text = t(R.string.history_export)
    }
}
