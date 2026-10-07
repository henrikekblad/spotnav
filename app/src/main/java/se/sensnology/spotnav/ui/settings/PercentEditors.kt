package se.sensnology.spotnav.ui.settings

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ui.charging.EnergyController
import se.sensnology.spotnav.ui.charging.FullMarkDrawable
import se.sensnology.spotnav.ui.charging.bandLabel
import se.sensnology.spotnav.ui.common.SLIDER_TRACK_DP
import se.sensnology.spotnav.ui.common.SliderTicks
import se.sensnology.spotnav.ui.common.TrackBandDrawable
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.addTrackLayer
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine
import se.sensnology.spotnav.ui.common.onLaidOut
import se.sensnology.spotnav.ui.common.openEditor
import se.sensnology.spotnav.ui.common.thickenTrack
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.vehicles.ChargeLimitRange
import se.sensnology.spotnav.vehicles.FloorSlider
import se.sensnology.spotnav.vehicles.LimitSlider
import se.sensnology.spotnav.vehicles.TargetSlider
import kotlin.math.roundToInt

/**
 * The editors a car's charge target, minimum charge level and own charge limit open: what the value is for, the
 * value large, a slider under it, and Save and Cancel as every value editor has. Opening writes nothing; Save
 * writes only a slider that was moved to something other than what is stored.
 */

/** The car's charge target, 0..100 % in whole percent; none stored opens at [maxPercent]'s default, marked so. */
internal fun ViewScope.editTargetSlider(
    title: String,
    current: Double?,
    maxPercent: Double?,
    help: String,
    save: (Double, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    body.addView(helpText(help))
    val amount = amountText()
    body.addView(amount)
    var moved = false
    val seek = percentSeek(100, TargetSlider.opening(current, maxPercent))
    seek.contentDescription = title
    val paint = {
        val value = t(R.string.percent_label, seek.progress)
        amount.text = if (TargetSlider.showsDefault(current, moved)) withDefault(value, t(R.string.vehicle_target_default)) else value
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) moved = true
            paint()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    })
    paint()
    body.addView(seek, seekParams())
    val error = errorLine().also { body.addView(it) }
    openEditor(title, body, error) { done ->
        val value = TargetSlider.toWrite(current, moved, seek.progress)
        if (value == null) done(null) else save(value, done)
    }
}

/** The car's minimum charge level: Off, then 10..80 % in fives, held at [target] with the track past it hatched. */
internal fun ViewScope.editFloorSlider(
    title: String,
    current: Int?,
    target: Double,
    help: String,
    save: (Int?, (String?) -> Unit) -> Unit
) {
    val body = editorBody()
    body.addView(helpText(help))
    val amount = amountText()
    body.addView(amount)
    var moved = false
    val seek = percentSeek(FloorSlider.LAST, FloorSlider.clamp(FloorSlider.index(current), target))
    seek.contentDescription = t(R.string.vehicle_minimum_slider)
    val paint = {
        amount.text = FloorSlider.at(seek.progress)?.let { t(R.string.percent_label, it) } ?: t(R.string.vehicle_minimum_off)
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) moved = true
            // Past the target the minimum cannot go: the thumb is held there.
            val held = FloorSlider.clamp(progress, target)
            if (held != progress) {
                seek.progress = held
                return
            }
            paint()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    })
    paint()
    body.addView(seek, seekParams())
    val cap = FloorSlider.capIndex(target)
    if (cap < FloorSlider.LAST) {
        val from = cap.toFloat() / FloorSlider.LAST
        val height = dp(SLIDER_TRACK_DP)
        addTrackLayer(seek, TrackBandDrawable(cardBackground, stripes = muted, stripeWidth = dp(3).toFloat()).apply {
            this.from = from
            to = 1f
        }, height)
        addTrackLayer(seek, FullMarkDrawable(dark, dp(2).toFloat(), dp(EnergyController.MARK_REACH_DP).toFloat()).apply {
            fraction = from
        }, height)
        // "laddmål 50 %" under the mark, inside the row.
        val word = bandLabel().apply {
            text = FloorSlider.markText(template(R.string.vehicle_minimum_cap), target.roundToInt(), AppLanguageSettings.numberLocale(context))
            visibility = View.INVISIBLE
        }
        val row = FrameLayout(context)
        row.addView(word, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(row)
        val place = {
            val rail = SliderTicks.rail(seek.width, seek.paddingLeft, seek.paddingRight, seek.thumb?.intrinsicWidth ?: 0, seek.thumbOffset)
            if (rail != null) {
                val center = SliderTicks.centerAtFraction(from, rail, seek.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                word.x = SliderTicks.labelLeft(center, word.width.toFloat(), 0f, row.width.toFloat())
                word.visibility = View.VISIBLE
            }
        }
        onLaidOut(seek) { place() }
        onLaidOut(word) { place() }
    }
    // The track's two ends: Off and 80 %.
    body.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(bandLabel().apply { text = t(R.string.vehicle_minimum_off) })
        addView(View(context), weight())
        addView(bandLabel().apply { text = t(R.string.percent_label, FloorSlider.at(FloorSlider.LAST) ?: 0) })
        setPadding(dp(4), 0, dp(4), 0)
    })
    val error = errorLine().also { body.addView(it) }
    openEditor(title, body, error) { done ->
        val write = FloorSlider.toWrite(current, moved, seek.progress)
        if (write == null) done(null) else save(write.level, done)
    }
}

/** The car's own charge limit, over the range and step its integration takes ([LimitSlider]); opens at [current]. */
internal fun ViewScope.editLimitSlider(
    title: String,
    current: Int,
    range: ChargeLimitRange?,
    help: String,
    save: (Int, (String?) -> Unit) -> Unit
) {
    val stops = LimitSlider.stops(range)
    val body = editorBody()
    body.addView(helpText(help))
    val amount = amountText()
    body.addView(amount)
    var moved = false
    val seek = percentSeek(LimitSlider.last(stops), LimitSlider.index(stops, current))
    seek.contentDescription = t(R.string.vehicle_limit_slider)
    val paint = {
        amount.text = t(R.string.percent_label, if (moved) LimitSlider.at(stops, seek.progress) else current)
    }
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) moved = true
            paint()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    })
    paint()
    body.addView(seek, seekParams())
    // The track's two ends: the lowest and the highest limit the car takes.
    body.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(bandLabel().apply { text = t(R.string.percent_label, LimitSlider.at(stops, 0)) })
        addView(View(context), weight())
        addView(bandLabel().apply { text = t(R.string.percent_label, LimitSlider.at(stops, LimitSlider.last(stops))) })
        setPadding(dp(4), 0, dp(4), 0)
    })
    val error = errorLine().also { body.addView(it) }
    openEditor(title, body, error) { done ->
        val value = LimitSlider.toWrite(current, moved, LimitSlider.at(stops, seek.progress))
        if (value == null) done(null) else save(value, done)
    }
}

private fun ViewScope.helpText(text: String) = TextView(context).apply {
    this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(6))
}

private fun ViewScope.amountText() = TextView(context).apply {
    textSize = 30f
    setTextColor(accent)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.START
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    setPadding(0, dp(4), 0, dp(4))
}

/** "80 %" and, muted and smaller after it, "(default)". */
private fun ViewScope.withDefault(value: String, mark: String): CharSequence =
    SpannableStringBuilder(value).append(' ').also { text ->
        val start = text.length
        text.append(mark)
        text.setSpan(ForegroundColorSpan(muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(0.5f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.NORMAL), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

private fun ViewScope.percentSeek(max: Int, value: Int) = SeekBar(context).apply {
    this.max = max
    progress = value.coerceIn(0, max)
    thickenTrack(this)
}

private fun ViewScope.seekParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    .apply { topMargin = dp(4) }
