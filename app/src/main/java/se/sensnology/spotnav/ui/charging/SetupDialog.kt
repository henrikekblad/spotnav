package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.dashboard.SetupRow
import se.sensnology.spotnav.ui.common.ViewScope

/**
 * The "Get started" rows left to set up, as the card's attention dialog lists them: what is missing, a button to the place
 * where it is done, and, where the row may be left, "Not needed".
 */
internal class SetupDialog(scope: ViewScope) : ViewScope(scope) {
    private var dialog: AlertDialog? = null

    /** The row's sentence and its button's words, or `null` for a row this app has no words for. */
    private fun words(id: String, forecastChoosable: Boolean): Pair<Int, Int>? = when (id) {
        "vehicle" -> R.string.setup_vehicle_text to R.string.setup_vehicle_action
        "car_level" -> R.string.setup_car_level_text to R.string.setup_car_level_open
        "site" -> R.string.setup_site_text to R.string.setup_site_action
        "solar_forecast" -> R.string.setup_solar_forecast_text to
            if (forecastChoosable) R.string.setup_solar_forecast_action else R.string.setup_solar_forecast_add
        "demand_grid_power" -> R.string.setup_demand_grid_power_text to R.string.setup_demand_grid_power_action
        "phase_switching" -> R.string.setup_phase_switching_text to R.string.setup_phase_switching_action
        "phone" -> R.string.setup_phone_text to R.string.setup_phone_action
        else -> null
    }

    fun show(
        rows: List<SetupRow>,
        canDismiss: (SetupRow) -> Boolean,
        forecastChoosable: Boolean,
        onOpen: (SetupRow) -> Unit,
        onDismiss: (SetupRow) -> Unit
    ) {
        dialog?.dismiss()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        for (row in rows) {
            val (text, action) = words(row.id, forecastChoosable) ?: continue
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(8))
                background = GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke(dp(1), muted)
                    cornerRadius = dp(8).toFloat()
                }
            }
            box.addView(TextView(context).apply {
                this.text = t(text)
                textSize = 14f
                setTextColor(dark)
            })
            val answers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fun button(label: Int, run: () -> Unit) = Button(context).apply {
                this.text = t(label)
                isAllCaps = false
                setOnClickListener { dialog?.dismiss(); run() }
            }
            answers.addView(button(action) { onOpen(row) })
            if (canDismiss(row)) answers.addView(button(R.string.setup_dismiss) { onDismiss(row) })
            box.addView(answers)
            list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        dialog = AlertDialog.Builder(context)
            .setTitle(t(R.string.setup_heading))
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }
}
