package se.sensnology.spotnav.ui.charging

import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.ui.common.ViewScope

/**
 * What Home Assistant asks the person to do about this charger, under the status line: the button of a charger's own
 * mode that works against SpotNav, and the "Get started" line that opens the rows left to set up. Each is hidden
 * unless there is something to act on.
 */
internal class ChargerAttention(scope: ViewScope) : ViewScope(scope) {
    private val ownMode = Button(context).apply {
        isAllCaps = false
        visibility = View.GONE
    }
    private val setup = TextView(context).apply {
        textSize = 13f
        setTextColor(accent)
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        setPadding(0, dp(2), 0, dp(6))
        visibility = View.GONE
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(ownMode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { bottomMargin = dp(4) })
        addView(setup)
    }

    /** The own-mode button with [label], or hidden for `null`. */
    fun showOwnMode(label: String?, onClick: () -> Unit) {
        ownMode.visibility = if (label == null) View.GONE else View.VISIBLE
        ownMode.text = label.orEmpty()
        ownMode.setOnClickListener { onClick() }
    }

    fun setOwnModeEnabled(enabled: Boolean) {
        ownMode.isEnabled = enabled
    }

    /** The "Get started: 2 things left" line, or hidden for `null`. */
    fun showSetup(text: String?, onClick: () -> Unit) {
        setup.visibility = if (text == null) View.GONE else View.VISIBLE
        setup.text = text.orEmpty()
        setup.setOnClickListener { onClick() }
    }
}
