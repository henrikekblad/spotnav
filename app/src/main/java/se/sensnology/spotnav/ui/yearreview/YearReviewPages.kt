package se.sensnology.spotnav.ui.yearreview

import se.sensnology.spotnav.ha.yearreview.YearReview
import se.sensnology.spotnav.ha.yearreview.YearReviewPage
import se.sensnology.spotnav.ui.common.CardWords
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** The energy meanings a panel is tinted with (the card's `--spotnav-energy-*`). */
internal enum class Tint { HOUSE, EXPORT, CAR, SUN, BATTERY, IMPORT, EXPENSIVE }

/** A panel's big figure: counted up from zero ([digits] decimals) with its unit, [approx] marked "≈". */
internal data class BigFigure(val value: Double, val digits: Int, val unit: String, val approx: Boolean)

/** One panel in words, as the card's `year-review-pages.ts` words it. */
internal data class StoryPage(
    val id: String,
    val tint: Tint,
    val eyebrow: String,
    /** A figure ([figure]) or a line of words in its place ([words]: the cover, the thanks page); neither on a car without a share. */
    val figure: BigFigure? = null,
    val words: String? = null,
    val say: String? = null,
    /** A share of 100 drawn as a bar. */
    val meter: Double? = null,
    val chips: List<String> = emptyList(),
    val foot: List<String> = emptyList(),
    val badge: String? = null,
    val fireworks: Boolean = false
)

/**
 * The story's words: pure, from the decoded review to what each panel says, so the story and the share picture word
 * the same figures alike and the tests read them without a screen. A port of the card's `year-review-pages.ts`: a
 * page Home Assistant left out is not drawn; a page with nothing to say here (a battery with neither money nor
 * energy) is skipped; "≈" where the review says a figure is estimated; "since {date}" where its record starts after
 * the year's first days.
 *
 * [language] is the app's language (the card's words and plural forms are its), [dates] the locale dates are written
 * in and [numbers] the one figures are.
 */
internal class YearReviewText(
    private val words: CardWords,
    private val language: String,
    private val dates: Locale,
    private val numbers: Locale
) {
    /** A number with up to [digits] decimals ("1 483", "0,3"), as the card's `formatNumber`. */
    fun number(value: Double, digits: Int): String = format(value, 0, digits)

    /** A number with exactly [digits] decimals, as the card's `formatFixed`. */
    fun fixed(value: Double, digits: Int): String = format(value, digits, digits)

    private fun format(value: Double, min: Int, max: Int): String {
        val shown = if (abs(value) < 0.5 * 10.0.pow(-max)) 0.0 else value
        return NumberFormat.getNumberInstance(numbers).apply {
            minimumFractionDigits = min
            maximumFractionDigits = max
            roundingMode = RoundingMode.HALF_EVEN
            isGroupingUsed = true
        }.format(shown)
    }

    /** The card's plural form of [count] in the app's language: "one" or "other". */
    fun form(count: Double): String =
        if (count == 1.0 || (language == "fr" && count == 0.0)) "one" else "other"

    /** A calendar date as "14 October" in the language. */
    fun dayMonth(date: LocalDate): String = DateTimeFormatter.ofPattern(DAY_MONTH[language] ?: "d MMMM", dates).format(date)

    /** A date's month alone, "October" ("oktober"), for the share picture. */
    fun monthName(date: LocalDate): String = DateTimeFormatter.ofPattern("LLLL", dates).format(date)

    /** The unit an amount in minor units is shown in: the major one where the area names it. */
    fun moneyUnit(money: YearReview.Money?): Pair<String, Double> =
        if (!money?.majorUnit.isNullOrEmpty()) money!!.majorUnit!! to 100.0 else (money?.minorUnit ?: "") to 1.0

    /** An amount in minor units, whole, in the major unit: "1 483 kr". */
    fun money(minor: Double, money: YearReview.Money?): String {
        val (unit, divisor) = moneyUnit(money)
        val figure = number((minor / divisor).roundToLong().toDouble(), 0)
        return if (unit.isEmpty()) figure else "$figure $unit"
    }

    /** A price per kWh given in minor units: "0,50 kr/kWh", or "50 öre/kWh" without a major unit. */
    fun price(minorPerKwh: Double, money: YearReview.Money?): String {
        if (!money?.majorUnit.isNullOrEmpty()) return "${fixed(minorPerKwh / 100, 2)} ${money!!.majorUnit}/kWh"
        val unit = money?.minorUnit ?: ""
        return "${number(minorPerKwh, 1)}${if (unit.isEmpty()) "" else " $unit"}/kWh"
    }

    fun kwhWhole(kwh: Double): String = "${number(kwh.roundToLong().toDouble(), 0)} kWh"

    /** A small energy with a decimal below 10 kWh ("0,3 kWh"), whole above. */
    private fun kwhSmall(kwh: Double): String = "${number(kwh, if (kwh < 10) 1 else 0)} kWh"

    private fun plural(base: String, count: Double): String = words.say("$base.${form(count)}", "count" to number(count, 0))

    private fun sinceLine(date: LocalDate?): List<String> =
        if (date == null) emptyList() else listOf(words.say("yearReview.since", "date" to dayMonth(date)))

    /** The big figure's number as text, at its decimals. */
    fun figure(figure: BigFigure, value: Double = figure.value): String = fixed(value, figure.digits)

    fun say(key: String, vararg params: Pair<String, String>): String = words.say(key, *params)

    /** One page in words, or `null` for one with nothing to say. */
    fun page(review: YearReview, source: YearReviewPage, supportReachable: Boolean): StoryPage? {
        val money = review.money
        val (major, divisor) = moneyUnit(money)
        return when (source) {
            YearReviewPage.Cover -> StoryPage(
                id = source.id,
                tint = Tint.HOUSE,
                eyebrow = words.say("yearReview.title"),
                words = words.say("yearReview.cover.big", "year" to review.year.toString()),
                say = review.since?.let { words.say("yearReview.cover.saySince", "date" to dayMonth(it)) }
                    ?: words.say("yearReview.cover.say"),
                badge = if (review.partial) words.say("yearReview.cover.partial") else null,
                fireworks = true
            )
            is YearReviewPage.Saved -> {
                val chips = mutableListOf<String>()
                val parts = listOf(
                    Triple("battery", source.battery, source.startsBattery),
                    Triple("car", source.car, source.startsCar),
                    Triple("heat", source.heat, source.startsHeat)
                )
                for ((part, value, start) in parts) {
                    if (value == null) continue
                    val amount = words.say("yearReview.saved.part.$part", "amount" to money(value, money))
                    chips += if (start == null) amount
                    else words.say("yearReview.saved.partSince", "part" to amount, "date" to dayMonth(start))
                }
                val anyStart = source.startsBattery != null || source.startsCar != null || source.startsHeat != null
                StoryPage(
                    id = source.id,
                    tint = Tint.EXPORT,
                    eyebrow = words.say("yearReview.saved.eyebrow"),
                    figure = BigFigure(source.total / divisor, 0, major, source.estimated),
                    say = words.say("yearReview.saved.say"),
                    // One part alone needs no breakdown.
                    chips = if (chips.size > 1 || anyStart) chips else emptyList(),
                    foot = listOf(words.say(if (source.estimated) "yearReview.saved.footEstimated" else "yearReview.saved.foot"))
                )
            }
            is YearReviewPage.Car -> {
                val chips = mutableListOf(plural("yearReview.car.charges", source.charges.toDouble()), kwhWhole(source.kwh))
                val cheapest = source.cheapestPrice?.let { price -> source.cheapestDate?.let { price to it } }
                if (cheapest != null) {
                    chips += words.say("yearReview.car.cheapest", "price" to price(cheapest.first, money), "date" to dayMonth(cheapest.second))
                }
                val foot = mutableListOf<String>()
                source.since?.let { foot += words.say("yearReview.car.since", "date" to dayMonth(it)) }
                if (source.estimated) foot += words.say("yearReview.car.estimated")
                if (cheapest != null) foot += words.say("yearReview.fees")
                val share = source.cheapSharePct
                StoryPage(
                    id = source.id,
                    tint = Tint.CAR,
                    eyebrow = words.say("yearReview.car.eyebrow"),
                    figure = share?.let { BigFigure(it, 0, "%", source.estimated) },
                    say = words.say(if (share == null) "yearReview.car.sayCount" else "yearReview.car.say"),
                    meter = share,
                    chips = chips,
                    foot = foot
                )
            }
            is YearReviewPage.Sun -> {
                val pct = source.homePct
                val say = if (pct == null) words.say("yearReview.sun.say")
                else words.say(
                    if (source.exportPaid == true) "yearReview.sun.saySold" else "yearReview.sun.sayOut",
                    "pct" to "${number(pct, 0)} %"
                )
                StoryPage(
                    id = source.id,
                    tint = Tint.SUN,
                    eyebrow = words.say("yearReview.sun.eyebrow"),
                    figure = BigFigure(source.kwh, 0, "kWh", false),
                    say = say,
                    meter = pct,
                    foot = sinceLine(source.since)
                )
            }
            is YearReviewPage.Battery -> battery(source, money, major, divisor)
            is YearReviewPage.Fuse -> StoryPage(
                id = source.id,
                tint = Tint.IMPORT,
                eyebrow = words.say("yearReview.fuse.eyebrow"),
                figure = BigFigure(source.count.toDouble(), 0, words.say("yearReview.fuse.times.${form(source.count.toDouble())}"), false),
                say = words.say("yearReview.fuse.say"),
                foot = sinceLine(source.since)
            )
            is YearReviewPage.Expensive -> {
                val date = LocalDate.parse(source.start.substring(0, 10))
                val params = arrayOf("date" to dayMonth(date), "time" to source.start.substring(11, 16))
                val bought = source.boughtKwh
                // An amount that would be told as 0,0 kWh is told as none bought.
                val say = when {
                    bought == null -> words.say("yearReview.expensive.when", *params)
                    bought < 0.05 -> words.say("yearReview.expensive.boughtNone", *params)
                    else -> words.say(
                        if (bought < 1) "yearReview.expensive.boughtLittle" else "yearReview.expensive.bought",
                        *params, "kwh" to kwhSmall(bought)
                    )
                }
                val unit = moneyUnit(money).first
                StoryPage(
                    id = source.id,
                    tint = Tint.EXPENSIVE,
                    eyebrow = words.say("yearReview.expensive.eyebrow"),
                    figure = if (!money?.majorUnit.isNullOrEmpty()) BigFigure(source.price / 100, 2, "$unit/kWh", false)
                    else BigFigure(source.price, 1, "$unit/kWh", false),
                    say = say,
                    foot = sinceLine(source.since) + words.say("yearReview.fees")
                )
            }
            YearReviewPage.Thanks -> StoryPage(
                id = source.id,
                tint = Tint.HOUSE,
                eyebrow = words.say("yearReview.thanks.eyebrow"),
                words = words.say("yearReview.thanks.big"),
                say = words.say("yearReview.thanks.say"),
                foot = if (supportReachable) listOf(words.say("yearReview.thanks.foot")) else emptyList(),
                fireworks = true
            )
        }
    }

    /** The battery: its money (SpotNav's own count), else what it saved at each hour's price, else the energy it gave back; the cycles as a chip. */
    private fun battery(source: YearReviewPage.Battery, money: YearReview.Money?, major: String, divisor: Double): StoryPage? {
        if (source.earned == null && source.saved == null && source.kwhOut == null) return null
        val chips = mutableListOf<String>()
        source.cycles?.let { chips += plural("yearReview.battery.cycles", it) }
        val foot = mutableListOf<String>()
        val figure: BigFigure
        val say: String
        if (source.earned != null) {
            source.buyPrice?.let { chips += words.say("yearReview.battery.buy", "price" to price(it, money)) }
            source.usePrice?.let { chips += words.say("yearReview.battery.use", "price" to price(it, money)) }
            figure = BigFigure(source.earned / divisor, 0, major, false)
            say = words.say("yearReview.battery.say")
            source.earnedSince?.let { foot += words.say("yearReview.battery.earnedSince", "date" to dayMonth(it)) }
        } else if (source.saved != null) {
            figure = BigFigure(source.saved / divisor, 0, major, true)
            say = words.say("yearReview.battery.saySaved")
            foot += words.say("yearReview.battery.savedFoot")
            foot += sinceLine(source.savedSince)
        } else {
            figure = BigFigure(source.kwhOut ?: 0.0, 0, "kWh", false)
            say = words.say("yearReview.battery.sayKwh")
        }
        return StoryPage(
            id = source.id, tint = Tint.BATTERY, eyebrow = words.say("yearReview.battery.eyebrow"),
            figure = figure, say = say, chips = chips, foot = foot
        )
    }

    /** Every page the review has, in words, in Home Assistant's order. */
    fun pages(review: YearReview, supportReachable: Boolean): List<StoryPage> =
        review.pages.mapNotNull { page(review, it, supportReachable) }

    private companion object {
        /** "14 October" as each language writes a day of a month (the card's `Intl` day and long month). */
        val DAY_MONTH = mapOf(
            "en" to "d MMMM", "sv" to "d MMMM", "nl" to "d MMMM", "fr" to "d MMMM",
            "da" to "d. MMMM", "nb" to "d. MMMM", "de" to "d. MMMM", "fi" to "d. MMMM", "es" to "d 'de' MMMM"
        )
    }
}
