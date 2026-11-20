package se.sensnology.spotnav.ui.site

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import se.sensnology.spotnav.ha.house.HouseLoads
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.session.HaSession
import se.sensnology.spotnav.ui.common.ViewScope
import java.time.LocalDate

/** The pie's colours for meters (the card's validated categorical order), then Other metered and Unknown. */
internal object PieColours {
    private val METERS = longArrayOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF9085E9, 0xFF2AA3C4, 0xFF8AA02C).map { it.toInt() }
    private const val OTHER = 0xFFB9BDC4.toInt()
    private const val UNKNOWN = 0xFF747982.toInt()

    fun of(slot: Int): Int = when (slot) {
        LoadsModel.OTHER_COLOUR -> OTHER
        LoadsModel.UNKNOWN_COLOUR -> UNKNOWN
        else -> METERS[slot.coerceIn(0, METERS.size - 1)]
    }
}

/** The pie as a ring, the house's kWh in its middle, as the card draws it (`renderPie`). */
@SuppressLint("ViewConstructor")
internal class PieView(context: Context, private val colours: SiteColours) : View(context) {
    private var pie: LoadsModel.Pie? = null
    private var centre = ""
    private var label = ""
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15f; isFakeBoldText = true }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 12f }

    fun show(next: LoadsModel.Pie, house: String, name: String) {
        pie = next
        centre = house
        label = name
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val model = pie ?: return
        canvas.save()
        canvas.scale(density, density)
        val middle = (OUTER + INNER) / 2
        ring.strokeWidth = OUTER - INNER
        val box = RectF(SIZE / 2 - middle, SIZE / 2 - middle, SIZE / 2 + middle, SIZE / 2 + middle)
        val drawn = model.slices.filter { it.kwh > 0 }
        val total = drawn.sumOf { it.kwh }
        var start = -90f
        if (drawn.isEmpty()) {
            ring.color = PieColours.of(LoadsModel.UNKNOWN_COLOUR)
            ring.alpha = 90
            canvas.drawArc(box, 0f, 360f, false, ring)
            ring.alpha = 255
        }
        for (slice in drawn) {
            val sweep = if (total > 0) (slice.kwh / total * 360).toFloat() else 0f
            ring.color = PieColours.of(slice.colour)
            canvas.drawArc(box, start, if (drawn.size == 1) 360f else sweep, false, ring)
            start += sweep
        }
        if (drawn.size > 1) {
            // The card's thin gaps between the slices, in the dialog's own colour.
            ring.color = colours.card
            ring.strokeWidth = 2f
            var angle = -90.0
            for (slice in drawn) {
                val rad = Math.toRadians(angle)
                canvas.drawLine(
                    (SIZE / 2 + INNER * Math.cos(rad)).toFloat(), (SIZE / 2 + INNER * Math.sin(rad)).toFloat(),
                    (SIZE / 2 + OUTER * Math.cos(rad)).toFloat(), (SIZE / 2 + OUTER * Math.sin(rad)).toFloat(), ring
                )
                angle += slice.kwh / total * 360
            }
        }
        value.color = colours.text
        caption.color = colours.muted
        canvas.drawText(centre, SIZE / 2f, SIZE / 2f + 5, value)
        canvas.drawText(label, SIZE / 2f, SIZE / 2f + 19, caption)
        canvas.restore()
    }

    private companion object {
        const val SIZE = 148f
        const val OUTER = 72f
        const val INNER = 44f
    }
}

/**
 * What the house (or the heat) used over the period the flow shows, as the card's popover has it
 * (`frontend/src/house-loads.ts`): the caption, the pie and a list of every slice (name, kWh, share), and the notes.
 * Read when opened; no meter is moved from here (that is set in Home Assistant).
 */
internal class LoadsDialog(
    private val scope: ViewScope,
    private val session: HaSession,
    private val text: SiteText,
    private val colours: SiteColours
) {
    private val words = text.words
    private var dialog: AlertDialog? = null

    fun open(kind: String, choice: PeriodChoice, today: LocalDate, running: Boolean, carApart: Boolean, heatApart: Boolean) {
        val body = LinearLayout(scope.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(scope.dp(20), scope.dp(8), scope.dp(20), scope.dp(4))
        }
        fun say(sentence: String) {
            body.removeAllViews()
            body.addView(note(sentence))
        }
        say(words.say("house.loads.reading"))
        dialog?.dismiss()
        dialog = AlertDialog.Builder(scope.context)
            .setTitle(words.say(if (kind == "heat") "house.loads.titleHeat" else "house.loads.title"))
            .setView(ScrollView(scope.context).apply { addView(body) })
            .setPositiveButton(scope.t(android.R.string.ok), null)
            .create().also { it.show() }
        session.houseLoads(kind, choice.period.key, choice.date) { outcome ->
            if (dialog?.isShowing != true) return@houseLoads
            when (outcome) {
                is SiteRead.Read -> fill(body, outcome.value, choice, running, carApart, heatApart)
                SiteRead.Unsupported -> say(words.say("house.loads.unsupported"))
                is SiteRead.Failed -> say(words.say(if (outcome.code == "unknown_command" || outcome.code == "spotnav_unsupported_api_version") "house.loads.unsupported" else "house.loads.failed"))
            }
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun fill(body: LinearLayout, answer: HouseLoads, choice: PeriodChoice, running: Boolean, carApart: Boolean, heatApart: Boolean) {
        body.removeAllViews()
        val house = answer.houseKwh
        if (house == null) {
            body.addView(note(words.say("house.loads.noFlow")))
            return
        }
        body.addView(TextView(scope.context).apply {
            textSize = 14f
            setTextColor(colours.text)
            text = caption(answer, running)
        })
        val pie = LoadsModel.pie(words, answer)
        val row = LinearLayout(scope.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        row.addView(PieView(scope.context, colours).apply {
            show(pie, LoadsModel.kwh(text, house), words.say(if (answer.kind == "heat") "house.flow.heat" else "house.flow.house"))
            contentDescription = words.say(
                "house.loads.chart",
                "list" to pie.slices.joinToString(", ") { "${it.name} ${LoadsModel.kwh(text, it.kwh)} (${LoadsModel.share(text, it.share)})" }
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(8); bottomMargin = scope.dp(8) })
        for (slice in pie.slices) row.addView(sliceRow(slice), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(row)
        for (line in LoadsModel.notes(text, answer, pie, carApart, heatApart)) {
            body.addView(note(line), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = scope.dp(8) })
        }
    }

    private fun caption(answer: HouseLoads, running: Boolean): String = when (answer.period) {
        "day" -> if (running) words.say("house.loads.today") else Periods.optionLabel(text.locale, HistoryPeriod.DAY, answer.date)
        "year" -> answer.date.year.toString()
        else -> Periods.optionLabel(text.locale, HistoryPeriod.MONTH, answer.date)
    }

    private fun sliceRow(slice: LoadsModel.Slice) = LinearLayout(scope.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, scope.dp(3), 0, scope.dp(3))
        addView(View(context).apply {
            background = GradientDrawable().apply { setColor(PieColours.of(slice.colour)); cornerRadius = scope.dp(3).toFloat() }
        }, LinearLayout.LayoutParams(scope.dp(10), scope.dp(10)).apply { marginEnd = scope.dp(8) })
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(colours.text)
            this.text = slice.name
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            textSize = 14f
            setTextColor(colours.text)
            this.text = "${LoadsModel.kwh(this@LoadsDialog.text, slice.kwh)} · ${LoadsModel.share(this@LoadsDialog.text, slice.share)}"
        })
    }

    private fun note(sentence: String) = TextView(scope.context).apply {
        textSize = 13f
        setTextColor(colours.muted)
        text = sentence
    }
}
