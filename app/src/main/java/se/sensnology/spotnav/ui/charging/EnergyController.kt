package se.sensnology.spotnav.ui.charging

import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.slider
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.vehicles.BatteryRoom
import se.sensnology.spotnav.vehicles.TargetNeed
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.widget.WidgetSettings

/** The plan's kWh driver control. */
internal class EnergyController(scope: ViewScope) : ViewScope(scope) {
    /** The energy control: */
    fun add(parent: LinearLayout, settings: WidgetSettings): EnergyControls {
        val energyValue = valueLabel()
        // A paired charger's battery room (`soc.room_kwh`) and the car's own limit, when Home Assistant
        // states them: the slider's top, and the note that the car ends the charge there.
        var roomKwh: Double? = null
        var limitPercent: Double? = null
        val carEndsLine = pairedLine()
        val paintCarEnds = { kwh: Double ->
            val shown = BatteryRoom.energyAtRoom(kwh, roomKwh)
            carEndsLine.text = if (shown) carEndsChargeText(limitPercent) else ""
            carEndsLine.visibility = if (shown) View.VISIBLE else View.GONE
        }
        val energy = slider(
            t(R.string.charging), 0, VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS,
            VehicleEnergy.energyProgressNearest(settings.chargingKwh), energyValue, parent
        ) { progress ->
            energyValue.text = kwhText(VehicleEnergy.energyKwhOfProgress(progress))
            paintCarEnds(VehicleEnergy.energyKwhOfProgress(progress))
        }
        parent.addView(carEndsLine)
        parent.addView(bandLabel())
        val applyRoom = {
            energy.max = BatteryRoom.energySliderMaxProgress(roomKwh, energy.progress)
            paintCarEnds(VehicleEnergy.energyKwhOfProgress(energy.progress))
        }
        return EnergyControls(
            energy = energy,
            refreshValueLabel = {
                energyValue.text = kwhText(VehicleEnergy.energyKwhOfProgress(energy.progress))
                paintCarEnds(VehicleEnergy.energyKwhOfProgress(energy.progress))
            },
            valueLabel = energyValue,
            setKwh = { kwh ->
                // The whole scale first, so a stored amount above the room is never cut to it.
                energy.max = VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS
                energy.progress = VehicleEnergy.energyProgressNearest(kwh)
                applyRoom()
                paintCarEnds(kwh)
            },
            applyRoom = { room, limit ->
                roomKwh = room
                limitPercent = limit
                applyRoom()
            }
        )
    }
}

/** The note under a slider at its top: the car ends the charge itself, at its own limit (100 % without one). */
internal fun ViewScope.carEndsChargeText(limitPercent: Double?): String =
    t(
        R.string.car_ends_charge,
        percentText(TargetNeed.chargeCeiling(limitPercent).toDouble(), AppLanguageSettings.numberLocale(context))
    )

/** What [EnergyController.add] hands back: the plan's kWh driver control. */
internal class EnergyControls(
    val energy: SeekBar,
    val refreshValueLabel: () -> Unit,
    /** The label the slider writes its own step into (see showExactValues). */
    val valueLabel: TextView,
    /** Show a stored amount, where it is even above the battery's room. */
    val setKwh: (Double) -> Unit,
    /**
     * A paired charger's battery room in kWh and the car's own limit (`null` while Home Assistant
     * states none, which keeps the ordinary 1-100 kWh slider and no note).
     */
    val applyRoom: (Double?, Double?) -> Unit
)
