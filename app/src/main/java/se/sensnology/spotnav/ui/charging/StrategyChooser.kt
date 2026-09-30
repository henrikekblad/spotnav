package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ui.common.ViewScope

/** The strategy chooser: the model's own rows, rendered once, in one dialog. */
internal fun ViewScope.showStrategyChooser(
    ui: ChargingStrategyUi,
    onChoose: (ChargingStrategy) -> Unit,
    onClosed: () -> Unit
): AlertDialog {
    val adapter = object : BaseAdapter() {
        override fun getCount() = ui.choices.size
        override fun getItem(position: Int) = ui.choices[position]
        override fun getItemId(position: Int) = position.toLong()

        // A row that cannot be chosen is a statement, and a list that reported "all items enabled"
        // while refusing them would read to a screen reader as a list of choices.
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = ui.choices[position].selectable

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val choice = ui.choices[position]
            val enabled = choice.selectable
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val title = TextView(context).apply {
                text = strategyTitleText(choice.strategy)
                textSize = 16f
                setTextColor(if (enabled) dark else muted)
            }
            val second = TextView(context).apply {
                text = strategyChoiceNote(choice)
                textSize = 13f
                setTextColor(muted)
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            // The two labels are not separately reachable, and a disabled row has to *say* what it
            // is waiting for rather than only look grey -- so the row carries one description of
            // exactly what it shows.
            title.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            second.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.contentDescription = listOf(title.text, second.text)
                .filter { it.isNotBlank() }
                .joinToString(". ")
            row.addView(title)
            row.addView(second)
            return row
        }
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(t(R.string.strategy_chooser_title))
        .setAdapter(adapter) { _, position -> onChoose(ui.choices[position].strategy) }
        .create()
    // Closing clears this screen's own chooser state and returns focus to the row that opened it:
    dialog.setOnDismissListener { onClosed() }
    dialog.show()
    return dialog
}

/** The one title the strategy row and the chooser both read. */
internal fun ViewScope.strategyTitleText(strategy: ChargingStrategy) = when (strategy) {
    ChargingStrategy.CHEAPEST -> t(R.string.strategy_title_cheapest)
    ChargingStrategy.SOLAR -> t(R.string.strategy_title_solar)
    ChargingStrategy.HYBRID -> t(R.string.strategy_title_hybrid)
}

/**
 * A chooser row's second line: what a row that cannot be chosen is waiting for, or what a row that
 * can be chosen would do.
 */
internal fun ViewScope.strategyChoiceNote(choice: StrategyChoice) = when {
    choice.gap != null -> strategyGapText(choice.gap)
    choice.chosen -> t(R.string.strategy_choice_active)
    else -> t(R.string.strategy_choice_automatic)
}

/** The capability a strategy that is not implemented yet is missing, in a person's words. */
internal fun ViewScope.strategyGapText(gap: StrategyGap) = when (gap) {
    StrategyGap.MEASURED_SOLAR_PRODUCTION -> t(R.string.strategy_gap_solar_measurement)
    StrategyGap.SOLAR_MEASUREMENT_AND_CONTROL -> t(R.string.strategy_gap_solar_control)
}
