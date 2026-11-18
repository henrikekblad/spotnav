package se.sensnology.spotnav.ui.site

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import se.sensnology.spotnav.R
import se.sensnology.spotnav.chart.DayRollover
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseNow
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords

/** The main screen's pages, in their fixed order. */
internal enum class PageKind { CHARGER, HOUSE, HEAT }

/** A page beside the charger's: its view, and being told when it is in view. */
internal interface SitePage {
    val kind: PageKind
    val view: View

    /** In view with the app in the foreground, or no longer: a page reads and animates only while it is. */
    fun setInView(inView: Boolean)
}

/** What a site's pages last read, kept while the Activity lives, so a page drawn again starts from it. */
internal class SiteMemory {
    var house: House? = null
    var live: HouseNow? = null
}

/** The platform's one-shot timer on the main thread. */
internal class MainTimer : DayRollover.Timer {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    override fun after(delayMs: Long, task: () -> Unit) {
        cancel()
        val run = Runnable { pending = null; task() }
        pending = run
        main.postDelayed(run, delayMs)
    }

    override fun cancel() {
        pending?.let(main::removeCallbacks)
        pending = null
    }
}

/** Which pages a site's Home Assistant serves, apart from the views. */
internal object PageOffer {
    /**
     * The pages to show: the charger's always, the house's where the charger's webhook answers for its site's house
     * ([house], `capabilities.get_house`), the heat's where it answers for the heating and the page is built
     * ([heat]). A Home Assistant before 3.0 states neither: one page, and no icon row.
     */
    fun kinds(house: Boolean, heat: Boolean): List<PageKind> = buildList {
        add(PageKind.CHARGER)
        if (house) add(PageKind.HOUSE)
        if (heat) add(PageKind.HEAT)
    }

    /** The page to open on: the one wanted while it is still offered, else the charger's. */
    fun opening(wanted: PageKind, kinds: List<PageKind>): PageKind = wanted.takeIf { it in kinds } ?: PageKind.CHARGER
}

/**
 * The main screen's pages and the row of icons that chooses between them: the charger (the screen's own scrolling
 * body), the house and the heat. A page exists only when Home Assistant says it serves it ([offer]); with the
 * charger's page alone the row is not drawn and the screen is as it was before there were pages. The page chosen is
 * kept while the Activity lives, across the other screens and a redraw of this one.
 */
internal class SitePages(private val scope: ViewScope, private val chargerPage: View) {
    val pager = PagerLayout(scope.context).apply {
        addView(chargerPage, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onPage = { index -> kinds.getOrNull(index)?.let(::chosen) }
    }

    /** The row of icons, above the pager; gone with one page. */
    val icons = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private var kinds: List<PageKind> = listOf(PageKind.CHARGER)
    private var pages: List<SitePage> = emptyList()
    private var offeredFor: String? = null
    private val known = HashMap<String, List<PageKind>>()
    private val memories = HashMap<String, SiteMemory>()

    /** The page in view, or the one to return to when the main screen is drawn again. */
    var selected: PageKind = PageKind.CHARGER
        private set

    /** Whether the app is in the foreground: the Activity says. */
    var foreground: Boolean = false
        set(value) {
            field = value
            tellPages()
        }

    /** What the charger's page does when it comes back into view (a fresh dashboard); cleared by [reset]. */
    var onChargerInView: (() -> Unit)? = null

    /** Whether the charger's page is the one in view. */
    val chargerInView: Boolean get() = selected == PageKind.CHARGER || PageKind.CHARGER == kinds.singleOrNull()

    /** The pages [profileId]'s Home Assistant last offered in this Activity's life, or `null` before its first answer. */
    fun knownFor(profileId: String): List<PageKind>? = known[profileId]

    fun memory(profileId: String): SiteMemory = memories.getOrPut(profileId) { SiteMemory() }

    /**
     * Another screen is drawn into the body, or this one afresh: the other pages go, the row with them. The page
     * chosen is remembered for [offer].
     */
    fun reset() {
        pages.forEach { it.setInView(false) }
        pages.forEach { pager.removeView(it.view) }
        pages = emptyList()
        kinds = listOf(PageKind.CHARGER)
        offeredFor = null
        onChargerInView = null
        icons.removeAllViews()
        icons.visibility = View.GONE
        pager.onPage = {}
        pager.show(0, animate = false)
        pager.onPage = { index -> kinds.getOrNull(index)?.let(::chosen) }
        chargerPage.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /**
     * Show the pages [profileId]'s Home Assistant offers ([offered], the charger's first); [build] makes each page
     * beside the charger's. The same pages as are shown are left as they are.
     */
    fun offer(profileId: String, offered: List<PageKind>, build: (PageKind) -> SitePage) {
        known[profileId] = offered
        if (offeredFor == profileId && offered == kinds) return
        val wanted = selected
        reset()
        offeredFor = profileId
        kinds = offered
        pages = offered.filter { it != PageKind.CHARGER }.map(build)
        val whole = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        pages.forEach { pager.addView(it.view, whole) }
        selected = PageOffer.opening(wanted, kinds)
        pager.onPage = {}
        pager.show(kinds.indexOf(selected), animate = false)
        pager.onPage = { index -> kinds.getOrNull(index)?.let(::chosen) }
        drawIcons()
        tellPages()
    }

    private fun chosen(kind: PageKind) {
        if (kind == selected) return
        selected = kind
        drawIcons()
        tellPages()
        if (kind == PageKind.CHARGER) onChargerInView?.invoke()
    }

    private fun tellPages() {
        pages.forEach { it.setInView(foreground && it.kind == selected) }
        // A page out of view is not read by a screen reader either.
        chargerPage.importantForAccessibility =
            if (chargerInView) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        pages.forEach {
            it.view.importantForAccessibility =
                if (it.kind == selected) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun drawIcons() {
        icons.removeAllViews()
        icons.visibility = if (kinds.size > 1) View.VISIBLE else View.GONE
        if (kinds.size < 2) return
        val words = scope.cardWords()
        for (kind in kinds) {
            val chosen = kind == selected
            val (icon, name) = when (kind) {
                PageKind.CHARGER -> R.drawable.ic_card_charger to scope.t(R.string.section_charger)
                PageKind.HOUSE -> R.drawable.ic_now_house to words.say("house.title")
                PageKind.HEAT -> R.drawable.ic_now_heat to words.say("heat.title")
            }
            icons.addView(FrameLayout(scope.context).apply {
                contentDescription = name
                isClickable = true
                isFocusable = true
                isSelected = chosen
                setOnClickListener { pager.show(kinds.indexOf(kind), animate = true) }
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(if (chosen) scope.accent else scope.muted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(scope.dp(24), scope.dp(24), Gravity.CENTER))
                // A short line in the accent colour under the page in view.
                addView(View(context).apply { setBackgroundColor(if (chosen) scope.accent else 0) },
                    FrameLayout.LayoutParams(scope.dp(24), scope.dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                // Read as one of several options, the chosen one checked.
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = RadioButton::class.java.name
                        info.isCheckable = true
                        info.isChecked = chosen
                    }
                }
            }, LinearLayout.LayoutParams(scope.dp(72), scope.dp(48)))
        }
    }
}
