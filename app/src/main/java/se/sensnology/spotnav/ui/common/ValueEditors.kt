package se.sensnology.spotnav.ui.common

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The few editors every settings value opens, one value per dialog: a number ([editNumber]), one of a
 * few ([chooseOne]), several ([chooseMany]) and on/off ([editOnOff]). Each hands its value to a save
 * callback that does the value's own write and answers `done(null)` when it took, or `done(message)`
 * to keep the dialog open with that message under the value.
 */

/** What a number field may hold: the range (never clamped) and how many decimals it keeps. */
internal data class NumberSpec(val min: Double, val max: Double, val decimals: Int) {
    /** What was typed, judged: a comma or a dot is the decimal mark, and the value is rounded to [decimals]. */
    fun check(text: String): NumberCheck {
        val number = text.trim().replace(',', '.').toDoubleOrNull()
        if (number == null || !number.isFinite()) return NumberCheck.NotANumber
        val scale = 10.0.pow(decimals)
        val rounded = (number * scale).roundToLong() / scale
        return if (rounded < min || rounded > max) NumberCheck.OutOfRange else NumberCheck.Valid(rounded)
    }

    /** A value as the field shows it: its own decimals, a dot as the mark (what [check] reads back). */
    fun fieldText(value: Double): String =
        if (decimals == 0) String.format(Locale.ROOT, "%d", value.roundToLong())
        else String.format(Locale.ROOT, "%.${decimals}f", value)
}

internal sealed interface NumberCheck {
    data class Valid(val value: Double) : NumberCheck
    data object NotANumber : NumberCheck
    data object OutOfRange : NumberCheck
}

/**
 * A number: [spec]'s range, its [unit] beside the field, and [rangeMessage] when it is outside. With
 * [noneLabel] a box above the field chooses "none" (the value is then `null`; [current] `null` starts
 * there).
 */
internal fun ViewScope.editNumber(
    title: String,
    spec: NumberSpec,
    unit: String,
    current: Double?,
    rangeMessage: String,
    help: String? = null,
    noneLabel: String? = null,
    save: (Double?, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    val none = noneLabel?.let { label ->
        CheckBox(context).apply { text = label; textSize = 16f; isChecked = current == null }.also { body.addView(it) }
    }
    val field = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (spec.decimals > 0) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        current?.let { setText(spec.fieldText(it)) }
        setSelectAllOnFocus(true)
    }
    body.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(field, weight())
        if (unit.isNotEmpty()) addView(TextView(context).apply { text = unit; textSize = 15f; setTextColor(muted); setPadding(dp(8), 0, 0, 0) })
    })
    none?.let { box ->
        field.isEnabled = !box.isChecked
        box.setOnCheckedChangeListener { _, checked -> field.isEnabled = !checked }
    }
    help?.let { body.addView(muted(it, top = 4)) }
    val error = errorLine().also { body.addView(it) }
    openEditor(title, body, error) { done ->
        if (none?.isChecked == true) {
            save(null, done)
            return@openEditor
        }
        when (val check = spec.check(field.text.toString())) {
            is NumberCheck.Valid -> save(check.value, done)
            NumberCheck.NotANumber -> done(t(R.string.paired_error_number))
            NumberCheck.OutOfRange -> done(rangeMessage)
        }
    }
}

/**
 * One of [options], [selected] marked, each with its help line under it when [helps] gives one. Choose
 * saves the choice (nothing is written when it is the one already chosen).
 */
internal fun ViewScope.chooseOne(
    title: String,
    options: List<String>,
    selected: Int,
    helps: List<String?>? = null,
    intro: String? = null,
    save: (Int, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    intro?.let { body.addView(muted(it, bottom = 6)) }
    val group = RadioGroup(context).apply { orientation = LinearLayout.VERTICAL }
    val radios = options.mapIndexed { index, option ->
        RadioButton(context).apply {
            id = View.generateViewId(); text = option; textSize = 16f; isChecked = index == selected
        }.also { radio ->
            group.addView(radio)
            helps?.getOrNull(index)?.let { help -> group.addView(muted(help, bottom = 4).apply { setPadding(dp(32), 0, 0, dp(4)) }) }
        }
    }
    body.addView(group)
    val error = errorLine().also { body.addView(it) }
    openEditor(title, body, error, positive = t(R.string.identify_choose)) { done ->
        val chosen = radios.indexOfFirst { it.isChecked }
        if (chosen < 0 || chosen == selected) done(null) else save(chosen, done)
    }
}

/** On or off, as a choice of the two (the words of an on/off row). */
internal fun ViewScope.editOnOff(title: String, on: Boolean, help: String? = null, save: (Boolean, (String?) -> Unit) -> Unit) =
    chooseOne(title, listOf(t(R.string.site_on), t(R.string.site_off)), if (on) 0 else 1, intro = help) { index, done ->
        save(index == 0, done)
    }

/** Several of [options], each ticked as [checked] says; [atLeastOne] refuses none with that message. */
internal fun ViewScope.chooseMany(
    title: String,
    options: List<String>,
    checked: List<Boolean>,
    intro: String? = null,
    atLeastOne: String? = null,
    save: (List<Boolean>, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    intro?.let { body.addView(muted(it, bottom = 6)) }
    val boxes = options.mapIndexed { index, option ->
        CheckBox(context).apply { text = option; textSize = 16f; isChecked = checked.getOrElse(index) { false } }
            .also { body.addView(it) }
    }
    val error = errorLine().also { body.addView(it) }
    openEditor(title, body, error) { done ->
        val ticks = boxes.map { it.isChecked }
        when {
            atLeastOne != null && ticks.none { it } -> done(atLeastOne)
            ticks == checked -> done(null)
            else -> save(ticks, done)
        }
    }
}

/**
 * The one dialog shell: [body], Cancel, and Save (or [positive]) that stays open while the write is
 * on its way and closes when it took; a message keeps it open with the message shown.
 */
internal fun ViewScope.openEditor(
    title: String,
    body: View,
    error: TextView,
    positive: String = t(R.string.paired_save),
    onSave: (done: (String?) -> Unit) -> Unit
) {
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton(t(android.R.string.cancel), null)
        .setPositiveButton(positive, null)
        .create()
    dialog.show()
    val save: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    save.setOnClickListener {
        save.isEnabled = false
        error.visibility = View.GONE
        onSave { message ->
            if (isDestroyed) return@onSave
            if (message == null) {
                dialog.dismiss()
            } else {
                save.isEnabled = true
                error.text = message
                error.visibility = View.VISIBLE
            }
        }
    }
}

private fun ViewScope.editorBody() = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(22), dp(8), dp(22), 0)
}

private fun ViewScope.errorLine() = TextView(context).apply {
    textSize = 13f; setTextColor(ERROR_RED); setPadding(0, dp(4), 0, 0); visibility = View.GONE
}

private fun ViewScope.muted(text: String, top: Int = 0, bottom: Int = 0) = TextView(context).apply {
    this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, dp(top), 0, dp(bottom))
}

private const val ERROR_RED = 0xFFD65C5C.toInt()
