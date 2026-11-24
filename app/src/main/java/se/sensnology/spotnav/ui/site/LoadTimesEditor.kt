package se.sensnology.spotnav.ui.site

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.ha.heat.HeatLoads
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine
import se.sensnology.spotnav.ui.common.openEditor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * The spa's bath times or the sauna's sessions, as the card's times editor (`heat-editors.ts` `sessionsEditor`): up to
 * four weekly times, each a clock time and its weekdays (Monday first), "Lägg till badtid", and one bath or session on
 * a date. Save sends what changed ([LoadTimes.check]) through [send]; a refusal keeps the dialog open with its words.
 */
internal class LoadTimesEditor(
    scope: ViewScope,
    private val words: CardWords,
    private val load: HeatLoads.Deadline,
    private val now: Instant,
    private val zone: ZoneId,
    private val locale: java.util.Locale,
    private val send: (List<org.json.JSONObject>, (String?) -> Unit) -> Unit
) : ViewScope(scope) {
    private val kind = load.kind

    private class Row(var time: String, val days: List<CheckBox>, val view: LinearLayout)

    private val rows = mutableListOf<Row>()
    private lateinit var list: LinearLayout
    private lateinit var add: Button

    fun open() {
        val body = editorBody()
        body.addView(muted(words.say("heat.$kind.timesHelp")))
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        add = Button(context).apply {
            text = words.say("heat.$kind.addTime")
            isAllCaps = false
            setOnClickListener { addRow(LoadTimes.fresh(kind)) }
        }
        load.weekly.forEach(::addRow)
        body.addView(add, wrap())
        paintAdd()

        // The one-off: a date and a time, or none.
        var (date, time) = LoadTimes.onceStart(kind, load.once, now, zone)
        val onceBox = CheckBox(context).apply { text = words.say("heat.$kind.onceLabel"); textSize = 16f; isChecked = load.once != null }
        val dateButton = Button(context).apply { isAllCaps = false }
        val timeButton = Button(context).apply { isAllCaps = false }
        fun paintOnce() {
            dateButton.text = "${words.say("heat.away.date")}: ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))}"
            timeButton.text = "${words.say("heat.spa.time")}: ${LoadTimes.clock(time.hour, time.minute)}"
            val shown = if (onceBox.isChecked) android.view.View.VISIBLE else android.view.View.GONE
            dateButton.visibility = shown
            timeButton.visibility = shown
        }
        dateButton.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); paintOnce() }, date.year, date.monthValue - 1, date.dayOfMonth)
                .apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
        }
        timeButton.setOnClickListener {
            TimePickerDialog(context, { _, h, min -> time = LocalTime.of(h, min); paintOnce() }, time.hour, time.minute, true).show()
        }
        onceBox.setOnCheckedChangeListener { _, _ -> paintOnce() }
        paintOnce()
        body.addView(onceBox, wrap(top = 10))
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dateButton)
            addView(timeButton)
        }, wrap())

        val error = errorLine().also { body.addView(it) }
        openEditor(words.say("heat.$kind.times"), body, error, positive = words.say("settings.save")) { done ->
            val weekly = rows.map { row -> HeatLoads.Session(row.days.withIndex().filter { it.value.isChecked }.map { it.index + 1 }, row.time) }
            when (val checked = LoadTimes.check(kind, load.weekly, load.once, weekly, if (onceBox.isChecked) date to time else null, Instant.now(), zone)) {
                is LoadTimes.Checked.Invalid -> done(words.say(checked.key))
                is LoadTimes.Checked.Presses -> send(checked.bodies, done)
            }
        }
    }

    private fun addRow(item: HeatLoads.Session) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        view.addView(TextView(context).apply { text = words.say("heat.$kind.weeklyTime"); textSize = 15f; setTextColor(dark) })
        val timeButton = Button(context).apply { isAllCaps = false }
        val days = (1..7).map { day ->
            CheckBox(context).apply {
                text = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
                isChecked = day in item.weekdays
            }
        }
        val row = Row(item.time, days, view)
        fun paintTime() { timeButton.text = "${words.say("heat.spa.time")}: ${row.time}" }
        timeButton.setOnClickListener {
            val at = runCatching { LocalTime.parse(row.time) }.getOrDefault(LocalTime.parse(LoadTimes.defaultTime(kind)))
            TimePickerDialog(context, { _, h, min -> row.time = LoadTimes.clock(h, min); paintTime() }, at.hour, at.minute, true).show()
        }
        paintTime()
        view.addView(timeButton, wrap())
        view.addView(WrapRow(context, dp(8), dp(0)).apply { days.forEach { addView(it) } })
        view.addView(Button(context).apply {
            text = words.say("heat.spa.removeTime")
            isAllCaps = false
            setOnClickListener {
                rows.remove(row)
                list.removeView(view)
                paintAdd()
            }
        }, wrap())
        rows += row
        list.addView(view)
        paintAdd()
    }

    private fun paintAdd() {
        if (::add.isInitialized) add.isEnabled = rows.size < LoadTimes.MAX
    }

    private fun muted(said: String) = TextView(context).apply { text = said; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(6)) }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}
