package se.sensnology.spotnav.ui.yearreview

import se.sensnology.spotnav.ha.yearreview.YearReview
import se.sensnology.spotnav.ha.yearreview.YearReviewPage

/**
 * The year review's picture to share (1080 × 1350), its layout alone: pure, so the tests read it without a canvas. A
 * port of the card's `year-review-share.ts`: "My 2026 with SpotNav" over up to four tiles (saved, the car's cheap
 * share, the sun's kWh, the battery: its money, else what it saved at each hour's price, else the kWh it gave back;
 * the first four present), laid out without a hole, the SpotNav logo top right. Only money, kWh, % and
 * "since {month}": never a name, an address or a time.
 */
internal object YearReviewShareLayout {
    const val WIDTH = 1080
    const val HEIGHT = 1350
    private const val TILE_W = 440
    private const val TILE_H = 360
    private const val TILE_GAP = 40
    private val TILE_X = intArrayOf(80, 560)
    private const val TILE_TOP = 400
    /** A tile across both columns. */
    private const val WIDE_W = TILE_W * 2 + TILE_GAP
    const val VALUE_MAX_PX = 84
    const val VALUE_MIN_PX = 48
    private const val LABEL_PX = 32

    /** The logo in the top right corner: its top level with the "SpotNav" line, its right edge at the left margin's distance. */
    const val LOGO_SIZE = 160
    const val LOGO_X = WIDTH - 80 - LOGO_SIZE
    const val LOGO_Y = 76

    data class Tile(val art: String, val label: String, val value: String, val tint: Tint)

    data class Box(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val artX: Int, val artY: Int, val artSize: Int,
        val labelX: Int, val labelY: Int, val labelMaxWidth: Int, val labelLineHeight: Int, val labelPx: Int,
        /** [valuePx]: the size the figure starts at (made smaller to fit [valueMaxWidth]). */
        val valueX: Int, val valueY: Int, val valueMaxWidth: Int, val valuePx: Int
    )

    /** The picture's tiles: what was saved, the car's cheap share, the sun's kWh, the battery (first four present). */
    fun tiles(text: YearReviewText, review: YearReview): List<Tile> {
        val tiles = mutableListOf<Tile>()
        fun approx(estimated: Boolean, value: String) = if (estimated) "≈ $value" else value
        for (page in review.pages) when (page) {
            is YearReviewPage.Saved -> tiles += Tile("saved", text.say("yearReview.share.saved"), approx(page.estimated, text.money(page.total, review.money)), Tint.EXPORT)
            is YearReviewPage.Car -> page.cheapSharePct?.let {
                tiles += Tile("car", text.say("yearReview.share.car"), approx(page.estimated, "${text.number(it, 0)} %"), Tint.CAR)
            }
            is YearReviewPage.Sun -> tiles += Tile("sun", text.say("yearReview.share.sun"), "${text.number(page.kwh, 0)} kWh", Tint.SUN)
            is YearReviewPage.Battery -> when {
                page.earned != null -> tiles += Tile("battery", text.say("yearReview.share.battery"), text.money(page.earned, review.money), Tint.BATTERY)
                page.saved != null -> tiles += Tile("battery", text.say("yearReview.share.batterySaved"), approx(true, text.money(page.saved, review.money)), Tint.BATTERY)
                page.kwhOut != null -> tiles += Tile("battery", text.say("yearReview.share.batteryKwh"), "${text.number(page.kwhOut, 0)} kWh", Tint.BATTERY)
            }
            else -> Unit
        }
        return tiles.take(4)
    }

    /** "with SpotNav", or "with SpotNav, since October" for a part of the year (the month only). */
    fun subtitle(text: YearReviewText, review: YearReview): String =
        review.since?.let { text.say("yearReview.share.subSince", "month" to text.monthName(it)) } ?: text.say("yearReview.share.sub")

    private enum class Size { TILE, WIDE, LARGE }

    private fun box(x: Int, y: Int, width: Int, height: Int, size: Size = Size.TILE): Box {
        val art = when (size) { Size.TILE -> 136; Size.WIDE -> 220; Size.LARGE -> 300 }
        val large = size == Size.LARGE
        val pad = if (large) 56 else 36
        return Box(
            x, y, width, height,
            artX = x + width - art - 24, artY = y + 24, artSize = art,
            labelX = x + pad, labelY = y + if (large) 96 else 64, labelMaxWidth = width - art - pad - 38,
            labelLineHeight = if (large) 56 else 40, labelPx = if (large) 46 else LABEL_PX,
            // A wide tile's figure stays clear of its picture (it reaches down beside it).
            valueX = x + pad, valueY = y + height - if (large) 80 else 48,
            valueMaxWidth = width - pad * 2 - if (size == Size.WIDE) art + 24 else 0,
            valuePx = when (size) { Size.TILE -> VALUE_MAX_PX; Size.WIDE -> 120; Size.LARGE -> 150 }
        )
    }

    /**
     * Where each of [count] tiles goes (four at most), never leaving a hole: four in two columns and two rows, three as
     * two above and one across below, two across one above the other, one large over the whole space.
     */
    fun boxes(count: Int): List<Box> {
        val n = count.coerceIn(0, 4)
        fun rowY(row: Int) = TILE_TOP + row * (TILE_H + TILE_GAP)
        if (n == 1) return listOf(box(TILE_X[0], TILE_TOP, WIDE_W, TILE_H * 2 + TILE_GAP, Size.LARGE))
        if (n == 2) return listOf(box(TILE_X[0], rowY(0), WIDE_W, TILE_H, Size.WIDE), box(TILE_X[0], rowY(1), WIDE_W, TILE_H, Size.WIDE))
        return (0 until n).map { k ->
            val wide = n == 3 && k == 2
            box(if (wide) TILE_X[0] else TILE_X[k % 2], rowY(k / 2), if (wide) WIDE_W else TILE_W, TILE_H, if (wide) Size.WIDE else Size.TILE)
        }
    }

    /** [text] broken into at most [maxLines] lines of [maxWidth], the last one ended "…" where words are left over. */
    fun wrap(measure: (String) -> Float, text: String, maxWidth: Int, maxLines: Int = 2): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val tried = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && measure(tried) > maxWidth) {
                lines += line
                line = word
            } else {
                line = tried
            }
        }
        if (line.isNotEmpty()) lines += line
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines).toMutableList().also { it[maxLines - 1] = it[maxLines - 1] + "…" }
    }

    /** The largest size from [start] down in steps of 4 at which [text] fits [maxWidth], never under [min]. */
    fun fit(measure: (String, Int) -> Float, text: String, maxWidth: Int, start: Int = VALUE_MAX_PX, min: Int = VALUE_MIN_PX): Int {
        var size = start
        while (size > min && measure(text, size) > maxWidth) size -= 4
        return size
    }

    /** A small repeatable random (the stars fall the same for the same year), the card's own. */
    fun seeded(seed: Int): () -> Double {
        var state = seed
        return {
            state += 0x6d2b79f5
            var t = state
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
        }
    }

    /** The picture's file name. */
    fun fileName(review: YearReview): String = "spotnav-${review.year}.png"
}
