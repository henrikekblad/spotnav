package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.dashboard.GridTariffFee
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine
import java.text.NumberFormat
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

/** The highest figure the high-load fee takes (the area's minor unit per kWh). */
internal const val HIGH_LOAD_MAX = 100000.0

/**
 * The grid fee in high-load hours, in one dialog (the card's `highLoadEditor`): the fee's figure and its hours, one Save
 * that hands both on, and, while the fee is on or hours are stored, "Ta bort höglastavgift", which hands on `null`. With
 * no hours stored they are prefilled with a common window and said to be a suggestion; nothing is written until Save,
 * and Save of what is stored unchanged writes nothing. [save] answers `null` when it took, or what to say.
 */
internal fun ViewScope.editHighLoad(
    words: CardWords,
    fee: GridTariffFee,
    window: HighLoadWindow?,
    save: (Pair<Double, HighLoadWindow>?, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    val numbers = AppLanguageSettings.numberLocale(activity)
    fun help(text: String) = body.addView(TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(6))
    })
    fun figure(value: Double, grouped: Boolean = true) = NumberFormat.getNumberInstance(numbers).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
        isGroupingUsed = grouped
    }.format(value)
    help(words.say("gridFee.high.help"))
    body.addView(TextView(context).apply {
        text = words.say("gridFee.high.fee") + (fee.unit?.let { " ($it)" } ?: "")
        textSize = 14f; setTextColor(dark); setPadding(0, dp(4), 0, dp(2))
    })
    val field = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        (fee.effectiveValue ?: fee.explicitValue)?.let { setText(figure(it, grouped = false)) }
        setSelectAllOnFocus(true)
    }
    body.addView(field)
    body.addView(View(context), LinearLayout.LayoutParams(1, dp(8)))
    help(words.say("gridFee.window.help"))
    if (window == null) help(words.say("gridFee.window.suggestion"))
    val fields = highLoadWindowFields(words, window ?: HighLoadWindow.SUGGESTED)
    fields.nodes.forEach(body::addView)
    val error = errorLine().also { body.addView(it) }
    val on = fee.policy == "manual"
    val builder = AlertDialog.Builder(context)
        .setTitle(words.say("gridFee.high"))
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton(t(android.R.string.cancel), null)
        .setPositiveButton(t(R.string.paired_save), null)
    if (on || window != null) builder.setNeutralButton(words.say("gridFee.high.remove"), null)
    val dialog = builder.create()
    dialog.show()
    val buttons = listOfNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE), dialog.getButton(AlertDialog.BUTTON_NEUTRAL))
    fun say(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }
    fun send(choice: Pair<Double, HighLoadWindow>?) {
        buttons.forEach { it.isEnabled = false }
        error.visibility = View.GONE
        save(choice) { message ->
            if (isDestroyed) return@save
            if (message == null) dialog.dismiss() else {
                buttons.forEach { it.isEnabled = true }
                say(message)
            }
        }
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { send(null) }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val text = field.text.toString().trim().replace(Regex("[\\s\u00A0\u202F]"), "").replace(',', '.')
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@setOnClickListener say(words.say("gridFee.high.needsFee"))
        if (value < 0 || value > HIGH_LOAD_MAX) {
            return@setOnClickListener say(words.say("settings.error.range", "min" to figure(0.0), "max" to figure(HIGH_LOAD_MAX)))
        }
        val rounded = Math.round(value * 100) / 100.0
        val chosen = fields.read()
        if (chosen.fromHour == chosen.toHour) return@setOnClickListener say(words.say("gridFee.window.sameHours"))
        // What is stored, unchanged: nothing to write.
        if (on && fee.explicitValue == rounded && window == chosen) return@setOnClickListener dialog.dismiss()
        send(rounded to chosen)
    }
}
