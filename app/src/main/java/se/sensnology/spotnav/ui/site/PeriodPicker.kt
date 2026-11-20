package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.chooseOne
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The one-line period picker the history tabs of the house and heat pages share: "‹ [label] ›" and Day | Month |
 * Year, as the Home Assistant card has it (`periodPicker`, `frontend/src/house-tabs.ts`). The arrows step one period
 * back or forward, the label opens a date dialog (a day) or a list (a month, a year), and nothing after today is
 * offered. [show] draws a choice; [onChange] hears the next one and the page shows it again.
 *
 * [kinds] are the kinds offered; with one alone its buttons are left out.
 */
@SuppressLint("ViewConstructor") // built in code with the page's words: there is nothing to inflate
internal class PeriodPickerView(
    private val scope: ViewScope,
    private val words: CardWords,
    private val locale: Locale,
    private val kinds: List<HistoryPeriod> = HistoryPeriod.entries,
    private val onChange: (PeriodChoice) -> Unit
) : LinearLayout(scope.context) {
    private val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var segment: LinearLayout? = null
    private var stacked = false
    private var choice = PeriodChoice(kinds.first())
    private var today: LocalDate = LocalDate.now()

    private val back = arrow("‹") { onChange(choice.step(today, -1)) }
    private val forward = arrow("›") { onChange(choice.step(today, 1)) }
    private val label = TextView(context).apply {
        textSize = 16f
        setTextColor(scope.dark)
        typeface = MEDIUM
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minimumHeight = scope.dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { pick() }
    }
    private val kindButtons = kinds.map { kind ->
        kind to TextView(context).apply {
            text = words.say("hist.period.${kind.key}")
            textSize = 14f
            gravity = Gravity.CENTER
            minimumHeight = scope.dp(32)
            setPadding(scope.dp(9), 0, scope.dp(9), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (kind != choice.period) onChange(choice.kind(kind, today)) }
        }
    }

    init {
        orientation = VERTICAL
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(back)
        row.addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(forward)
        if (kinds.size > 1) {
            segment = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(scope.dp(2), scope.dp(2), scope.dp(2), scope.dp(2))
                background = GradientDrawable().apply {
                    setColor(SiteColours.mix(scope.muted, scope.cardBackground, 0.14f))
                    cornerRadius = scope.dp(9).toFloat()
                }
                contentDescription = words.say("hist.period.label")
                kindButtons.forEach { (_, button) -> addView(button) }
            }
            place()
        }
    }

    /** The segment beside the date, or on a row of its own under it. */
    private fun place() {
        val box = segment ?: return
        (box.parent as? ViewGroup)?.removeView(box)
        if (stacked) {
            addView(box, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = scope.dp(2) })
        } else {
            row.addView(box, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = scope.dp(6) })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val box = segment
        if (box != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            box.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val arrows = 2 * scope.dp(44)
            val want = PeriodPickerLayout.stacked(MeasureSpec.getSize(widthMeasureSpec), arrows, box.measuredWidth + scope.dp(6), scope.dp(PeriodPickerLayout.MIN_LABEL_DP))
            if (want != stacked) {
                stacked = want
                post { place(); requestLayout() }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Draw [next], with [today] the site's local date; [through] ("17:00") is told after today's label where the
     * row has room for it ("I dag · till 17:00"), never cut.
     */
    fun show(next: PeriodChoice, today: LocalDate, through: String? = null) {
        choice = next
        this.today = today
        val period = next.period.key
        val name = Periods.label(words, locale, next, today)
        val whole = if (through != null && next.period == HistoryPeriod.DAY && next.date == null) {
            words.say("house.picker.through", "label" to name, "time" to through)
        } else name
        label.text = name
        label.contentDescription = "${words.say("hist.pick.$period")}: $whole"
        if (whole != name) {
            // Shown only once laid out and found to fit.
            label.post { if (label.paint.measureText(whole) <= label.width - label.paddingLeft - label.paddingRight) label.text = whole }
        }
        step(back, words.say("hist.previous.$period"), next.canStepBack(today))
        step(forward, words.say("hist.next.$period"), next.canStepForward(today))
        for ((kind, button) in kindButtons) {
            val pressed = kind == next.period
            button.isSelected = pressed
            button.setTextColor(if (pressed) scope.dark else scope.muted)
            button.typeface = if (pressed) MEDIUM else Typeface.DEFAULT
            button.background = if (!pressed) null else GradientDrawable().apply {
                setColor(scope.cardBackground)
                cornerRadius = scope.dp(7).toFloat()
            }
        }
    }

    private fun step(arrow: TextView, description: String, allowed: Boolean) {
        arrow.contentDescription = description
        arrow.isEnabled = allowed
        arrow.alpha = if (allowed) 1f else 0.3f
    }

    private fun arrow(glyph: String, go: () -> Unit) = TextView(context).apply {
        text = glyph
        textSize = 26f
        setTextColor(scope.accent)
        gravity = Gravity.CENTER
        minimumWidth = scope.dp(44)
        minimumHeight = scope.dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { go() }
    }

    /** Choose the period by hand: a day in a date dialog, a month or a year in a list; nothing after today. */
    private fun pick() {
        val shown = choice.shown(today)
        val earliest = Periods.earliest(choice.period, today)
        if (choice.period == HistoryPeriod.DAY) {
            // The dialog's limits are instants of this phone's own zone, as its calendar is drawn in it.
            val zone = ZoneId.systemDefault()
            DatePickerDialog(context, { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day)
                if (picked in earliest..today) onChange(choice.picked(picked, today))
            }, shown.year, shown.monthValue - 1, shown.dayOfMonth).apply {
                datePicker.minDate = earliest.atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = today.atStartOfDay(zone).toInstant().toEpochMilli()
            }.show()
            return
        }
        val options = if (choice.period == HistoryPeriod.MONTH) Periods.months(today, shown) else Periods.years(today, shown)
        scope.chooseOne(
            words.say("hist.pick.${choice.period.key}"),
            options.map { Periods.optionLabel(locale, choice.period, it) },
            options.indexOf(shown)
        ) { index, done ->
            done(null)
            onChange(choice.picked(options[index], today))
        }
    }

    private companion object {

        private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

/** A details dialog at the screen's full width (less the dialog's own margins) rather than the default dialog width. */
internal fun android.app.AlertDialog.useFullWidth() {
    window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** The picker's layout decision, apart from the views. */
internal object PeriodPickerLayout {
    /** The least width (dp) the date label is given beside the segment. */
    const val MIN_LABEL_DP = 110

    /** Whether the segment goes on a row of its own: the row's [available] width less the arrows and the segment leaves the label under [minLabel]. */
    fun stacked(available: Int, arrows: Int, segment: Int, minLabel: Int): Boolean = available - arrows - segment < minLabel
}
