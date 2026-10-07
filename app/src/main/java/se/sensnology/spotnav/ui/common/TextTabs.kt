package se.sensnology.spotnav.ui.common

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import se.sensnology.spotnav.chart.ViewBounds
import kotlin.math.roundToInt

/** The space on each side of a text tab's word: the same before and after every "|". */
private const val TAB_GAP_DP = 8

/**
 * A compact row of text tabs in a card's header ("kWh | Mål", "EV6 | Niro"): each word wraps its own
 * text with the same gap on both sides of every "|", the chosen one in the accent colour, bold and
 * underlined, the others muted, and the last word's text ends where the card's values end. No word is
 * widened for its touch target: once laid out, each word's target is grown to 48 dp on [host] (the
 * card), the last one's to the right, into the card's side padding (see [setTouchTarget]).
 *
 * Add it with [textTabsLayoutParams]. [onSelect] hears the index of a word that is tapped.
 */
internal fun ViewScope.textTabs(labels: List<String>, selected: Int, host: View, onSelect: (Int) -> Unit): TextTabs {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val words = labels.mapIndexed { index, label ->
        if (index > 0) row.addView(TextView(context).apply {
            text = "|"; textSize = 15f; setTextColor(muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val last = index == labels.lastIndex
        TextView(context).apply {
            text = label
            textSize = 15f
            maxLines = 1
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            setPadding(dp(TAB_GAP_DP), 0, if (last) 0 else dp(TAB_GAP_DP), 0)
            setOnClickListener { onSelect(index) }
        }.also { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
    }
    row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val minimum = dp(48).toFloat()
        val allowed = ViewBounds(0f, 0f, host.width.toFloat(), host.height.toFloat())
        words.forEachIndexed { index, word ->
            val box = boundsInParent(word, host)
            val grown = ActionTarget.hitRect(box, minimum, allowed)
            // The last word grows to the right, where nothing else is, rather than over the "|".
            val left = if (index == words.lastIndex) box.left else grown.left
            val right = if (index == words.lastIndex) minOf(maxOf(box.right, box.left + minimum), allowed.right) else grown.right
            setTouchTarget(host, word, Rect(left.roundToInt(), grown.top.roundToInt(), right.roundToInt(), grown.bottom.roundToInt()))
        }
    }
    return TextTabs(this, row, words).also { it.select(selected) }
}

/** The row [textTabs] made, and which word is chosen. */
internal class TextTabs(private val scope: ViewScope, val row: LinearLayout, val words: List<TextView>) {
    fun select(index: Int) {
        words.forEachIndexed { at, word -> style(word, at == index) }
    }

    private fun style(word: TextView, chosen: Boolean) {
        word.setTextColor(if (chosen) scope.accent else scope.muted)
        word.typeface = if (chosen) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        word.paintFlags = if (chosen) word.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            else word.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        word.isSelected = chosen
        // Read as one of several options, the chosen one checked.
        word.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = chosen
            }
        }
    }
}

/** Where a header's text tabs sit: as tall as the title, their targets reaching past it (see [textTabs]). */
internal fun textTabsLayoutParams() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
)
