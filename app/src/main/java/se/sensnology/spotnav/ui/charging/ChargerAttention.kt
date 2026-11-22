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
 * mode that works against SpotNav, the "does not fit" line with its choices (Home Assistant 3.0.0b3), "Release the import
 * target for this charge" while the import target would make the plan miss its departure (where no such line stands in
 * for it), and the "Get started" line that opens the rows left to set up. Each is hidden
 * unless there is something to act on.
 */
internal class ChargerAttention(scope: ViewScope) : ViewScope(scope) {
    private val ownMode = Button(context).apply {
        isAllCaps = false
        visibility = View.GONE
    }
    private val release = Button(context).apply {
        isAllCaps = false
        visibility = View.GONE
    }
    /** The "does not fit" line and its choices, one button each (the card's suggestion row). */
    private val fitLine = TextView(context).apply {
        textSize = 14f
        setTextColor(dark)
        setPadding(0, dp(2), 0, dp(4))
    }
    private val fitChoices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val fit = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(fitLine)
        addView(fitChoices)
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
        addView(fit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        addView(release, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { bottomMargin = dp(4) })
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

    /** "Release the import target for this charge" with [label], or hidden for `null`. */
    fun showRelease(label: String?, onClick: () -> Unit) {
        release.visibility = if (label == null) View.GONE else View.VISIBLE
        release.text = label.orEmpty()
        release.setOnClickListener { onClick() }
    }

    fun setReleaseEnabled(enabled: Boolean) {
        release.isEnabled = enabled
    }

    /** One button under the "does not fit" line: its words and what pressing it does. */
    class FitChoice(val code: String, val label: String, val onClick: (Button) -> Unit)

    /** The "does not fit" line ([line]) with a button per choice, or hidden for `null`. */
    fun showDoesNotFit(line: String?, choices: List<FitChoice>) {
        fit.visibility = if (line == null) View.GONE else View.VISIBLE
        fitLine.text = line.orEmpty()
        fitChoices.removeAllViews()
        if (line == null) return
        for (choice in choices) {
            val button = Button(context).apply {
                isAllCaps = false
                text = choice.label
                tag = choice.code
                setOnClickListener { choice.onClick(this) }
            }
            fitChoices.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2)
            })
        }
    }

    /** The choices' buttons pressable or not (off while a choice is on its way). */
    fun setDoesNotFitEnabled(enabled: Boolean) {
        for (index in 0 until fitChoices.childCount) fitChoices.getChildAt(index).isEnabled = enabled
    }

    /** The "Get started: 2 things left" line, or hidden for `null`. */
    fun showSetup(text: String?, onClick: () -> Unit) {
        setup.visibility = if (text == null) View.GONE else View.VISIBLE
        setup.text = text.orEmpty()
        setup.setOnClickListener { onClick() }
    }
}
