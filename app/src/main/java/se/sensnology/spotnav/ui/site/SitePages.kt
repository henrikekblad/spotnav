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
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseNow
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords
import se.sensnology.spotnav.ui.common.iconAction

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
    var heat: Heat? = null
    /** Whether the heating has something to show, as the last read of `get_heat` said (`null`: not asked yet). */
    var heatShown: Boolean? = null
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
     * The pages to show: the charger's where there is a charger ([charger]; a home without one has none), the house's
     * where the webhook answers for the site's house ([house], `capabilities.get_house`), the heat's where it answers
     * for the heating and the page is built ([heat]). A Home Assistant before 3.0 states neither: the charger's page
     * alone, and no icon row.
     */
    fun kinds(house: Boolean, heat: Boolean, charger: Boolean = true): List<PageKind> = buildList {
        if (charger) add(PageKind.CHARGER)
        if (house) add(PageKind.HOUSE)
        if (heat) add(PageKind.HEAT)
    }

    /** The row of page icons: shown only with two or more pages (owner 2026-11-18). */
    fun iconsShown(kinds: List<PageKind>): Boolean = kinds.size >= 2

    /** The page to open on: the one wanted while it is still offered, else the first (the charger's, where there is one). */
    fun opening(wanted: PageKind, kinds: List<PageKind>): PageKind = wanted.takeIf { it in kinds } ?: kinds.firstOrNull() ?: PageKind.CHARGER

    /** Whether [kinds] (as remembered) is a list of pages: in their fixed order, each once, the charger's first if there. */
    fun valid(kinds: List<PageKind>): Boolean = kinds.isNotEmpty() && kinds == kinds.distinct().sortedBy { it.ordinal }
}

/**
 * The main screen's pages and the top row that chooses between them: the charger (the screen's own scrolling body),
 * the house and the heat. A page exists only when Home Assistant says it serves it ([offer]); with the charger's
 * page alone the row has no page icons and there is nothing to swipe. The page in view is remembered per charger:
 * across the other screens and a redraw of this one, and on this phone for the next time the app starts, where it
 * is opened again while Home Assistant still offers it.
 */
internal class SitePages(
    private val scope: ViewScope,
    private val chargerPage: View,
    private val preferences: SitePreferences = SitePreferences.of(scope.context)
) {
    val pager = PagerLayout(scope.context).apply {
        addView(chargerPage, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onPage = { index -> kinds.getOrNull(index)?.let(::chosen) }
    }

    /** The page icons, in the middle of [top]; none with one page. */
    private val icons = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    /** The Settings gear's place at the right end of [top]. */
    private val gear = FrameLayout(scope.context)

    /**
     * The main screen's top row, above the pager and still while a page scrolls: the app's mark at the left end, the
     * page icons in the middle and the Settings gear at the right end. With one page it has the mark and the gear
     * alone, so the screen is laid out the same whatever Home Assistant serves. Gone on the other screens.
     */
    val top = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        // The mark and the gear in boxes of one width, so the icons between them are in the screen's middle.
        addView(FrameLayout(context).apply {
            addView(ImageView(context).apply {
                // The Home Assistant card's coloured product mark: a picture, not a control (no click, ripple or focus).
                setImageResource(R.drawable.ic_brand_mark)
                contentDescription = scope.getString(R.string.app_name)
                isClickable = false
                isFocusable = false
            }, FrameLayout.LayoutParams(scope.dp(28), scope.dp(28), Gravity.START or Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(scope.dp(48), scope.dp(48)))
        addView(icons, LinearLayout.LayoutParams(0, scope.dp(48), 1f))
        addView(gear, LinearLayout.LayoutParams(scope.dp(48), scope.dp(48)))
    }

    private var kinds: List<PageKind> = listOf(PageKind.CHARGER)
    private var pages: List<SitePage> = emptyList()
    private var offeredFor: String? = null
    private val known = HashMap<String, List<PageKind>>()
    private val chosenFor = HashMap<String, PageKind>()
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

    /**
     * The pages [profileId]'s Home Assistant last offered: in this Activity's life, else as remembered from the last
     * time the app ran; `null` when it never answered.
     */
    fun knownFor(profileId: String): List<PageKind>? = known[profileId] ?: run {
        val names = PageKind.entries.map { it.name }
        val kept = preferences.pages(profileId, names).map(PageKind::valueOf)
        // Pages in their fixed order (the charger's first where there is one); anything else stored is not a list of pages.
        kept.takeIf { PageOffer.valid(it) }
    }

    /**
     * The main screen is on show: the top row with its gear, which opens Settings. The page icons come with
     * [offer].
     */
    fun showTop(onSettings: () -> Unit) {
        gear.removeAllViews()
        gear.addView(scope.iconAction(R.drawable.ic_settings, scope.t(R.string.settings)) { onSettings() })
        top.visibility = View.VISIBLE
    }

    fun memory(profileId: String): SiteMemory = memories.getOrPut(profileId) { SiteMemory() }

    /**
     * Another screen is drawn into the body, or this one afresh: the other pages go, and the top row with them. The
     * page in view is remembered for [offer].
     */
    fun reset() {
        clearPages()
        onChargerInView = null
        gear.removeAllViews()
        top.visibility = View.GONE
    }

    /** Take the pages beside the charger's away, and their icons; the charger's page is the one in view. */
    private fun clearPages() {
        pages.forEach { it.setInView(false) }
        pages.forEach { pager.removeView(it.view) }
        pages = emptyList()
        kinds = listOf(PageKind.CHARGER)
        offeredFor = null
        // A home without a charger took the charger's page (the scrolling body) out: it is back for every other screen.
        if (chargerPage.parent == null) pager.addView(chargerPage, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        icons.removeAllViews()
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
        if (known[profileId] != offered) preferences.setPages(profileId, offered.map { it.name })
        known[profileId] = offered
        if (offeredFor == profileId && offered == kinds) return
        // The page chosen for this charger while the Activity lived, else the one last in view when the app last ran.
        val wanted = chosenFor[profileId]
            ?: preferences.page(profileId, PageKind.entries.map { it.name })?.let(PageKind::valueOf)
            ?: offered.firstOrNull() ?: PageKind.CHARGER
        clearPages()
        offeredFor = profileId
        kinds = offered
        pages = offered.filter { it != PageKind.CHARGER }.map(build)
        // A home without a charger: no charger's page, so the pages are the pager's only children.
        if (PageKind.CHARGER !in offered) pager.removeView(chargerPage)
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
        offeredFor?.let {
            chosenFor[it] = kind
            preferences.setPage(it, kind.name)
        }
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
        if (!PageOffer.iconsShown(kinds)) return
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
                    // Lifted off the box's bottom edge, so it does not touch the panel under the row.
                    FrameLayout.LayoutParams(scope.dp(24), scope.dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                        .apply { bottomMargin = scope.dp(6) })
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
