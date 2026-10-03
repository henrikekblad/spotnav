package se.sensnology.spotnav.ui.charging

import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.slider
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.widget.WidgetSettings

/** The plan's kWh driver control. */
internal class EnergyController(scope: ViewScope) : ViewScope(scope) {
    /** The energy control: */
    fun add(parent: LinearLayout, settings: WidgetSettings): EnergyControls {
        val energyValue = valueLabel()
        val energy = slider(
            t(R.string.charging), 0, VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS,
            VehicleEnergy.energyProgressNearest(settings.chargingKwh), energyValue, parent
        ) { progress ->
            energyValue.text = kwhText(VehicleEnergy.energyKwhOfProgress(progress))
        }
        parent.addView(bandLabel())
        return EnergyControls(
            energy = energy,
            refreshValueLabel = {
                energyValue.text = kwhText(VehicleEnergy.energyKwhOfProgress(energy.progress))
            },
            valueLabel = energyValue
        )
    }
}

/** What [EnergyController.add] hands back: the plan's kWh driver control. */
internal class EnergyControls(
    val energy: SeekBar,
    val refreshValueLabel: () -> Unit,
    /** The label the slider writes its own step into (see showExactValues). */
    val valueLabel: TextView
)
