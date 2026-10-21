package se.sensnology.spotnav.ui.common

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R

/**
 * The notice for a charger whose webhook SpotNav in Home Assistant no longer recognises: [text] in the
 * offline notice's attention style, with a "Pair again" button that runs [onPairAgain]. The same on the
 * charging screen and in Settings -> Home Assistant.
 */
internal fun ViewScope.pairAgainNotice(text: String, onPairAgain: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(6))
        background = GradientDrawable().apply {
            setColor(0x1AD47A19)
            setStroke(dp(1), ATTENTION_COLOUR)
            cornerRadius = dp(8).toFloat()
        }
        addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(ATTENTION_COLOUR)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(Button(context).apply {
            this.text = t(R.string.instance_pair_again)
            isAllCaps = false
            setOnClickListener { onPairAgain() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }
