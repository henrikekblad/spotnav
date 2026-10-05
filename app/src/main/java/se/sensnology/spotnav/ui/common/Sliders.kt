package se.sensnology.spotnav.ui.common

import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * A slider with its own heading (`title` + `valueView`), or — with a null [title] — just the
 * slider, for a popover that renders the name and the value itself. Either way the caller's
 * `updateValue` owns the value text.
 */
internal fun ViewScope.slider(title: String?, min: Int, max: Int, value: Int, valueView: TextView, parent: LinearLayout, updateValue: (Int) -> Unit): SeekBar {
    if (title != null) {
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(dark) }, weight())
        heading.addView(valueView)
        parent.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val seek = SeekBar(context).apply { this.max = max - min; progress = (value - min).coerceIn(0, max - min) }
    updateValue(seek.progress + min)
    thickenTrack(seek)
    parent.addView(seek, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = if (title != null) dp(TRACK_TOP_DP) else 0 })
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateValue(progress + min)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    })
    return seek
}

/** Make a slider's track thick enough to aim at, and answer how thick it ends up. */
internal fun ViewScope.thickenTrack(seek: SeekBar): Int {
    val thickness = dp(SLIDER_TRACK_DP)
    val track = seek.progressDrawable as? LayerDrawable ?: return thickness
    var resized = false
    for (index in 0 until track.numberOfLayers) {
        val layer = track.getDrawable(index) ?: continue
        // The fill sits in a clip on older platforms and in a scale on newer ones: both are wrappers, and
        // the shape inside is what is thickened, or the fill stays a thin line over a thick track.
        val shape = (layer as? DrawableWrapper)?.drawable ?: layer
        if (shape is GradientDrawable) {
            // No width of its own: the shape spans the layer it sits in, which is what the
            // platform's own `<size>` leaves it doing.
            shape.setSize(NO_INTRINSIC_WIDTH, thickness)
            shape.setCornerRadius(thickness / 2f)
            resized = true
        }
        // A layer can carry a height of its own from the platform's drawable, which wins over its shape's
        // size: the newer track gives the fill one, so only the background thickened. Every layer gets the
        // track's thickness, centred, so the fill is as thick as the track it fills.
        track.setLayerHeight(index, thickness)
        track.setLayerGravity(index, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
    }
    return if (resized) thickness else track.intrinsicHeight.takeIf { it > 0 } ?: thickness
}
