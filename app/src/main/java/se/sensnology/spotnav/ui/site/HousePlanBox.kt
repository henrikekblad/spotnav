package se.sensnology.spotnav.ui.site

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.client.DoesNotFitChoice
import se.sensnology.spotnav.ha.client.ReleaseImportTarget
import se.sensnology.spotnav.ha.house.Plan
import se.sensnology.spotnav.ha.house.PlanRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardHeading
import se.sensnology.spotnav.ui.common.cardTitle

/** What the Plan box shows: nothing (not served, or not known yet), a line while it reads or after it failed, or the plan. */
internal sealed interface PlanBoxState {
    data object Hidden : PlanBoxState

    /** [key]: the card's words for the line ("house.loading", "house.failed", "hist.unsupported"). */
    data class Line(val key: String) : PlanBoxState

    data class Shown(val plan: Plan) : PlanBoxState

    companion object {
        /**
         * The box's state: hidden where the webhook does not serve `get_plan` ([offered] false: `get_site` said so; or
         * [refused]: an older Home Assistant answered "Unsupported action" since), and while that is not known yet with
         * nothing read, so an older Home Assistant never shows an empty box. A plan held stays shown while the next read
         * is on its way or fails.
         */
        fun of(offered: Boolean?, refused: Boolean, plan: Plan?, failed: Boolean, unsupported: Boolean): PlanBoxState = when {
            offered == false || refused -> Hidden
            plan != null -> Shown(plan)
            unsupported -> Line("hist.unsupported")
            offered == null -> Hidden
            failed -> Line("house.failed")
            else -> Line("house.loading")
        }
    }
}

/**
 * The house page's Plan box, under Now as the card's Plan tab follows Now (`frontend/src/house-plan.ts`; see
 * [HousePlanModel] for what it says). It reads `get_plan` when the page opens and then with the house, about once a
 * minute. Not a period box: the shared picker does not drive it. A car's "does not fit" line is said in its lane with
 * the card's buttons: "Take what fits", "Later departure (14:00)" where a later hour fits (without one the departure's
 * editor is the charger page's) and "Allow the peak", asked again on the button before it is sent, as the card's Plan
 * tab asks. [choiceScope] says where a lane's choices go ([PlanChoiceScope]).
 */
internal class HousePlanBox(
    scope: ViewScope,
    private val session: HaSession,
    private val memory: SiteMemory,
    private val text: SiteText,
    colours: SiteColours,
    private val choiceScope: () -> PlanChoiceScope
) : ViewScope(scope) {
    private val planPalette = PlanPalette(colours)
    private var inFlight = false
    private var failed = false
    private var unsupported = false
    /** The page came into view since the last read: a webhook that refused `get_plan` is asked again. */
    private var cameIntoView = true

    private val title = cardTitle("")
    private val status = muted(14f)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val panel: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(cardBackground)
            cornerRadius = dp(10).toFloat()
        }
        visibility = View.GONE
        addView(cardHeading(R.drawable.ic_tab_plan, title), wide())
        addView(status, wide().apply { topMargin = dp(8) })
        addView(content, wide())
    }

    /** The page came into view again: the next read asks a webhook that refused `get_plan` again. */
    fun cameIntoView() {
        cameIntoView = true
    }

    /** Read the plan, unless the webhook does not serve it ([PlanProbe] says when one that refused is asked again) or a read is on its way. */
    fun read() {
        if (inFlight) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!PlanProbe.ask(memory.planOffered, memory.planRefusedAt, now, cameIntoView)) return
        cameIntoView = false
        inFlight = true
        session.plan { outcome ->
            inFlight = false
            when (outcome) {
                is SiteRead.Read -> {
                    memory.plan = outcome.value
                    memory.planRefusedAt = null
                    failed = false
                    unsupported = false
                }
                // An older Home Assistant: no Plan box until a later ask (PlanProbe) finds the plan served.
                is SiteRead.Failed -> if (outcome.code == PlanRead.UNSUPPORTED_ACTION) {
                    memory.planRefusedAt = android.os.SystemClock.elapsedRealtime()
                    memory.plan = null
                } else failed = true
                SiteRead.Unsupported -> unsupported = true
            }
            render()
        }
    }

    /** Draw the box; `false` when it is left out. */
    fun render(): Boolean {
        val state = PlanBoxState.of(memory.planOffered, memory.planRefusedAt != null, memory.plan, failed, unsupported)
        panel.visibility = if (state == PlanBoxState.Hidden) View.GONE else View.VISIBLE
        when (state) {
            PlanBoxState.Hidden -> return false
            is PlanBoxState.Line -> {
                title.text = text.words.say("house.tab.plan")
                status.text = text.words.say(state.key)
                status.visibility = View.VISIBLE
                content.removeAllViews()
            }
            is PlanBoxState.Shown -> {
                status.visibility = View.GONE
                draw(state.plan)
            }
        }
        return true
    }

    private fun draw(plan: Plan) {
        val shown = HousePlanModel.content(text, plan)
        title.text = shown.title
        content.removeAllViews()
        content.addView(muted(13f, shown.lede), wide().apply { topMargin = dp(6) })
        content.addView(stats(shown.stats), wide().apply { topMargin = dp(10) })
        shown.noPrices?.let { content.addView(muted(13f, it), wide().apply { topMargin = dp(6) }) }
        content.addView(subheading(shown.fromGrid), wide().apply { topMargin = dp(12) })
        content.addView(HousePlanChartView(context, planPalette).apply { chart = shown.chart }, wide().apply { topMargin = dp(4) })
        content.addView(legend(shown.legend), wide().apply { topMargin = dp(4) })
        shown.wouldNote?.let { content.addView(muted(13f, it), wide().apply { topMargin = dp(6) }) }
        shown.perLoad?.let { heading ->
            content.addView(subheading(heading), wide().apply { topMargin = dp(12) })
            for (lane in shown.lanes) content.addView(lane(lane, plan.hours.size), wide().apply { topMargin = dp(6) })
            shown.rolling?.let { content.addView(muted(12f, it).apply { setPadding(dp(LANE_NAME_DP + 8), 0, 0, 0) }, wide().apply { topMargin = dp(4) }) }
        }
        content.addView(subheading(shown.inWords), wide().apply { topMargin = dp(12) })
        shown.nothing?.let { content.addView(muted(13f, it), wide().apply { topMargin = dp(4) }) }
        for (said in shown.words) content.addView(said(said, shown.wouldTag), wide().apply { topMargin = dp(4) })
    }

    private fun stats(stats: List<HousePlanModel.Stat>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for (stat in stats) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(8), 0)
                addView(muted(12.5f, stat.label))
                addView(TextView(context).apply {
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(
                        when (stat.over) {
                            true -> PlanPalette.CEILING
                            false -> OK
                            null -> dark
                        }
                    )
                    text = stat.value
                })
                addView(muted(12f, stat.note))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun legend(items: List<Pair<PlanLayer?, String>>): View {
        val row = WrapRow(context, dp(12), dp(4))
        for ((layer, word) in items) {
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(layer?.let(planPalette::of) ?: dark)
                        cornerRadius = dp(3).toFloat()
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(10), if (layer == null) dp(3) else dp(10)).apply { marginEnd = dp(5) })
                addView(TextView(context).apply {
                    textSize = 12.5f
                    setTextColor(dark)
                    text = word
                })
            })
        }
        return row
    }

    private fun lane(lane: HousePlanModel.Lane, hours: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply {
            textSize = 13.5f
            setTextColor(if (lane.would) muted else dark)
            text = lane.name
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(LANE_NAME_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(PlanLaneTrackView(context, planPalette, planPalette.grid).apply {
                this.hours = hours
                this.lane = lane
                contentDescription = "${lane.name}: ${lane.reason}"
            }, wide())
            addView(muted(12.5f, lane.reason), wide().apply { topMargin = dp(2) })
            lane.doesNotFit?.let { addView(TextView(context).apply { textSize = 13f; setTextColor(dark); text = it }, wide().apply { topMargin = dp(2) }) }
            choices(lane)?.let { addView(it, wide()) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** A car lane's buttons, one per choice still open, where its charger can be reached ([PlanChoiceScope]); else `null`. */
    private fun choices(lane: HousePlanModel.Lane): View? {
        if (lane.choices.isEmpty()) return null
        val target = PlanChoiceScope.target(choiceScope(), lane.id) ?: return null
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (choice in lane.choices) {
            val button = android.widget.Button(context).apply {
                isAllCaps = false
                text = choice.label
                tag = choice.code
            }
            // "Allow the peak" asks again on the button ("Ja, tillåt toppen"), as the card's Plan tab: it has no undo.
            var asked = choice.press != DoesNotFitText.Press.ACCEPT_PEAK
            button.setOnClickListener {
                if (!asked) {
                    asked = true
                    button.text = lane.acceptPeakAgain
                    return@setOnClickListener
                }
                for (index in 0 until column.childCount) column.getChildAt(index).isEnabled = false
                choose(choice.press, target)
            }
            column.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return column
    }

    /** Send a lane's choice for its charger, then read the plan again whatever came of it; a real refusal is said once. */
    private fun choose(press: DoesNotFitText.Press, target: PlanChoiceScope.Target) {
        fun done(failed: Boolean) {
            if (failed) android.widget.Toast.makeText(context, t(R.string.does_not_fit_failed), android.widget.Toast.LENGTH_LONG).show()
            read()
            render()
        }
        when (press) {
            DoesNotFitText.Press.TAKE_WHAT_FITS -> session.doesNotFitChoice(DoesNotFitChoice.TAKE_WHAT_FITS, target.chargerId) {
                done(it is DoesNotFitChoice.Outcome.Failed)
            }
            DoesNotFitText.Press.LATER_DEPARTURE -> session.doesNotFitChoice(DoesNotFitChoice.LATER_DEPARTURE, target.chargerId) {
                done(it is DoesNotFitChoice.Outcome.Failed)
            }
            DoesNotFitText.Press.ACCEPT_PEAK -> session.releaseImportTarget(target.chargerId) { done(it is ReleaseImportTarget.Outcome.Failed) }
            DoesNotFitText.Press.OPEN_DEPARTURE -> Unit
        }
    }

    private fun said(said: HousePlanModel.Said, wouldTag: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(muted(13.5f, said.span), LinearLayout.LayoutParams(dp(LANE_NAME_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        val sentence = SpannableStringBuilder(said.sentence)
        if (said.would) {
            val start = sentence.length + 1
            sentence.append(" · ").append(wouldTag)
            sentence.setSpan(ForegroundColorSpan(muted), start - 1, sentence.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        addView(TextView(context).apply {
            textSize = 13.5f
            setTextColor(dark)
            text = sentence
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun subheading(words: String) = TextView(context).apply {
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(dark)
        text = words
    }

    private fun muted(size: Float, words: String = "") = TextView(context).apply {
        textSize = size
        setTextColor(muted)
        text = words
    }

    private fun wide() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private companion object {
        const val LANE_NAME_DP = 84
        /** Under the ceiling: the card's `--success-color` fallback. */
        const val OK = 0xFF43A047.toInt()
    }
}
