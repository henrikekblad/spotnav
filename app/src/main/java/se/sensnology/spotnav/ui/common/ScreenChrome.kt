package se.sensnology.spotnav.ui.common

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R

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
