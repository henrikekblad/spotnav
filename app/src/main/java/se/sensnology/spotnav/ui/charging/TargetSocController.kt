package se.sensnology.spotnav.ui.charging

import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.dashboard.DashboardSoc
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.planning.ChargeNeed
import se.sensnology.spotnav.ui.common.LABEL_GAP_DP
import se.sensnology.spotnav.ui.common.SLIDER_TRACK_DP
import se.sensnology.spotnav.ui.common.SliderMarks
import se.sensnology.spotnav.ui.common.SliderTicks
import se.sensnology.spotnav.ui.common.TrackBandDrawable
import se.sensnology.spotnav.ui.common.darker
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.onLaidOut
import se.sensnology.spotnav.ui.common.slider
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.vehicles.FloorSlider
import se.sensnology.spotnav.vehicles.PairedTarget
import se.sensnology.spotnav.vehicles.TargetNeed
import se.sensnology.spotnav.vehicles.TargetVerdict
import se.sensnology.spotnav.vehicles.VehicleEnergy
import se.sensnology.spotnav.vehicles.VehicleStatus

/** The target state-of-charge slider and the words a paired charger puts around it. */
internal class TargetSocController(scope: ViewScope) : ViewScope(scope) {
    /**
     * Its stored value still lives on this charger profile, and only a real drag writes it: a re-
     * render, a new vehicle or a mode switch sets the slider programmatically and never commits.
     */
    fun add(
        parent: LinearLayout,
        profile: ChargerProfile?,
        persist: (Int) -> Unit
    ): TargetSocControls {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // The track's thumb draws its pressed halo outside its own box (the slot that holds
            // this control leaves room for it); a clip here would throw that room away again.
            clipChildren = false
        }
        parent.addView(container)
        val targetValue = valueLabel()
        // The track is the whole scale, 0..100, and stays there: Two things fall out of that.
        val targetSoc = slider(
            t(R.string.vehicle_target), 0, 100,
            profile?.targetSocPercent ?: VehicleEnergy.DEFAULT_TARGET_SOC_PERCENT,
            targetValue, container
        ) { value -> targetValue.text = t(R.string.percent_label, value) }
        // The shaded ends ride on the framework's own track:
        val track = targetSoc.progressDrawable
        val shading = TargetTrackShading(mutedShadingColour())
        val trackInsets = Rect()
        track.getPadding(trackInsets)
        // The shading is drawn at the thickness `slider()` has just given the track, read back from
        // the platform drawable rather than agreed separately:
        val trackHeight = ((track as? LayerDrawable)?.getDrawable(0)?.intrinsicHeight ?: 0)
            .takeIf { it > 0 } ?: dp(SLIDER_TRACK_DP)
        // One more layer of the track itself, never a wrapper around it: the slider finds its fill and tints
        // it by the track's own `android.R.id.progress` layer, which a wrapper would hide.
        val layers = track as? LayerDrawable
        if (layers != null) {
            val index = layers.addLayer(shading)
            // The shading is drawn in the same rect the track paints itself in, which is inset
            // where the platform drawable says so (it is how a SeekBar makes room for the thumb).
            layers.setLayerInset(index, trackInsets.left, 0, trackInsets.right, 0)
            layers.setLayerHeight(index, trackHeight)
            layers.setLayerGravity(index, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
            targetSoc.progressDrawable = layers
        }
        // A paired car's minimum charge level: the track from 0 to it in a darker tone of the fill, under the
        // marks, with "min 30 %" under the middle of that part.
        val floorBand = TrackBandDrawable(darker(accent, FLOOR_DARKEN))
        if (layers != null) {
            val index = layers.addLayer(floorBand)
            layers.setLayerInset(index, trackInsets.left, 0, trackInsets.right, 0)
            layers.setLayerHeight(index, trackHeight)
            layers.setLayerGravity(index, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
            targetSoc.progressDrawable = layers
        }
        // A paired car's level now and its own charge limit, as marks on the track with a short word
        // under each ("nu", "gräns"), as the kWh slider marks "fullt".
        val nowTick = FullMarkDrawable(dark, dp(2).toFloat(), dp(EnergyController.MARK_REACH_DP).toFloat())
        val limitTick = FullMarkDrawable(dark, dp(2).toFloat(), dp(EnergyController.MARK_REACH_DP).toFloat())
        if (layers != null) {
            for (tick in listOf(nowTick, limitTick)) {
                val index = layers.addLayer(tick)
                layers.setLayerInset(index, trackInsets.left, 0, trackInsets.right, 0)
                layers.setLayerHeight(index, trackHeight)
                layers.setLayerGravity(index, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
            }
            targetSoc.progressDrawable = layers
        }
        // The ends' own numbers, under the track. Decoration around a labelled control:
        val floorLabel = bandLabel()
        val ceilingLabel = bandLabel()
        val bandRow = FrameLayout(context)
        // Wrap-content, explicitly:
        val labelParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bandRow.addView(floorLabel, labelParams)
        bandRow.addView(ceilingLabel, labelParams)
        container.addView(bandRow)
        var band: ChargeNeed.TargetRange? = null
        var currentShading = TargetShading.of(null)
        val placeLabels = {
            // Measured against the track's own rect -- the same one the shading is drawn in -- so a
            // number sits under the edge it names, and the thumb's own place is the same fraction
            // of the same width.
            val places = TargetShading.labels(
                (bandRow.width - trackInsets.left - trackInsets.right).coerceAtLeast(0),
                floorLabel.width, ceilingLabel.width, currentShading, dp(LABEL_GAP_DP)
            )
            floorLabel.x = (trackInsets.left + (places.floorX ?: 0)).toFloat()
            ceilingLabel.x = (trackInsets.left + (places.ceilingX ?: 0)).toFloat()
            // Invisible rather than gone: a label that is not drawn still has to be measured,
            // because the other one's place is measured against it.
            floorLabel.visibility = if (places.floorX == null) View.INVISIBLE else View.VISIBLE
            ceilingLabel.visibility = if (places.ceilingX == null) View.INVISIBLE else View.VISIBLE
        }
        onLaidOut(bandRow) { placeLabels() }
        onLaidOut(floorLabel) { placeLabels() }
        onLaidOut(ceilingLabel) { placeLabels() }
        // The marks' words: centred under their ticks, on a second level when they would touch.
        val nowWord = bandLabel().apply { text = t(R.string.target_mark_now); visibility = View.INVISIBLE }
        val limitWord = bandLabel().apply { text = t(R.string.target_mark_limit); visibility = View.INVISIBLE }
        val minWord = bandLabel().apply { visibility = View.INVISIBLE }
        val markRow = FrameLayout(context).apply { visibility = View.GONE }
        for (word in listOf(minWord, nowWord, limitWord)) {
            markRow.addView(word, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Right under the track, where the band's numbers go for a standalone charger.
        container.addView(markRow, container.indexOfChild(bandRow))
        var nowAt: Float? = null
        var limitAt: Float? = null
        var minAt: Float? = null
        val placeMarks = {
            nowTick.fraction = nowAt
            limitTick.fraction = limitAt
            val rail = SliderTicks.rail(
                targetSoc.width, targetSoc.paddingLeft, targetSoc.paddingRight, targetSoc.thumb?.intrinsicWidth ?: 0, targetSoc.thumbOffset
            )
            val words = mapOf("min" to minWord, "now" to nowWord, "limit" to limitWord)
            val mirrored = targetSoc.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val marks = if (rail == null) emptyList() else listOfNotNull(
                minAt?.let { SliderMarks.Mark("min", SliderTicks.centerAtFraction(it, rail, mirrored), minWord.width.toFloat()) },
                nowAt?.let { SliderMarks.Mark("now", SliderTicks.centerAtFraction(it, rail, mirrored), nowWord.width.toFloat()) },
                limitAt?.let { SliderMarks.Mark("limit", SliderTicks.centerAtFraction(it, rail, mirrored), limitWord.width.toFloat()) }
            )
            val placed = SliderMarks.place(marks, markRow.width.toFloat(), dp(6).toFloat()).associateBy { it.key }
            // One line per level: a word on the second level sits one line lower, and the row is as tall as
            // its lowest word.
            val line = maxOf(minWord.height, nowWord.height, limitWord.height).takeIf { it > 0 } ?: nowWord.lineHeight
            for ((key, word) in words) {
                val at = placed[key]
                word.visibility = if (at == null) View.INVISIBLE else View.VISIBLE
                if (at != null) {
                    word.x = at.left
                    word.translationY = (at.level * line).toFloat()
                }
            }
            val levels = (placed.values.maxOfOrNull { it.level } ?: 0) + 1
            if (markRow.minimumHeight != levels * line) {
                markRow.minimumHeight = levels * line
                // Asked for while a layout pass may be running: measured again on the next one.
                markRow.post { markRow.requestLayout() }
            }
        }
        onLaidOut(targetSoc) { placeMarks() }
        onLaidOut(nowWord) { placeMarks() }
        onLaidOut(limitWord) { placeMarks() }
        onLaidOut(minWord) { placeMarks() }
        onLaidOut(markRow) { placeMarks() }
        var storedTargetSocPercent: Int? = profile?.targetSocPercent
        // A paired charger's own words around the slider: what is known now is on the track as marks
        // (above); under it the verdict ("Ingen laddning behövs nu" / "Laddar till bilens gräns, Y %")
        // and the energy the target needs, recomputed with Home Assistant's own formula as the slider
        // moves (see [PairedTarget]).
        val verdictLine = pairedLine()
        val needLine = pairedLine()
        container.addView(verdictLine)
        container.addView(needLine)
        var pairedSoc: DashboardSoc? = null
        var pairedVehicles: List<DashboardVehicle> = emptyList()
        var pairedPicked: String? = null
        val paintPaired = {
            val soc = pairedSoc
            if (soc == null) {
                for (line in listOf(verdictLine, needLine)) { line.text = ""; line.visibility = View.GONE }
                nowAt = null
                limitAt = null
                minAt = null
                floorBand.to = null
                placeMarks()
            } else {
                val facts = PairedTarget.facts(soc, pairedVehicles, pairedPicked)
                val target = targetSoc.progress.toDouble()
                val locale = AppLanguageSettings.numberLocale(context)
                // The level now and the car's own limit, as marks on the 0..100 track.
                nowAt = facts.now?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
                // "≈ nu": Home Assistant's estimate between readings says so.
                nowWord.text = t(if (facts.estimated) R.string.target_mark_now_estimated else R.string.target_mark_now)
                limitAt = facts.limit?.let { (TargetNeed.chargeCeiling(it) / 100.0).toFloat().coerceIn(0f, 1f) }
                // The car's minimum, no further than the target being chosen.
                val floor = FloorSlider.segment(facts.minPercent, target)
                floorBand.to = floor?.end
                minAt = floor?.label
                minWord.text = floor?.let { FloorSlider.markText(template(R.string.target_mark_min), it.percent, locale) }.orEmpty()
                placeMarks()
                // Once the words have their sizes for this text, place them again.
                markRow.post { placeMarks() }
                val verdictKind = PairedTarget.verdict(facts, target)
                val verdict = when (verdictKind) {
                    TargetVerdict.NO_NEED -> t(R.string.paired_target_no_need)
                    TargetVerdict.TO_LIMIT -> t(
                        R.string.paired_target_to_limit,
                        percentText(TargetNeed.chargeCeiling(facts.limit).toDouble(), locale)
                    )
                    TargetVerdict.NONE -> ""
                }
                verdictLine.text = verdict
                verdictLine.visibility = if (verdict.isEmpty()) View.GONE else View.VISIBLE
                val kwh = PairedTarget.needKwh(facts, target)
                needLine.text = t(
                    R.string.paired_need,
                    kwh?.let { String.format(locale, "%.1f kWh", it) } ?: t(R.string.paired_need_unknown)
                )
                // "Ingen laddning behövs nu" says it all: no "0,0 kWh" under it.
                needLine.visibility = if (PairedTarget.showsNeed(verdictKind)) View.VISIBLE else View.GONE
            }
        }
        val refreshLabel = {
            targetValue.text = t(R.string.percent_label, targetSoc.progress)
            paintPaired()
        }
        return TargetSocControls(
            slider = targetSoc,
            container = container,
            progress = { targetSoc.progress },
            enforceBand = { value -> band?.clamp(value) ?: value },
            refreshValueLabel = { refreshLabel() },
            valueLabel = targetValue,
            applyPaired = { soc, vehicles, picked ->
                // A paired charger's track has no band, so no row of band numbers under it either.
                bandRow.visibility = if (soc != null) View.GONE else View.VISIBLE
                // The marks' words are a paired charger's alone.
                markRow.visibility = if (soc != null) View.VISIBLE else View.GONE
                pairedSoc = soc
                pairedVehicles = vehicles
                pairedPicked = picked
                if (soc != null) {
                    band = PairedTarget.RANGE
                    currentShading = TargetShading.of(null)
                    shading.shading = currentShading
                    floorLabel.text = ""
                    ceilingLabel.text = ""
                    targetSoc.contentDescription = t(R.string.vehicle_target)
                    placeLabels()
                }
                paintPaired()
            },
            applyRemote = { percent ->
                if (percent != null) {
                    targetSoc.progress = percent.coerceIn(0, 100)
                    refreshLabel()
                }
            },
            applyVehicle = { vehicle ->
                if (vehicle != null) {
                    val range = ChargeNeed.targetRange(vehicle.socPercent, vehicle.targetSocPercentMax)
                    band = range
                    // What the track draws for this car.
                    currentShading = TargetShading.of(vehicle)
                    shading.shading = currentShading
                    floorLabel.text = currentShading.floorPercent
                        ?.let { t(R.string.percent_label, it) }.orEmpty()
                    ceilingLabel.text = currentShading.ceilingPercent
                        ?.let { t(R.string.percent_label, it) }
                        ?: t(R.string.target_no_limit)
                    // The band in words, for the screen reader the shading is invisible to. The
                    // value the bar announces is the thumb's own number, on the same 0..100 scale.
                    targetSoc.contentDescription = currentShading.floorPercent?.let { floor ->
                        currentShading.ceilingPercent?.let { ceiling -> t(R.string.target_description, floor, ceiling) }
                            ?: t(R.string.target_description_no_limit, floor)
                    }
                    placeLabels()
                    // A fresh number can be a different width, and the layout callback only fires
                    // when something's *size* changes.
                    bandRow.post { placeLabels() }
                    // The stored choice, inside the car's own limit as before, and then inside the
                    // band:
                    val target = range.clamp(
                        VehicleEnergy.effectiveTargetSocPercent(storedTargetSocPercent, vehicle.targetSocPercentMax)
                    )
                    targetSoc.progress = target
                    targetValue.text = t(R.string.percent_label, target)
                } else {
                    band = null
                    currentShading = TargetShading.of(null)
                    shading.shading = currentShading
                    floorLabel.text = ""
                    ceilingLabel.text = ""
                    targetSoc.contentDescription = t(R.string.vehicle_target)
                    placeLabels()
                }
            },
            commit = {
                storedTargetSocPercent = targetSoc.progress
                persist(storedTargetSocPercent)
            }
        )
    }
}

/** How much darker than the fill the minimum's part of the track is. */
private const val FLOOR_DARKEN = 0.45f

/** What [TargetSocController.add] hands back: */
internal class TargetSocControls(
    val slider: SeekBar,
    val container: LinearLayout,
    val progress: () -> Int,
    /**
     * The band, applied to a value the bar is about to show. a drag into a shaded end comes back to
     * the end, and the shaded end is what explains why.
     */
    val enforceBand: (Int) -> Int,
    val refreshValueLabel: () -> Unit,
    /** The label the slider writes its own step into (see showExactValues). */
    val valueLabel: TextView,
    val applyVehicle: (VehicleStatus?) -> Unit,
    /**
     * A paired charger's dashboard `soc` block (`null` when there is none, which ends the paired
     * words and leaves the band to [applyVehicle]), its vehicles, and the vehicle picked in the
     * card, for the words around the slider and the full 0-100 scale.
     */
    val applyPaired: (DashboardSoc?, List<DashboardVehicle>, String?) -> Unit,
    /**
     * Show a target without saving it: `null` means the record states none, which leaves the
     * control where it is rather than inventing one.
     */
    val applyRemote: (Int?) -> Unit,
    val commit: () -> Unit
)
