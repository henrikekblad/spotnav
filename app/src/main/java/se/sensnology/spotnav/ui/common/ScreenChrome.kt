package se.sensnology.spotnav.ui.common

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import se.sensnology.spotnav.R

/**
 * The main screen's own header: the app's mark and name on the left, the Settings gear on the
 * right, and nothing else.
 */
internal fun ViewScope.addMainHeader(parent: LinearLayout, onSettings: () -> Unit) {
    parent.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_spotnav_mark)
            imageTintList = ColorStateList.valueOf(dark)
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(28), dp(36)).apply { marginEnd = dp(7) })
        addView(TextView(context).apply {
            text = t(R.string.app_title)
            textSize = 21f
            setTextColor(dark)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        })
        addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(iconAction(R.drawable.ic_settings, t(R.string.settings)) { onSettings() })
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** A panel's own top row: Back, then the panel's localized title. */
internal fun ViewScope.addPanelHeader(parent: LinearLayout, title: String, back: () -> Unit) {
    parent.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        addView(iconAction(R.drawable.ic_back, t(R.string.back)) { back() })
        addView(TextView(context).apply {
            text = title
            textSize = 21f
            setTextColor(dark)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        })
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}
