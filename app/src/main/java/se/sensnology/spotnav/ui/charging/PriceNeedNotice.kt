package se.sensnology.spotnav.ui.charging

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.settings.StandalonePrice

/**
 * What the plan card shows in place of its chart when an unpaired phone has no source for prices (design
 * 5.2): what is missing, and the way to fix it here -- add or replace the ENTSO-E key, or pair with Home
 * Assistant, which needs no key. Hidden whenever there are prices or nothing to act on.
 */
internal class PriceNeedNotice(scope: ViewScope) : ViewScope(scope) {
    /** Open the key's how-to (set by the screen). */
    var onKey: () -> Unit = {}

    /** Open Settings at its Home Assistant card (set by the screen). */
    var onPair: () -> Unit = {}

    private val message = TextView(context).apply {
        textSize = 15f
        setTextColor(dark)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val keyButton = button { onKey() }
    private val pairButton = button { onPair() }.apply { text = t(R.string.price_need_pair) }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(6))
        background = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(dp(1), muted)
            cornerRadius = dp(8).toFloat()
        }
        addView(message)
        addView(keyButton, buttonParams())
        addView(pairButton, buttonParams())
        visibility = View.GONE
    }

    /** Show [notice], or hide the whole notice for `null`. */
    fun show(notice: StandalonePrice.Notice?) {
        view.visibility = if (notice == null) View.GONE else View.VISIBLE
        notice ?: return
        message.text = t(
            when (notice) {
                StandalonePrice.Notice.ADD_KEY -> R.string.price_need_key
                StandalonePrice.Notice.REPLACE_KEY -> R.string.price_need_rejected
                StandalonePrice.Notice.NEEDS_HOME_ASSISTANT -> R.string.area_needs_home_assistant
            }
        )
        keyButton.visibility = if (notice == StandalonePrice.Notice.NEEDS_HOME_ASSISTANT) View.GONE else View.VISIBLE
        keyButton.text = t(if (notice == StandalonePrice.Notice.REPLACE_KEY) R.string.price_need_replace_key else R.string.price_need_add_key)
        // A key someone has but ENTSO-E refuses is fixed by replacing it: one action.
        pairButton.visibility = if (notice == StandalonePrice.Notice.REPLACE_KEY) View.GONE else View.VISIBLE
    }

    val shown: Boolean get() = view.visibility == View.VISIBLE

    private fun button(onTap: () -> Unit) = Button(context).apply {
        isAllCaps = false
        setOnClickListener { onTap() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(4) }
}
