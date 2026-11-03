package se.sensnology.spotnav.ui.settings

import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine
import se.sensnology.spotnav.ui.common.openEditor
import java.time.Month
import java.time.format.TextStyle

/** The window's fields (the card's `windowFields`), ready to place in a form, and how to read what they hold. */
internal class HighLoadWindowFields(val nodes: List<View>, val read: () -> HighLoadWindow)

/**
 * The days, the first and last month, and from and to hour (an end before the start runs over midnight), showing
 * [shown].
 */
internal fun ViewScope.highLoadWindowFields(words: CardWords, shown: HighLoadWindow): HighLoadWindowFields {
    val locale = AppLanguageSettings.locale(activity)
    fun label(text: String) = TextView(context).apply {
        this.text = text; textSize = 14f; setTextColor(dark); setPadding(0, dp(10), 0, dp(2))
    }
    fun spinner(items: List<String>, selected: Int) = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(0, items.lastIndex))
    }
    fun column(title: String, control: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title))
        addView(control)
    }
    fun pair(left: View, right: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    val days = spinner(HighLoadWindow.DAYS.map { words.say(GridTariffText.DAY_KEYS.getValue(it)) }, HighLoadWindow.DAYS.indexOf(shown.days))
    val months = (1..12).map { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { c -> c.titlecase(locale) } }
    val fromMonth = spinner(months, shown.fromMonth - 1)
    val toMonth = spinner(months, shown.toMonth - 1)
    val fromHour = spinner((0..23).map { "%02d:00".format(it) }, shown.fromHour)
    val toHour = spinner((1..24).map { "%02d:00".format(it) }, shown.toHour - 1)
    return HighLoadWindowFields(
        listOf(
            column(words.say("gridFee.window.days"), days),
            pair(column(words.say("gridFee.window.fromMonth"), fromMonth), column(words.say("gridFee.window.toMonth"), toMonth)),
            pair(column(words.say("gridFee.window.from"), fromHour), column(words.say("gridFee.window.to"), toHour))
        )
    ) {
        HighLoadWindow(
            HighLoadWindow.DAYS[days.selectedItemPosition],
            fromMonth.selectedItemPosition + 1,
            toMonth.selectedItemPosition + 1,
            fromHour.selectedItemPosition,
            toHour.selectedItemPosition + 1
        )
    }
}

/**
 * The high-load hours (the card's `windowEditor`). With none stored ([current] `null`) it is prefilled with a common
 * window and says it is a suggestion; nothing is written until Save, and Save of the stored hours unchanged writes
 * nothing. [intro] and [positive] are the turn-on flow's: why the hours come first, and "Nästa".
 */
internal fun ViewScope.editHighLoadWindow(
    words: CardWords,
    current: HighLoadWindow?,
    intro: String? = null,
    positive: String? = null,
    save: (HighLoadWindow, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    fun help(text: String) = body.addView(TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(6))
    })
    intro?.let(::help)
    help(words.say("gridFee.window.help"))
    if (current == null) help(words.say("gridFee.window.suggestion"))
    val fields = highLoadWindowFields(words, current ?: HighLoadWindow.SUGGESTED)
    fields.nodes.forEach(body::addView)
    val error = errorLine().also { body.addView(it) }
    val save: (done: (String?) -> Unit) -> Unit = { done ->
        val chosen = fields.read()
        when {
            chosen.fromHour == chosen.toHour -> done(words.say("gridFee.window.sameHours"))
            chosen == current -> done(null)
            else -> save(chosen, done)
        }
    }
    if (positive == null) openEditor(words.say("gridFee.window"), body, error, onSave = save)
    else openEditor(words.say("gridFee.window"), body, error, positive = positive, onSave = save)
}
