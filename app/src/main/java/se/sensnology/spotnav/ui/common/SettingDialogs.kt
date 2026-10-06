package se.sensnology.spotnav.ui.common

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R

/**
 * The dialogs a settings value row opens for a choice: one of a few ([chooseOne]), or several
 * ([chooseMany]). A row with a number or a form keeps its own dialog.
 */

/**
 * One of [options], [selected] marked. Without [helps] a tap is the choice; with them each option has
 * its own line of what it does, and Choose confirms.
 */
internal fun ViewScope.chooseOne(
    title: String,
    options: List<String>,
    selected: Int,
    helps: List<String?>? = null,
    intro: String? = null,
    onChosen: (Int) -> Unit
) {
    if (helps == null && intro == null) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                if (which != selected) onChosen(which)
            }
            .setNegativeButton(t(android.R.string.cancel), null)
            .show()
        return
    }
    val body = dialogBody()
    intro?.let { body.addView(mutedLine(it, bottom = 6)) }
    val group = RadioGroup(context).apply { orientation = LinearLayout.VERTICAL }
    val radios = options.mapIndexed { index, option ->
        RadioButton(context).apply {
            id = View.generateViewId(); text = option; textSize = 16f; isChecked = index == selected
        }.also { radio ->
            group.addView(radio)
            helps?.getOrNull(index)?.let { help -> group.addView(mutedLine(help, bottom = 4).apply { setPadding(dp(32), 0, 0, dp(4)) }) }
        }
    }
    body.addView(group)
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton(t(android.R.string.cancel), null)
        .setPositiveButton(t(R.string.identify_choose)) { _, _ ->
            val chosen = radios.indexOfFirst { it.isChecked }
            if (chosen >= 0 && chosen != selected) onChosen(chosen)
        }
        .show()
}

/**
 * Several of [options], each ticked as [checked] says. Save hands the ticks to [onSave] with the
 * dialog and a way to say what is wrong; the dialog stays open until [onSave] closes it.
 */
internal fun ViewScope.chooseMany(
    title: String,
    options: List<String>,
    checked: List<Boolean>,
    intro: String? = null,
    onSave: (ticks: List<Boolean>, dialog: AlertDialog, say: (String?) -> Unit) -> Unit
) {
    val body = dialogBody()
    intro?.let { body.addView(mutedLine(it, bottom = 6)) }
    val boxes = options.mapIndexed { index, option ->
        CheckBox(context).apply { text = option; textSize = 16f; isChecked = checked.getOrElse(index) { false } }
            .also { body.addView(it) }
    }
    val error = TextView(context).apply { textSize = 13f; setTextColor(0xFFD65C5C.toInt()); visibility = View.GONE }
    body.addView(error)
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton(t(android.R.string.cancel), null)
        .setPositiveButton(t(R.string.paired_save), null)
        .create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        onSave(boxes.map { it.isChecked }, dialog) { message ->
            error.text = message.orEmpty()
            error.visibility = if (message == null) View.GONE else View.VISIBLE
        }
    }
}

private fun ViewScope.dialogBody() = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(22), dp(8), dp(22), 0)
}

private fun ViewScope.mutedLine(text: String, bottom: Int = 0) = TextView(context).apply {
    this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(bottom))
}
