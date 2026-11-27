package se.sensnology.spotnav.ui.yearreview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ha.yearreview.YearReviewAnswer
import se.sensnology.spotnav.ha.yearreview.YearReviewRead
import se.sensnology.spotnav.ha.yearreview.YearReviewSeen
import se.sensnology.spotnav.notify.AppIdentity
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.cardWords

/**
 * Whether the band and the row show, from the answer held and this phone's own note of what it has seen. Pure.
 *
 * Home Assistant keeps the phone's seen state too (by its `app_id`); the phone's own note keeps the band away at once,
 * and also where telling Home Assistant failed, so a closed band never comes back on this phone.
 */
internal object YearReviewOffer {
    fun banner(answer: YearReviewAnswer?, seenHere: Boolean): Boolean =
        answer != null && answer.banner && answer.review != null && !seenHere

    fun row(answer: YearReviewAnswer?): Boolean = answer != null && answer.row && answer.review != null

    /** An answer held this long is asked again when a page or Settings is drawn. */
    const val FRESH_MS = 30 * 60 * 1000L

    /** The note's key: one per Home Assistant door (charger or home), its site and year. */
    fun seenKey(door: String, answer: YearReviewAnswer): String = "$door|${answer.siteId}|${answer.year}"
}

/**
 * The year review of each Home Assistant door this phone talks to (a charger's webhook or a home's): read once and
 * kept while the app runs (asked again after [YearReviewOffer.FRESH_MS]), the bands and the Support row drawn from it,
 * and the story opened from either. An older Home Assistant (no `get_year_review`) or a review that is not ready has
 * no band and no row. Main thread only.
 */
internal object YearReviewHost {
    private class Held(val answer: YearReviewAnswer?, val readAt: Long)

    private val held = HashMap<String, Held>()
    /** When a read went out, per door: an answer dropped with its screen frees the door after [STUCK_MS]. */
    private val inFlight = HashMap<String, Long>()
    private const val STUCK_MS = 30_000L
    private val listeners = HashMap<String, MutableSet<() -> Unit>>()

    private const val PREFS = "spotnav_year_review"

    fun answer(door: String): YearReviewAnswer? = held[door]?.answer

    private fun seenHere(context: Context, door: String, answer: YearReviewAnswer): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(YearReviewOffer.seenKey(door, answer), false)

    fun bannerShown(context: Context, door: String): Boolean {
        val answer = answer(door) ?: return false
        return YearReviewOffer.banner(answer, seenHere(context, door, answer))
    }

    fun rowShown(door: String): Boolean = YearReviewOffer.row(answer(door))

    /** Read the review for [door] unless a fresh answer is held; every view of the door is told when it changes. */
    fun load(context: Context, session: HaSession, door: String) {
        val now = SystemClock.elapsedRealtime()
        val known = held[door]
        if (known != null && now - known.readAt < YearReviewOffer.FRESH_MS) return
        if (inFlight[door]?.let { now - it < STUCK_MS } == true) return
        inFlight[door] = now
        val appId = runCatching { AppIdentity.forContext(context.applicationContext).appId() }.getOrNull()
        session.yearReview(appId) { outcome ->
            inFlight.remove(door)
            when (outcome) {
                is YearReviewRead.Outcome.Read -> held[door] = Held(outcome.answer, SystemClock.elapsedRealtime())
                YearReviewRead.Outcome.None -> held[door] = Held(null, SystemClock.elapsedRealtime())
                // Offline: what was held stays, asked again next time.
                YearReviewRead.Outcome.Later -> return@yearReview
            }
            changed(door)
        }
    }

    private fun changed(door: String) {
        listeners[door]?.toList()?.forEach { it() }
    }

    fun listen(door: String, listener: () -> Unit) {
        listeners.getOrPut(door) { mutableSetOf() }.add(listener)
    }

    fun unlisten(door: String, listener: () -> Unit) {
        listeners[door]?.remove(listener)
    }

    /** This phone opened the story or closed the band: noted here at once and told to Home Assistant. */
    private fun seen(context: Context, session: HaSession, door: String, answer: YearReviewAnswer, seen: YearReviewSeen.Seen) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(YearReviewOffer.seenKey(door, answer), true).apply()
        runCatching { AppIdentity.forContext(context.applicationContext).appId() }.getOrNull()?.let { appId ->
            session.yearReviewSeen(appId, answer.year, seen)
        }
        changed(door)
    }

    /** Open the story of [door]'s review; the first opening while the band shows says it was viewed. */
    fun open(scope: ViewScope, session: HaSession, door: String) {
        val answer = answer(door) ?: return
        val review = answer.review ?: return
        if (bannerShown(scope.context, door)) seen(scope.context, session, door, answer, YearReviewSeen.Seen.VIEWED)
        YearReviewStory(scope, text(scope), review, supportReachable = answer.row).show()
    }

    fun dismiss(context: Context, session: HaSession, door: String) {
        val answer = answer(door) ?: return
        if (bannerShown(context, door)) seen(context, session, door, answer, YearReviewSeen.Seen.DISMISSED)
    }

    fun text(scope: ViewScope) = YearReviewText(
        scope.cardWords(),
        AppLanguageSettings.language(scope.context),
        AppLanguageSettings.locale(scope.context),
        AppLanguageSettings.numberLocale(scope.context)
    )

    /** The Support row's words ("Året med SpotNav 2026"), `null` while there is no row. */
    fun rowLabel(scope: ViewScope, door: String): String? {
        val answer = answer(door)?.takeIf { YearReviewOffer.row(it) } ?: return null
        return scope.cardWords().say("yearReview.row", "year" to answer.review!!.year.toString())
    }
}

/**
 * The band at the top of the house page and the charger page while the review's answer says `banner`: a tap opens
 * the story (and says it was viewed), its × closes it (dismissed); either way it never comes back on this phone.
 * Nothing here asks for support: the story's thanks page does, once.
 */
internal class YearReviewBand(private val scope: ViewScope, private val session: HaSession, private val door: String) {
    val view: LinearLayout = LinearLayout(scope.context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    private val listener: () -> Unit = { render() }

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                YearReviewHost.listen(door, listener)
                render()
                YearReviewHost.load(scope.context, session, door)
            }

            override fun onViewDetachedFromWindow(v: View) = YearReviewHost.unlisten(door, listener)
        })
    }

    private fun render() {
        view.removeAllViews()
        val answer = YearReviewHost.answer(door)
        if (answer?.review == null || !YearReviewHost.bannerShown(scope.context, door)) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val words = scope.cardWords()
        val dp = scope::dp
        val band = LinearLayout(scope.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(YearReviewColours.of(Tint.HOUSE, false), YearReviewColours.of(Tint.CAR, false))
            ).apply { cornerRadius = dp(14).toFloat() }
            elevation = dp(3).toFloat()
        }
        // The words are one button (it opens the story), the cross another.
        val opener = LinearLayout(scope.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(40)
            setOnClickListener { YearReviewHost.open(scope, session, door) }
        }
        opener.addView(ImageView(scope.context).apply {
            setImageResource(se.sensnology.spotnav.R.drawable.yr_art_cover)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        val title = words.say("yearReview.banner.title")
        val sub = words.say("yearReview.banner.sub", "year" to answer.review.year.toString())
        opener.addView(LinearLayout(scope.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(scope.context).apply {
                text = title
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(scope.context).apply {
                text = sub
                textSize = 13f
                setTextColor(Color.WHITE)
                alpha = 0.92f
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        opener.addView(TextView(scope.context).apply {
            text = "›"
            textSize = 22f
            setTextColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        opener.contentDescription = "$title. $sub"
        band.addView(opener, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        band.addView(TextView(scope.context).apply {
            text = "×"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            contentDescription = words.say("yearReview.banner.close")
            isClickable = true
            isFocusable = true
            setOnClickListener { YearReviewHost.dismiss(scope.context, session, door) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
        view.addView(band, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
    }
}

/**
 * Settings → Support: the row "Året med SpotNav 2026" that opens the story while the review's answer says `row`. The
 * card is drawn only while there is a row (the app's Support holds nothing else yet), so it is never empty.
 */
internal class YearReviewSupportCard(
    private val scope: ViewScope,
    parent: LinearLayout,
    private val session: HaSession,
    private val door: String
) {
    private val card = scope.card(parent, scope.cardWords().say("settings.section.support"), se.sensnology.spotnav.R.drawable.ic_help)
    private val row = TextView(scope.context).apply {
        textSize = 15f
        setTextColor(scope.accent)
        minHeight = scope.dp(44)
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(0, scope.dp(6), 0, scope.dp(6))
        setOnClickListener { YearReviewHost.open(scope, session, door) }
    }
    private val listener: () -> Unit = { render() }

    init {
        card.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                YearReviewHost.listen(door, listener)
                render()
                YearReviewHost.load(scope.context, session, door)
            }

            override fun onViewDetachedFromWindow(v: View) = YearReviewHost.unlisten(door, listener)
        })
        render()
    }

    private fun render() {
        val label = YearReviewHost.rowLabel(scope, door)
        card.body.visibility = if (label == null) View.GONE else View.VISIBLE
        row.text = label?.let { "$it  ›" }
        row.contentDescription = label
    }
}
