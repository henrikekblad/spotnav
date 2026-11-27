package se.sensnology.spotnav.ui.site

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import se.sensnology.spotnav.ui.common.Energy

/**
 * A pill around its words, outlined in their colour (the card's headline pills): the words in [colour], a little
 * bolder, with room each side. It never breaks inside; a line too narrow wraps before it.
 */
internal class PillSpan(private val colour: Int, private val density: Float) : ReplacementSpan() {
    private val pad get() = 9f * density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val extra = (3f * density).toInt()
            fm.ascent -= extra; fm.top -= extra; fm.descent += extra; fm.bottom += extra
        }
        return (paint.measureText(text, start, end) * BOLDER + 2 * pad).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val width = paint.measureText(text, start, end) * BOLDER + 2 * pad
        val metrics = paint.fontMetrics
        val half = density
        outline.strokeWidth = density
        outline.color = colour
        val box = RectF(x + half, y + metrics.ascent - 2f * density, x + width - half, y + metrics.descent + 2f * density)
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, outline)
        val before = paint.color
        val fake = paint.isFakeBoldText
        paint.color = colour
        paint.isFakeBoldText = true
        canvas.drawText(text, start, end, x + pad, y.toFloat(), paint)
        paint.color = before
        paint.isFakeBoldText = fake
    }

    private companion object {
        /** The bolder words' width against the plain ones. */
        const val BOLDER = 1.06f
    }
}

/** A small rounded square in a part's colour before its name (the ring's legend). */
internal class SwatchSpan(private val colour: Int, private val density: Float) : ReplacementSpan() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = (15f * density).toInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        fill.color = colour
        val size = 10f * density
        val box = RectF(x, y - size + density, x + size, y + density)
        canvas.drawRoundRect(box, 3f * density, 3f * density, fill)
    }
}

/** The Now box's headline and legend as text with their pills and swatches, wrapping as the box's width allows. */
internal object NowText {
    /** The headline's pills on one wrapping line: the own share, then the price now. */
    fun pills(headline: Headline, colours: SiteColours, density: Float): CharSequence? {
        val out = SpannableStringBuilder()
        fun pill(words: String, colour: Int) {
            if (out.isNotEmpty()) out.append("  ")
            val start = out.length
            out.append(words)
            out.setSpan(PillSpan(colour, density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (headline.own != null) {
            pill(
                headline.own,
                when (headline.ownTone) {
                    OwnTone.HIGH -> mixed(colours, Energy.EXPORT)
                    OwnTone.MID -> mixed(colours, Energy.MID)
                    else -> colours.muted
                }
            )
        }
        if (headline.price != null) {
            pill(
                headline.price,
                mixed(
                    colours,
                    when (headline.band) {
                        PriceBand.CHEAP -> Energy.CHEAP
                        PriceBand.DEAR -> Energy.EXPENSIVE
                        else -> Energy.MID
                    }
                )
            )
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The ring's legend: each part with a swatch in its colour and its figure. */
    fun legend(items: List<Pair<PartRole, String>>, colours: SiteColours, density: Float): CharSequence? {
        if (items.isEmpty()) return null
        val out = SpannableStringBuilder()
        for ((role, words) in items) {
            if (out.isNotEmpty()) out.append("    ")
            val start = out.length
            out.append("■")
            out.setSpan(SwatchSpan(colours.of(HouseNowModel.tone(role)), density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // A part's name and figure never break apart.
            out.append(words.replace(' ', ' '))
        }
        return out
    }

    /** Words in a meaning's colour, a third toward the text colour, so they read on a light and a dark screen. */
    private fun mixed(colours: SiteColours, meaning: Energy): Int = SiteColours.mix(colours.of(meaning), colours.text, 0.68f)
}
