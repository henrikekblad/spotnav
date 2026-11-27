package se.sensnology.spotnav.ui.yearreview

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.BuildConfig
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.yearreview.YearReview
import se.sensnology.spotnav.ui.common.ViewScope
import kotlin.math.abs
import kotlin.math.min

/**
 * The year review's story ("Året med SpotNav"), full screen: one fact per panel, the progress bars on top, Back, Close
 * and Next under it, and a swipe between pages; each panel in its tint from the energy palette, its picture floating
 * above the figure, the page's words in one block centred in the panel (scrolled from its top where it is taller),
 * the figure counted up and fireworks behind the cover and the thanks page. With the system's animations off nothing
 * moves. A port of the card's `year-review-view.ts`.
 *
 * The thanks page carries the one support ask of the whole story (GitHub Sponsors, once, light, and only in the
 * build from GitHub: a store build keeps the thanks without the link) and the picture to share or save.
 */
internal class YearReviewStory(
    private val scope: ViewScope,
    private val text: YearReviewText,
    private val review: YearReview,
    /** Whether the reader can open the story again from Settings → Support (the thanks page says so). */
    supportReachable: Boolean,
    private val onClose: () -> Unit = {}
) {
    private val context = scope.context
    private val pages = text.pages(review, supportReachable)
    private var index = 0
    private val darkTheme = se.sensnology.spotnav.app.AppThemeSettings.isDark(context)
    private val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar)
    private val fireworks = FireworksView(context, context.resources.displayMetrics.density)
    private val bars = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val panel = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val back = navButton("‹ " + text.say("yearReview.nav.back"), text.say("yearReview.nav.back")) { go(index - 1) }
    private val close = navButton(text.say("yearReview.nav.close"), text.say("yearReview.nav.close")) { dialog.dismiss() }
    private val next = navButton(text.say("yearReview.nav.next") + " ›", text.say("yearReview.nav.next")) { go(index + 1) }
    private val root = SwipeFrame(context, scope.dp(SWIPE_DP)) { forward -> go(index + if (forward) 1 else -1) }
    private val animators = mutableListOf<ValueAnimator>()
    private var picture: android.graphics.Bitmap? = null
    private var shareNote: String? = null

    fun show() {
        if (pages.isEmpty()) return
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(scope.dp(14), scope.dp(12), scope.dp(14), 0)
            })
            addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(scope.dp(14), 0, scope.dp(14), scope.dp(14))
                addView(back)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(close)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(next)
            })
        }
        root.addView(fireworks, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Clear of the status and navigation bars wherever the window reaches under them.
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener {
            stopMotion()
            onClose()
        }
        dialog.show()
        paint()
        // Next is what the reader wants first (Back is disabled on the cover).
        next.requestFocus()
    }

    /** Turn to another page (clamped); the motion of the one left stops. */
    private fun go(target: Int) {
        val clamped = target.coerceIn(0, pages.size - 1)
        if (clamped == index) return
        index = clamped
        paint()
    }

    private fun stopMotion() {
        fireworks.stop()
        animators.forEach { it.end() }
        animators.forEach { it.cancel() }
        animators.clear()
    }

    private fun paint() {
        val page = pages[index]
        stopMotion()
        val tint = YearReviewColours.of(page.tint, darkTheme)
        // The tint under a darkening from the top left to the bottom right; the cover's to the night blue.
        val shade = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.TRANSPARENT, if (page.id == "cover") 0xBF101246.toInt() else 0x80000000.toInt()))
        root.background = LayerDrawable(arrayOf(ColorDrawable(tint), shade))
        bars.removeAllViews()
        pages.indices.forEach { k ->
            bars.addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = scope.dp(2).toFloat()
                    setColor(if (k <= index) 0xF2FFFFFF.toInt() else 0x4DFFFFFF)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, scope.dp(3), 1f).apply { if (k > 0) marginStart = scope.dp(4) })
        }
        content.removeAllViews()
        content.setPadding(scope.dp(24), scope.dp(14), scope.dp(24), scope.dp(16))
        fillPanel(page)
        panel.contentDescription = text.say("yearReview.nav.page", "page" to (index + 1).toString(), "pages" to pages.size.toString())
        panel.scrollTo(0, 0)
        back.isEnabled = index > 0
        back.alpha = if (back.isEnabled) 1f else 0.35f
        next.isEnabled = index < pages.size - 1
        next.alpha = if (next.isEnabled) 1f else 0.35f
        if (page.fireworks) fireworks.start()
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun gapped(top: Int) = wide().apply { topMargin = scope.dp(top) }

    private fun line(words: String, sp: Float, alpha: Float = 1f, bold: Boolean = false) = TextView(context).apply {
        text = words
        textSize = sp
        setTextColor(Color.WHITE)
        this.alpha = alpha
        setLineSpacing(0f, 1.35f)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun fillPanel(page: StoryPage) {
        val metrics = context.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val heightDp = metrics.heightPixels / metrics.density
        val thanks = page.id == "thanks"
        val artDp = if (thanks) min(widthDp * 0.26f, heightDp * 0.14f).coerceIn(72f, 104f) else min(widthDp * 0.4f, heightDp * 0.24f).coerceIn(96f, 168f)
        val art = ImageView(context).apply {
            setImageResource(YearReviewColours.art(page.id))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = scope.dp(6).toFloat()
        }
        val artSize = (artDp * metrics.density).toInt()
        if (thanks) {
            // The SpotNav icon opposite the heart, still.
            val logoDp = min(widthDp * 0.14f, heightDp * 0.08f).coerceIn(40f, 56f)
            val logo = ImageView(context).apply {
                setImageResource(R.drawable.app_icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(art, LinearLayout.LayoutParams(artSize, artSize))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                val logoSize = (logoDp * metrics.density).toInt()
                addView(logo, LinearLayout.LayoutParams(logoSize, logoSize).apply { topMargin = scope.dp(4) })
            }, wide())
        } else {
            content.addView(art, LinearLayout.LayoutParams(artSize, artSize).apply { topMargin = scope.dp(4); bottomMargin = scope.dp(8) })
        }
        if (motionAllowed()) {
            animators += ObjectAnimator.ofPropertyValuesHolder(
                art,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -scope.dp(7).toFloat(), 0f),
                PropertyValuesHolder.ofFloat(View.ROTATION, 0f, -2f, 0f)
            ).apply {
                duration = 5000
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        content.addView(line(page.eyebrow.uppercase(text.dates), 13.5f, 0.85f, bold = true).apply { letterSpacing = 0.09f }, gapped(0))
        page.words?.let { words ->
            content.addView(line(words, 38f, bold = true).apply { setLineSpacing(0f, 1.05f) }, gapped(12))
        }
        page.figure?.let { figure -> content.addView(figureView(figure), gapped(12)) }
        page.say?.let { content.addView(line(it, if (thanks) 17f else 19.5f), gapped(12)) }
        page.meter?.let { share ->
            val track = FrameLayout(context).apply {
                background = GradientDrawable().apply { cornerRadius = scope.dp(7).toFloat(); setColor(0x40FFFFFF) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                clipToOutline = true
            }
            val fill = View(context).apply { background = GradientDrawable().apply { cornerRadius = scope.dp(7).toFloat(); setColor(0xF2FFFFFF.toInt()) } }
            track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            track.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) view.post {
                    fill.layoutParams = fill.layoutParams.apply { width = ((right - left) * share.coerceIn(0.0, 100.0) / 100).toInt() }
                }
            }
            content.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scope.dp(14)).apply { topMargin = scope.dp(12) })
        }
        if (page.chips.isNotEmpty()) {
            val chips = ChipRow(context, scope.dp(10))
            page.chips.forEach { chip ->
                chips.addView(line(chip, 15.5f).apply {
                    setLineSpacing(0f, 1.15f)
                    background = GradientDrawable().apply { cornerRadius = scope.dp(10).toFloat(); setColor(0x29FFFFFF) }
                    setPadding(scope.dp(11), scope.dp(7), scope.dp(11), scope.dp(7))
                })
            }
            content.addView(chips, gapped(12))
        }
        page.badge?.let { badge ->
            content.addView(line(badge, 12.5f).apply {
                background = GradientDrawable().apply { cornerRadius = scope.dp(999).toFloat(); setColor(0x38000000) }
                setPadding(scope.dp(10), scope.dp(4), scope.dp(10), scope.dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(12) })
        }
        if (thanks) content.addView(thanksActions(), gapped(12))
        page.foot.forEach { content.addView(line(it, 13.5f, 0.85f), gapped(12)) }
    }

    /** The big figure: "≈ " where estimated, the number counted up from zero, the unit smaller. */
    private fun figureView(figure: BigFigure): TextView {
        val view = line("", 52f, bold = true).apply { setLineSpacing(0f, 1.02f) }
        fun show(value: Double) {
            val number = text.figure(figure, value)
            val built = SpannableStringBuilder()
            if (figure.approx) built.append("≈ ")
            built.append(number)
            if (figure.unit.isNotEmpty()) {
                val start = built.length
                built.append(" ").append(figure.unit)
                built.setSpan(RelativeSizeSpan(0.42f), start, built.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            view.text = built
        }
        show(figure.value)
        // Read out as the final figure, whatever frame is shown.
        view.contentDescription = view.text
        if (motionAllowed() && figure.value != 0.0) {
            show(0.0)
            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                duration = COUNT_UP_MS
                // The card's cubic ease-out.
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { show(figure.value * (it.animatedValue as Float)) }
                start()
            }
        }
        return view
    }

    /** The support ask (once, here only, in the build from GitHub) and the picture to share or save. */
    private fun thanksActions(): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (BuildConfig.STORE == "github") {
            column.addView(action(text.say("yearReview.thanks.sponsor"), primary = true) {
                runCatching { scope.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPONSOR_URL))) }
            }, wide())
        }
        column.addView(action(scope.t(R.string.year_review_share), primary = false) { share() }, gapped(8))
        if (YearReviewPicture.canSave) column.addView(action(scope.t(R.string.year_review_save), primary = false) { save() }, gapped(8))
        shareNote?.let { note ->
            column.addView(line(note, 13.5f, 0.9f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, gapped(8))
        }
        return column
    }

    private fun picture(): android.graphics.Bitmap? = picture ?: runCatching { YearReviewPicture.draw(context, text, review) }.getOrNull()?.also { picture = it }

    private fun share() {
        val bitmap = picture()
        val uri = bitmap?.let { runCatching { YearReviewPicture.forSharing(context, it, YearReviewShareLayout.fileName(review)) }.getOrNull() }
        if (uri == null) {
            shareNote = text.say("yearReview.thanks.shareFailed")
            paint()
            return
        }
        runCatching { scope.startActivity(YearReviewPicture.shareIntent(uri, text.say("yearReview.title"))) }
            .onFailure { shareNote = text.say("yearReview.thanks.shareFailed"); paint() }
    }

    private fun save() {
        val bitmap = picture()
        val saved = bitmap != null && YearReviewPicture.save(context, bitmap, YearReviewShareLayout.fileName(review))
        shareNote = scope.t(if (saved) R.string.year_review_saved else R.string.year_review_save_failed)
        paint()
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) 0xFF1B2430.toInt() else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = scope.dp(12).toFloat(); setColor(if (primary) Color.WHITE else 0x2EFFFFFF) }
        stateListAnimator = null
        minHeight = scope.dp(44)
        setOnClickListener { onClick() }
    }

    private fun navButton(label: String, spoken: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        contentDescription = spoken
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = scope.dp(999).toFloat(); setColor(0x33FFFFFF) }
        stateListAnimator = null
        minHeight = scope.dp(40)
        minimumHeight = scope.dp(40)
        minWidth = 0
        minimumWidth = 0
        setPadding(scope.dp(16), 0, scope.dp(16), 0)
        setOnClickListener { onClick() }
    }

    companion object {
        const val SPONSOR_URL = "https://github.com/sponsors/henrikekblad"
        private const val COUNT_UP_MS = 900L
        /** A swipe this far across (dp), more across than down, turns the page. */
        private const val SWIPE_DP = 40
    }
}

/** The story's frame: a sideways swipe turns the page (left: the next one), and is then no tap for what it began on. */
internal class SwipeFrame(context: android.content.Context, private val threshold: Int, private val turn: (forward: Boolean) -> Unit) : FrameLayout(context) {
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) >= threshold && abs(dx) > abs(dy)) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    turn(dx < 0)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
