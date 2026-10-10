package se.sensnology.spotnav.ui.charging

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueRow
import se.sensnology.spotnav.vehicles.ChargerCarFace
import se.sensnology.spotnav.notify.IdentifyNotice
import se.sensnology.spotnav.notify.InAppQuestion
import se.sensnology.spotnav.vehicles.VehicleIdentification

/**
 * The car on a paired charger's card (see [ChargerCarFace]): where more than one car can charge, a
 * full-width button right over Start and Pause, with the open question's banner just above it (one
 * button per car, the first tap is the answer); where there is one car, a value row under the charge
 * history that opens the car's settings. The banner, the button and the chooser behind it send the
 * chosen car through [onChoose].
 */
internal class IdentificationViews(scope: ViewScope, parent: LinearLayout) : ViewScope(scope) {
    private var dashboard: Dashboard? = null
    private var onChoose: (String) -> Unit = {}
    private var onOpenCar: (String) -> Unit = {}
    private var busy = false
    private var rowCar: String? = null

    private val banner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(14), dp(10), dp(12), dp(10))
        background = bannerBackground()
    }

    // One car: "Bil · EV6" and its levels, in the card's value-row look; the value opens its settings.
    private val rowValue = valueLabel()
    private val row = valueRow(parent, t(R.string.car_caption), rowValue) { rowCar?.let { onOpenCar(it) } }

    // Several cars: the button, in the Start and Pause cells' family.
    private val caption = TextView(context).apply {
        textSize = 12f; gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val mainText = TextView(context).apply {
        textSize = 16f; setTextColor(dark); gravity = Gravity.CENTER; maxLines = 2
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val button = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        minimumHeight = dp(55)
        visibility = View.GONE
        setOnClickListener { openSwitch() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(8))
            addView(caption)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(glyph(R.drawable.ic_ev, accent, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                addView(mainText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    init {
        row.view.visibility = View.GONE
        // The open question, then the car it asks about, then Start and Pause (attached after).
        parent.addView(banner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        parent.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    /** Set by the screen: the car a person chose, to send as the answer (or correction). */
    fun attachChoose(handler: (String) -> Unit) {
        onChoose = handler
    }

    /** Set by the screen: open this car's settings. */
    fun attachOpenCar(handler: (String) -> Unit) {
        onOpenCar = handler
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
        paintBanner(question)
        val face = held?.let { ChargerCarFace.of(it, ::percent, template(R.string.car_line_of_target)) }
        paintRow(face?.takeIf { it.place == ChargerCarFace.Place.ROW })
        paintButton(face?.takeIf { it.place == ChargerCarFace.Place.BUTTON })
    }

    private fun words() = ChargerCarFace.Words(
        car = t(R.string.car_caption), carWith = template(R.string.car_with), choose = t(R.string.car_caption_choose)
    ) { t(basisText(it)) }

    /** One car: its name in the label, its levels as the value; the value opens the car's settings. */
    private fun paintRow(face: ChargerCarFace.Face?) {
        row.view.visibility = if (face != null) View.VISIBLE else View.GONE
        face ?: return
        rowCar = face.vehicleId.takeIf { face.tap == ChargerCarFace.Tap.CAR_SETTINGS }
        row.label?.text = ChargerCarFace.rowLabel(face, words())
        rowValue.text = face.levels ?: "–"
        row.show(ValueCue.of(editable = rowCar != null))
    }

    /** Several cars: how it was decided over the car and its levels; marked while the question is open. */
    private fun paintButton(face: ChargerCarFace.Face?) {
        button.visibility = if (face != null) View.VISIBLE else View.GONE
        face ?: return
        val words = words()
        caption.text = ChargerCarFace.caption(face, words)
        caption.setTextColor(if (face.highlighted) accent else muted)
        mainText.text = buttonText(face)
        button.background = buttonBackground(face.highlighted)
        button.contentDescription = "${caption.text}. ${mainText.text}. ${t(R.string.identify_switch)}"
        button.isEnabled = !busy
        button.alpha = if (busy) 0.5f else 1f
    }

    /** "EV6 · 72 % av 95 % mål": the name in bold; "Ingen bil vald" when no car is known yet. */
    private fun buttonText(face: ChargerCarFace.Face): CharSequence {
        val name = face.name ?: return t(R.string.vehicle_none)
        val text = SpannableStringBuilder(name)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        face.levels?.let { text.append(" · ").append(it) }
        return text
    }

    /** The cells' outline and corners; the question's fill and a 2 dp accent outline while it is open. */
    private fun buttonBackground(highlighted: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (highlighted) {
                setColor((accent and 0x00FFFFFF) or PANEL_ALPHA)
                setStroke(dp(2), accent)
            } else {
                setColor(appBackground)
                setStroke(dp(1), (muted and 0x00FFFFFF) or OUTLINE_ALPHA)
            }
        }
        return RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or RIPPLE_ALPHA), shape, null)
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

    /** The chooser: a radio per car with what its own report says, the current car chosen; Välj sends it. */
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
        const val OUTLINE_ALPHA = 0x66000000
        const val RIPPLE_ALPHA = 0x33000000
    }
}
