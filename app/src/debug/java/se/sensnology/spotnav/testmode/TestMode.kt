package se.sensnology.spotnav.testmode

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppThemeSettings

/** The test-mode entry points, in the *debug* build only. */
object TestMode {
    /** The amber the charger card already uses for "this needs attention". */
    private const val BANNER = 0xFFD47A19.toInt()
    private const val BANNER_TEXT = 0xFF1A1206.toInt()

    /** A settings-tab section that opens [TestModeActivity]. */
    fun addSettingsSection(activity: Activity, parent: LinearLayout) {
        parent.addView(TextView(activity).apply {
            text = activity.getString(R.string.test_mode_title)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(activity, android.R.attr.textColorPrimary))
            setPadding(0, dp(activity, 18), 0, dp(activity, 4))
        })
        parent.addView(TextView(activity).apply {
            text = activity.getString(R.string.test_mode_intro)
            textSize = 13f
            setTextColor(themeColor(activity, android.R.attr.textColorSecondary))
        })
        parent.addView(Button(activity).apply {
            text = activity.getString(R.string.test_mode_title)
            isAllCaps = false
            setOnClickListener { activity.startActivity(TestModeActivity.launch(activity)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)).apply { topMargin = dp(activity, 8) })
    }

    /**
     * A banner saying test mode is on, with a way out of it, drawn at the top of the charging
     * screen.
     */
    fun addBanner(activity: Activity, parent: LinearLayout) {
        if (!TestModeSnapshotStore.forContext(activity).exists()) return
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            background = GradientDrawable().apply {
                setColor(BANNER)
                cornerRadius = dp(activity, 8).toFloat()
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.test_mode_banner)
                    textSize = 14f
                    setTextColor(BANNER_TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(activity).apply {
                    text = activity.getString(R.string.test_mode_leave)
                    isAllCaps = false
                    setOnClickListener { TestModeActivity.leaveAndRestart(activity) }
                })
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(activity, 10) })
    }

    /** The theme's own colour for [attribute], so a debug screen still matches. */
    /** The same two text colours the config screen uses, chosen the same way. */
    private fun themeColor(activity: Activity, attribute: Int): Int =
        if (attribute == android.R.attr.textColorSecondary) {
            if (AppThemeSettings.isDark(activity)) 0xFFA8B1BC.toInt() else 0xFF667180.toInt()
        } else {
            if (AppThemeSettings.isDark(activity)) 0xFFF5F7FA.toInt() else 0xFF192029.toInt()
        }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
