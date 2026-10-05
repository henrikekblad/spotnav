package se.sensnology.spotnav.ui.charging

import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.dashboard.DashboardSoc
import se.sensnology.spotnav.ui.common.SLIDER_TRACK_DP
import se.sensnology.spotnav.ui.common.SliderTicks
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.onLaidOut
import se.sensnology.spotnav.ui.common.slider
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.vehicles.BatteryRoom
import se.sensnology.spotnav.vehicles.TargetNeed
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The plan's kWh driver control. With a paired charger's battery room known (`soc.room_kwh`) it is the
 * card's slider past full: a top past the room ([BatteryRoom.fillTopKwh]), a "full" mark on the track
 * at the room, a help line under it, and a last step that is "Fill" (`fill_to_limit`) on a Home
 * Assistant whose record has it. Without a room it is the ordinary 1-100 kWh slider.
 */
internal class EnergyController(scope: ViewScope) : ViewScope(scope) {
    /** The energy control: */
    fun add(parent: LinearLayout, settings: WidgetSettings): EnergyControls {
        val energyValue = valueLabel()
        var facts: BatteryRoom.FillFacts? = null
        var top: Double? = null
        // Whether the record has `fill_to_limit`, and whether the slider stands at "Fill" now.
        var supported = false
        var filling = false
        // The amount the record states, kept for a stored Fill whose room goes unknown.
        var amountKwh = settings.chargingKwh
        val helpLine = pairedLine()
        val markLabel = bandLabel().apply { text = t(R.string.energy_full_mark); visibility = View.INVISIBLE }
        // Set once the slider exists: its own first label is written while it is being made.
        var built: SeekBar? = null
        val paint: () -> Unit = paint@{
            val seek = built ?: return@paint
            energyValue.text = if (filling) t(R.string.energy_fill) else kwhText(VehicleEnergy.energyKwhOfProgress(seek.progress))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                seek.stateDescription = if (filling) t(R.string.energy_fill) else null
            }
            val room = facts?.roomKwh
            if (room == null || top == null) {
                helpLine.text = ""
                helpLine.visibility = View.GONE
                return@paint
            }
            val locale = AppLanguageSettings.numberLocale(context)
            val kwh = BatteryRoom.kwhFigure(room, locale)
            val limit = BatteryRoom.limitNamed(facts?.vehicleMaxPercent)
                ?.let { " " + t(R.string.energy_limit_suffix, percentText(it.toDouble(), locale)) }
                .orEmpty()
            helpLine.text = t(if (filling) R.string.energy_fill_help else R.string.energy_room_help, kwh, limit)
            helpLine.visibility = View.VISIBLE
        }
        val energy = slider(
            t(R.string.charging), 0, VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS,
            VehicleEnergy.energyProgressNearest(settings.chargingKwh), energyValue, parent
        ) { paint() }
        built = energy
        // The slider wrote its first label before `built` was set; write it now (a standalone screen has no
        // later answer that would).
        paint()
        // The "full" mark rides on the framework's own track, as the target slider's shading does. It is added
        // as one more layer of that track, never by wrapping it: the slider finds its fill by the track's own
        // `android.R.id.progress` layer, and a wrapper hides it, so no fill would be drawn.
        val track = energy.progressDrawable
        val trackInsets = Rect()
        track.getPadding(trackInsets)
        val trackHeight = ((track as? LayerDrawable)?.getDrawable(0)?.intrinsicHeight ?: 0)
            .takeIf { it > 0 } ?: dp(SLIDER_TRACK_DP)
        val mark = FullMarkDrawable(dark, dp(2).toFloat(), dp(MARK_REACH_DP).toFloat())
        val layers = track as? LayerDrawable
        if (layers != null) {
            val index = layers.addLayer(mark)
            layers.setLayerInset(index, trackInsets.left, 0, trackInsets.right, 0)
            layers.setLayerHeight(index, trackHeight)
            layers.setLayerGravity(index, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
            energy.progressDrawable = layers
        }
        // Its word under the line, in the row both drivers keep under their slider.
        val markRow = FrameLayout(context)
        markRow.addView(markLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(markRow)
        parent.addView(helpLine)
        val placeMark = {
            val at = BatteryRoom.markFraction(facts?.roomKwh?.takeIf { top != null }, energy.max)
            mark.fraction = at
            val rail = SliderTicks.rail(
                energy.width, energy.paddingLeft, energy.paddingRight, energy.thumb?.intrinsicWidth ?: 0, energy.thumbOffset
            )
            if (at == null || rail == null) {
                markLabel.visibility = View.INVISIBLE
            } else {
                val mirrored = energy.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val center = SliderTicks.centerAtFraction(at, rail, mirrored)
                markLabel.x = SliderTicks.labelLeft(center, markLabel.width.toFloat(), 0f, markRow.width.toFloat())
                markLabel.visibility = View.VISIBLE
            }
        }
        onLaidOut(energy) { placeMark() }
        onLaidOut(markLabel) { placeMark() }
        val applyRange = {
            val topProgress = BatteryRoom.topProgress(top)
            if (filling && top != null) {
                energy.max = topProgress
                energy.progress = topProgress
            } else {
                if (filling) {
                    // A stored Fill with no room known: the amount Home Assistant plans instead.
                    filling = false
                    energy.max = VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS
                    energy.progress = VehicleEnergy.energyProgressNearest(amountKwh)
                }
                energy.max = BatteryRoom.energySliderMaxProgress(top, energy.progress)
            }
            placeMark()
            paint()
        }
        var storedFill = false
        return EnergyControls(
            energy = energy,
            refreshValueLabel = { paint() },
            valueLabel = energyValue,
            setKwh = { kwh, fill ->
                amountKwh = kwh
                supported = fill != null
                storedFill = fill == true
                filling = storedFill
                // The whole scale first, so a stored amount above the top is never cut to it.
                energy.max = VehicleEnergy.ENERGY_SLIDER_MAX_PROGRESS
                energy.progress = VehicleEnergy.energyProgressNearest(kwh)
                applyRange()
            },
            applyRoom = { soc ->
                facts = soc?.let { BatteryRoom.FillFacts(it.roomKwh, it.capacityKwh, it.vehicleMaxPercent, it.efficiency) }
                top = facts?.let { BatteryRoom.fillTopKwh(it) }
                // A stored Fill shows again once a room is known.
                if (storedFill && top != null) filling = true
                applyRange()
            },
            userMoved = { progress ->
                filling = BatteryRoom.isFillStep(progress, energy.max, top, supported)
                storedFill = filling
                if (!filling) amountKwh = VehicleEnergy.energyKwhOfProgress(progress)
                paint()
            },
            filling = { filling }
        )
    }

    private companion object {
        /** How far the "full" line reaches above and below the track. */
        const val MARK_REACH_DP = 5
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
    /**
     * Show a stored amount, where it is even above the battery's room, and the record's
     * `fill_to_limit` (`null` from a Home Assistant without it, which never offers "Fill").
     */
    val setKwh: (Double, Boolean?) -> Unit,
    /**
     * A paired charger's `soc` block (`null` while Home Assistant states none): its room, battery size,
     * efficiency and the car's own limit. Without a room the slider is the ordinary 1-100 kWh one.
     */
    val applyRoom: (DashboardSoc?) -> Unit,
    /** A person moved the slider to [Int]: at the last step past the room that is "Fill", elsewhere not. */
    val userMoved: (Int) -> Unit,
    /** Whether the slider stands at "Fill" now. */
    val filling: () -> Boolean
)
