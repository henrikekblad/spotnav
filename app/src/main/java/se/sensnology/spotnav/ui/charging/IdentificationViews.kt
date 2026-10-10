package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.vehicles.PairedCarLine
import se.sensnology.spotnav.notify.IdentifyNotice
import se.sensnology.spotnav.notify.InAppQuestion
import se.sensnology.spotnav.vehicles.VehicleIdentification

/**
 * The car on a paired charger's card: the car line (the car, its levels, how it was decided, and Byt
 * bil for every user where more than one car can charge), and under it the banner with the open
 * question (one button per car, the first tap is the answer). Both send the chosen car through
 * [onChoose].
 */
internal class IdentificationViews(scope: ViewScope, parent: LinearLayout) : ViewScope(scope) {
    private var dashboard: Dashboard? = null
    private var onChoose: (String) -> Unit = {}
    private var busy = false

    private val banner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(14), dp(10), dp(12), dp(10))
        background = bannerBackground()
    }

    private val carLine = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(2))
    }
    private val carText = TextView(context).apply { textSize = 15f; setTextColor(muted) }

    // How the car was decided, on a line of its own under the car, muted: the same look in every
    // language and state.
    private val methodLine = TextView(context).apply {
        textSize = 13f; setTextColor(muted); setPadding(dp(26), 0, 0, dp(6)); visibility = View.GONE
    }
    private val carBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val switchAction = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setPadding(dp(8), dp(6), 0, dp(6))
        contentDescription = t(R.string.identify_switch)
        // Byt bil reads as the screen's other tappable values do: accent, the same size and weight.
        addView(glyph(R.drawable.ic_swap, accent, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
        addView(TextView(context).apply {
            text = t(R.string.identify_switch); textSize = 16f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD
        })
        setOnClickListener { openSwitch() }
    }

    init {
        carLine.addView(glyph(R.drawable.ic_ev, muted, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        carLine.addView(carText, weight())
        carLine.addView(switchAction)
        carBlock.addView(carLine)
        carBlock.addView(methodLine)
        // The car first, then the open question about it.
        parent.addView(carBlock)
        parent.addView(banner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2); bottomMargin = dp(8) })
    }

    /** Set by the screen: the car a person chose, to send as the answer (or correction). */
    fun attachChoose(handler: (String) -> Unit) {
        onChoose = handler
    }

    /** Whether an answer is on its way: the buttons wait for it. */
    fun setBusy(inFlight: Boolean) {
        busy = inFlight
        paint()
    }

    /** Draw from [held] (`null`: a failed read, which shows neither). */
    fun show(held: Dashboard?) {
        dashboard = held
        paint()
    }

    private fun paint() {
        val held = dashboard
        val question = held?.let { VehicleIdentification.banner(it) }
        // The question in view: the background check does not post it as a notification too.
        InAppQuestion.banner = if (question != null) held.identification?.let(IdentifyNotice::key) else null
        val line = held?.let { VehicleIdentification.carLine(it) }
        paintBanner(question)
        carBlock.visibility = if (line != null) View.VISIBLE else View.GONE
        if (line != null && held != null) carText.text = carLineText(held, line)
        val method = line?.basis?.let { t(basisText(it)) }
        methodLine.text = method.orEmpty()
        methodLine.visibility = if (method == null) View.GONE else View.VISIBLE
        // One car at the charger: nothing to change.
        switchAction.visibility = if (line?.canSwitch == true) View.VISIBLE else View.GONE
        switchAction.isEnabled = !busy
        switchAction.alpha = if (busy) 0.5f else 1f
    }

    private fun paintBanner(question: VehicleIdentification.Banner?) {
        banner.removeAllViews()
        banner.visibility = if (question != null) View.VISIBLE else View.GONE
        question ?: return
        banner.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(glyph(R.drawable.ic_help, accent, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(TextView(context).apply {
                text = t(R.string.identify_question); textSize = 16f; setTextColor(dark); typeface = Typeface.DEFAULT_BOLD
            }, weight())
        })
        val note = listOfNotNull(
            t(R.string.identify_undecided).takeIf { question.carsCouldNotTell },
            question.keptName?.let { t(R.string.identify_keeps, it) }
        ).joinToString(" ")
        if (note.isNotEmpty()) {
            banner.addView(TextView(context).apply { text = note; textSize = 13f; setTextColor(muted); setPadding(0, dp(4), 0, dp(4)) })
        }
        // One button per car, two to a row, in the order Home Assistant gives them.
        question.choices.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, choice ->
                row.addView(Button(context).apply {
                    text = choice.name
                    isAllCaps = false
                    isEnabled = !busy
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ev, 0, 0, 0)
                    compoundDrawablePadding = dp(8)
                    compoundDrawableTintList = ColorStateList.valueOf(accent)
                    setOnClickListener { if (!busy) onChoose(choice.vehicleId) }
                }, weight().apply { if (index == 1) marginStart = dp(8) })
            }
            if (pair.size == 1) row.addView(View(context), weight().apply { marginStart = dp(8) })
            banner.addView(row)
        }
        banner.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(glyph(R.drawable.ic_bell, muted, 14), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
            addView(TextView(context).apply { text = t(R.string.identify_answer_where); textSize = 12f; setTextColor(muted) })
        })
    }

    /**
     * "EV6 · 89 % av 93 % mål · identifierad via bilens laddkabel": the name in bold, its levels (see
     * [PairedCarLine]), then how it was decided.
     */
    private fun carLineText(held: Dashboard, line: VehicleIdentification.CarLine): CharSequence {
        // No car planned for yet: the line says so, and Byt bil beside it chooses one.
        val id = line.vehicleId ?: return t(R.string.vehicle_none)
        val text = SpannableStringBuilder(line.name ?: id)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(dark), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        PairedCarLine.levelsText(PairedCarLine.levels(held, id), ::percent, template(R.string.car_line_of_target))?.let { levels ->
            val start = text.length + 3
            text.append(" · ").append(levels)
            text.setSpan(ForegroundColorSpan(dark), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    /** A whole percent as the cards write one ("89 %"). */
    private fun percent(value: Int): String = t(R.string.vehicle_card_soc_value, value)

    private fun basisText(basis: VehicleIdentification.Basis): Int = when (basis) {
        VehicleIdentification.Basis.PLUG_SENSOR -> R.string.identify_by_plug_sensor
        VehicleIdentification.Basis.LOCATION -> R.string.identify_by_location
        VehicleIdentification.Basis.CHOSEN_MANUALLY -> R.string.identify_by_hand
        VehicleIdentification.Basis.ASSUMED -> R.string.identify_by_assumed
        VehicleIdentification.Basis.IDENTIFYING -> R.string.identify_identifying
        VehicleIdentification.Basis.CAMERA -> R.string.identify_by_camera
    }

    private fun hintText(hint: VehicleIdentification.Hint): Int = when (hint) {
        VehicleIdentification.Hint.PLUGGED_IN -> R.string.identify_hint_plugged_in
        VehicleIdentification.Hint.NOT_PLUGGED_IN -> R.string.identify_hint_not_plugged_in
        VehicleIdentification.Hint.AWAY -> R.string.identify_hint_away
        VehicleIdentification.Hint.ELSEWHERE -> R.string.identify_hint_elsewhere
    }

    /** Byt bil: a radio per car with what its own report says, the current car chosen; Välj sends it. */
    private fun openSwitch() {
        val held = dashboard ?: return
        if (busy) return
        val switch = VehicleIdentification.switchChoices(held)
        if (switch.choices.isEmpty()) return
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
        }
        val charger = held.chargerName ?: t(R.string.widget_charger_title)
        body.addView(TextView(context).apply {
            text = t(R.string.identify_switch_prompt, charger); textSize = 14f; setTextColor(muted); setPadding(0, 0, 0, dp(6))
        })
        val group = RadioGroup(context).apply { orientation = LinearLayout.VERTICAL }
        val radios = switch.choices.map { choice ->
            RadioButton(context).apply {
                id = View.generateViewId()
                text = choice.name
                textSize = 16f
                isChecked = choice.vehicleId == switch.current
            }.also { radio ->
                group.addView(radio)
                choice.hint?.let { hint ->
                    group.addView(TextView(context).apply {
                        text = t(hintText(hint)); textSize = 13f; setTextColor(muted); setPadding(dp(32), 0, 0, dp(4))
                    })
                }
            }
        }
        body.addView(group)
        AlertDialog.Builder(context)
            .setTitle(t(R.string.identify_switch))
            .setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(t(android.R.string.cancel), null)
            .setPositiveButton(t(R.string.identify_choose)) { _, _ ->
                val chosen = switch.choices.getOrNull(radios.indexOfFirst { it.isChecked })?.vehicleId
                // Choosing the car already chosen still counts: it is the person's answer.
                if (chosen != null) onChoose(chosen)
            }
            .show()
    }

    private fun glyph(icon: Int, colour: Int, size: Int) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(colour)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        minimumWidth = dp(size); minimumHeight = dp(size)
    }

    /** A faint accent panel with the accent stripe down its left edge, as in the approved look. */
    private fun bannerBackground(): LayerDrawable {
        val panel = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor((accent and 0x00FFFFFF) or PANEL_ALPHA)
        }
        val stripe = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(accent)
        }
        return LayerDrawable(arrayOf(panel, stripe)).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerWidth(1, dp(3))
        }
    }

    private companion object {
        const val PANEL_ALPHA = 0x1F000000
    }
}
