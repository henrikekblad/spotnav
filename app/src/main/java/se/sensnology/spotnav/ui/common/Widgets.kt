package se.sensnology.spotnav.ui.common

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chargers.ChargerProfile

/** The small view factories every screen shares: a label, a checkbox, a number field. */

internal fun ViewScope.label(value: String) = TextView(context).apply { text = value; textSize = 14f; setTextColor(muted); setPadding(0, dp(16), 0, dp(4)) }
internal fun ViewScope.checkbox(value: String, checked: Boolean) = CheckBox(context).apply { text = value; isChecked = checked; textSize = 16f }
internal fun ViewScope.numberField(value: Double, hintText: String) = EditText(context).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; hint = hintText; setText(if (value == 0.0) "" else value.toString()) }
internal fun ViewScope.number(field: EditText) = field.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
internal fun ViewScope.weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

/**
 * A value as a **control** draws it: large, bold and accent-coloured -- a slider's own readout,
 * which is the number its thumb is at, and therefore editable by definition.
 */
internal fun ViewScope.valueLabel() = TextView(context).apply { textSize = 16f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD }

internal fun ViewScope.resultValue() = TextView(context).apply {
    textSize = 15f; setTextColor(dark); gravity = Gravity.END; typeface = Typeface.DEFAULT_BOLD
}

/** One readout row, added to [parent] and handed back so a card can hide it. */
internal fun ViewScope.resultRow(parent: LinearLayout, name: String, value: TextView): View {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0)
        addView(TextView(context).apply { text = name; textSize = 15f; setTextColor(muted) }, weight())
        addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    parent.addView(row)
    return row
}

/** Calls [action] whenever [view] is laid out at a new size. */
internal fun ViewScope.onLaidOut(view: View, action: () -> Unit) {
    view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) action()
    }
}

/**
 * A charger's name, by the same rule every other list of chargers uses: the user's own name for it,
 * else what the charger calls itself, else the generic "charger". Never the webhook, the URL or the
 * remote id.
 */
internal fun ViewScope.chargerName(profile: ChargerProfile) =
    profile.label(t(R.string.charger_generic_name))
