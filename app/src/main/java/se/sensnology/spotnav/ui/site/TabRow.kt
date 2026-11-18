package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import se.sensnology.spotnav.ui.common.ViewScope

/** One tab: its long name ("Energiflöde"), its short one ("Flöde") and its icon. [key] is what is remembered. */
internal class TabSpec(val key: String, val long: String, val short: String, val icon: Int)

/** How a tab row is worded: long names, short names, or icons alone. */
internal enum class TabFit { LONG, SHORT, ICONS }

internal object TabFitting {
    /**
     * The widest wording a row of [room] has room for, as the Home Assistant card chooses it (`fitTabs`,
     * `frontend/src/house-tabs.ts`): long names while every tab fits on one line, else short ones, else icons. A
     * tab is as wide as its words in their selected (bolder) form plus [padding], with [gap] between tabs, so the
     * wording never changes with the tab chosen. A row not laid out yet (no room known) keeps its long names.
     */
    fun choose(room: Float, long: List<Float>, short: List<Float>, padding: Float, gap: Float): TabFit {
        if (room <= 0f) return TabFit.LONG
        fun fits(words: List<Float>) = words.sumOf { (it + padding).toDouble() } + gap * (words.size - 1).coerceAtLeast(0) <= room + 1.0
        return when {
            fits(long) -> TabFit.LONG
            fits(short) -> TabFit.SHORT
            else -> TabFit.ICONS
        }
    }
}

/**
 * A page's tab row, shared by the house and the heat page: every tab in view on one line, worded by the room there
 * is ([TabFitting]), the chosen one in the text colour with a line in the accent colour under it. Whatever is drawn,
 * a tab's name for a screen reader is its long name. A row of one tab is not worth drawing: the caller leaves it out.
 */
@SuppressLint("ViewConstructor") // built in code from the page's tabs: there is nothing to inflate
internal class TabRowView(
    private val scope: ViewScope,
    private val tabs: List<TabSpec>,
    selected: String,
    /** The colour of the rule under the row. */
    private val rule: Int,
    private val onSelect: (String) -> Unit
) : LinearLayout(scope.context) {
    var selected: String = selected
        private set
    private var fit: TabFit? = null
    private val cells = ArrayList<FrameLayout>()
    private val bold = TextPaint().apply {
        typeface = MEDIUM
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics)
    }

    init {
        orientation = HORIZONTAL
        for (tab in tabs) {
            val cell = FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
                minimumHeight = scope.dp(40)
                contentDescription = tab.long
                setOnClickListener { if (tab.key != this@TabRowView.selected) onSelect(tab.key) }
            }
            cells.add(cell)
            addView(cell, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        word(TabFit.LONG)
    }

    /** Show [key] as the chosen tab. */
    fun select(key: String) {
        selected = key
        fit?.let(::word)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val room = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).toFloat()
            val next = TabFitting.choose(
                room,
                tabs.map { bold.measureText(it.long) },
                tabs.map { bold.measureText(it.short) },
                padding = scope.dp(PAD_DP * 2).toFloat(),
                gap = 0f
            )
            if (next != fit) word(next)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun word(next: TabFit) {
        fit = next
        tabs.forEachIndexed { index, tab ->
            val cell = cells[index]
            val chosen = tab.key == selected
            cell.removeAllViews()
            val colour = if (chosen) scope.dark else scope.muted
            val face: View = if (next == TabFit.ICONS) {
                ImageView(context).apply {
                    setImageResource(tab.icon)
                    imageTintList = ColorStateList.valueOf(colour)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            } else {
                TextView(context).apply {
                    text = if (next == TabFit.SHORT) tab.short else tab.long
                    textSize = TEXT_SP
                    maxLines = 1
                    setTextColor(colour)
                    typeface = if (chosen) MEDIUM else Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    // As wide as its chosen (bolder) form, so choosing a tab never moves the others.
                    minWidth = Math.ceil(bold.measureText(text.toString()).toDouble()).toInt()
                }
            }
            val pad = if (next == TabFit.ICONS) 0 else scope.dp(PAD_DP)
            val size = if (next == TabFit.ICONS) scope.dp(22) else ViewGroup.LayoutParams.WRAP_CONTENT
            cell.addView(face, FrameLayout.LayoutParams(size, size, Gravity.CENTER).apply {
                marginStart = pad; marginEnd = pad; topMargin = scope.dp(6); bottomMargin = scope.dp(8)
            })
            // The rule under the row, and over it the chosen tab's line in the accent colour: the cell's own
            // background, so it is as wide as the cell without having a say in how wide that is.
            cell.background = BottomLine(if (chosen) scope.accent else rule, scope.dp(if (chosen) 2 else 1))
            cell.isSelected = chosen
            // Read as one of several options, the chosen one checked.
            cell.accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = chosen
                }
            }
            // Icons share the row evenly; words keep their own width and share what is left over.
            (cell.layoutParams as LayoutParams).width = if (next == TabFit.ICONS) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    private companion object {
        const val TEXT_SP = 13f
        const val PAD_DP = 7
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

/** A line of [thickness] px along the bottom edge of whatever it is the background of. */
private class BottomLine(colour: Int, private val thickness: Int) : Drawable() {
    private val paint = Paint().apply { color = colour }

    override fun draw(canvas: Canvas) {
        val box = bounds
        canvas.drawRect(box.left.toFloat(), (box.bottom - thickness).toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
