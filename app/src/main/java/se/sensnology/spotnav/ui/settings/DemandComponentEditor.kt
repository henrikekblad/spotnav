package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.dashboard.DemandComponent
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine
import java.time.Month
import java.time.format.TextStyle

/**
 * One part of the demand charge, the card's `componentEditor`: which hours count (all, or a window of days, months and
 * hours), how many peaks a month on different days, and the price per kW in winter and summer (either may stay empty).
 * Save hands [save] the part; an existing one ([current]) also has Remove, which hands it `null`. [save] answers
 * `null` when it took (the dialog closes) or the message to show.
 */
internal fun ViewScope.editDemandComponent(
    words: CardWords,
    current: DemandComponent?,
    unit: String?,
    save: (DemandComponent?, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    val locale = AppLanguageSettings.locale(activity)
    fun label(text: String) = TextView(context).apply {
        this.text = text; textSize = 14f; setTextColor(dark); setPadding(0, dp(10), 0, dp(2))
    }
    fun spinner(items: List<String>, selected: Int) = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(selected.coerceIn(0, items.lastIndex))
    }
    fun pair(left: View, right: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    fun column(title: String, control: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title))
        addView(control)
    }
    body.addView(TextView(context).apply {
        text = words.say("demand.help"); textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(6))
    })
    val counted = spinner(listOf(words.say("demand.counted.all"), words.say("demand.counted.window")), if (current?.window != null) 1 else 0)
    body.addView(label(words.say("demand.counted")))
    body.addView(counted)
    val shown = current?.window ?: HighLoadWindow.SUGGESTED
    val days = spinner(HighLoadWindow.DAYS.map { words.say(DemandText.DAY_KEYS.getValue(it)) }, HighLoadWindow.DAYS.indexOf(shown.days))
    val months = (1..12).map { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { c -> c.titlecase(locale) } }
    val fromMonth = spinner(months, shown.fromMonth - 1)
    val toMonth = spinner(months, shown.toMonth - 1)
    val fromHour = spinner((0..23).map { "%02d:00".format(it) }, shown.fromHour)
    val toHour = spinner((1..24).map { "%02d:00".format(it) }, shown.toHour - 1)
    val windowBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(words.say("gridFee.window.days")))
        addView(days)
        addView(pair(column(words.say("gridFee.window.fromMonth"), fromMonth), column(words.say("gridFee.window.toMonth"), toMonth)))
        addView(pair(column(words.say("gridFee.window.from"), fromHour), column(words.say("gridFee.window.to"), toHour)))
        visibility = if (counted.selectedItemPosition == 1) View.VISIBLE else View.GONE
    }
    counted.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            windowBox.visibility = if (position == 1) View.VISIBLE else View.GONE
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    body.addView(windowBox)
    val topN = spinner(DemandComponent.TOP_N.map { words.say("demand.top.$it") }, DemandComponent.TOP_N.indexOf(current?.topN ?: 1))
    body.addView(label(words.say("demand.topN")))
    body.addView(topN)
    val unitText = unit?.let { " ($it)" }.orEmpty()
    val figures = AppLanguageSettings.numberLocale(activity)
    fun priceField(value: Double?) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(value?.let { java.text.NumberFormat.getNumberInstance(figures).apply { isGroupingUsed = false; maximumFractionDigits = 4 }.format(it) }.orEmpty())
        gravity = Gravity.START
    }
    val winter = priceField(current?.winter)
    val summer = priceField(current?.summer)
    body.addView(label(words.say("demand.priceWinter") + unitText))
    body.addView(winter)
    body.addView(label(words.say("demand.priceSummer") + unitText))
    body.addView(summer)
    val error = errorLine().also { body.addView(it) }

    val builder = AlertDialog.Builder(context)
        .setTitle(words.say("demand.title"))
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton(t(android.R.string.cancel), null)
        .setPositiveButton(t(R.string.paired_save), null)
    if (current != null) builder.setNeutralButton(words.say("demand.remove"), null)
    val dialog = builder.create()
    dialog.show()
    val buttons = listOfNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE), dialog.getButton(AlertDialog.BUTTON_NEUTRAL))
    fun send(part: DemandComponent?) {
        buttons.forEach { it.isEnabled = false }
        error.visibility = View.GONE
        save(part) { message ->
            if (isDestroyed) return@save
            if (message == null) dialog.dismiss() else {
                buttons.forEach { it.isEnabled = true }
                error.text = message
                error.visibility = View.VISIBLE
            }
        }
    }
    fun refuse(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { send(null) }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val window = if (counted.selectedItemPosition == 1) HighLoadWindow(
            HighLoadWindow.DAYS[days.selectedItemPosition],
            fromMonth.selectedItemPosition + 1,
            toMonth.selectedItemPosition + 1,
            fromHour.selectedItemPosition,
            toHour.selectedItemPosition + 1
        ) else null
        if (window != null && window.fromHour == window.toHour) return@setOnClickListener refuse(words.say("gridFee.window.sameHours"))
        val winterPrice = DemandText.parsePrice(winter.text.toString())
        val summerPrice = DemandText.parsePrice(summer.text.toString())
        if (winterPrice?.isNaN() == true || summerPrice?.isNaN() == true) return@setOnClickListener refuse(words.say("demand.error.price"))
        val chosen = DemandComponent(window, DemandComponent.TOP_N[topN.selectedItemPosition], winterPrice, summerPrice)
        // The part as stored, unchanged: nothing to write.
        if (chosen == current) dialog.dismiss() else send(chosen)
    }
}

/** A row of the demand charge's lines: muted, under its row. */
internal fun ViewScope.demandLine(text: String) = TextView(context).apply {
    this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(6))
}

