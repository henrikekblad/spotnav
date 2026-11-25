package se.sensnology.spotnav.ui.site

import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.HouseHistory.HistoryKind
import se.sensnology.spotnav.ha.house.HouseSources
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One block of a section of the house's details sheet; the view draws each as the card does (`house-history.ts`). */
internal sealed interface DetailBlock {
    /** The period's bars: [series] name the parts (a legend of two or more), [scale] words the tallest. */
    class Bars(val label: String, val bars: List<Bar>, val series: List<BarSeries>, val scale: (Double) -> String, val hint: String) : DetailBlock

    /** A name and its figure, [muted] for one not known. */
    data class Figure(val name: String, val value: String, val muted: Boolean = false) : DetailBlock

    data class Note(val text: String) : DetailBlock

    /** The sentence over the shares ("Sigenergy AI 82 % av perfekt · SpotNav Balanserad 88 %"). */
    data class Headline(val text: String) : DetailBlock

    /** A way's share of the perfect plan, a bar of [percent] (0 to 100, `null`: none) and its [figure]; [inForce] marks the chosen mode. */
    data class Share(val name: String, val inForce: String?, val percent: Double?, val figure: String, val muted: Boolean) : DetailBlock

    /** A table with a head row; a cell of [mutedCells] (row, column) is drawn muted. */
    class Table(val head: List<String>, val rows: List<List<String>>, val mutedCells: Set<Pair<Int, Int>> = emptySet()) : DetailBlock

    /** The ways' figures: a name on a line of its own over its cells, [head] naming the cells (so it fits a phone). */
    class Ways(val head: List<String>, val groups: List<Pair<String, List<String>>>) : DetailBlock

    /** Where the figures come from: a [heading], a list of [facts] and then plain [notes]. */
    data class Sources(val heading: String, val facts: List<String>, val notes: List<String>) : DetailBlock
}

/** A section of the sheet with its heading; [key] names it for tests and the view. */
internal class HouseDetailSection(val key: String, val title: String, val blocks: List<DetailBlock>)

/** The words a count takes, apart from [SiteText]: the card's `pluralForm` and `numberWord`. */
internal object CountWords {
    /** `one` or `other`, by the language's own rule (French counts 0 as one). */
    fun form(language: String, count: Int): String = if (count == 1 || (language == "fr" && count == 0)) "one" else "other"

    private val NUMBERS = mapOf(
        "en" to "one two three four five six seven eight nine ten eleven twelve",
        "sv" to "en två tre fyra fem sex sju åtta nio tio elva tolv",
        "nb" to "en to tre fire fem seks syv åtte ni ti elleve tolv",
        "da" to "en to tre fire fem seks syv otte ni ti elleve tolv",
        "fi" to "yksi kaksi kolme neljä viisi kuusi seitsemän kahdeksan yhdeksän kymmenen yksitoista kaksitoista",
        "de" to "ein zwei drei vier fünf sechs sieben acht neun zehn elf zwölf",
        "nl" to "een twee drie vier vijf zes zeven acht negen tien elf twaalf",
        "fr" to "un deux trois quatre cinq six sept huit neuf dix onze douze",
        "es" to "un dos tres cuatro cinco seis siete ocho nueve diez once doce"
    ).mapValues { it.value.split(' ') }

    /** A small count of things in running text: the word for 1 to 12, the digits otherwise. */
    fun word(language: String, count: Int): String = NUMBERS[language]?.getOrNull(count - 1)?.takeIf { count in 1..12 } ?: count.toString()
}

private fun SiteText.form(count: Int) = CountWords.form(locale.language.let { if (it == "no") "nb" else it }, count)

private fun SiteText.numberWord(count: Int) = CountWords.word(locale.language.let { if (it == "no") "nb" else it }, count)

/**
 * The house's details sheet (the card's history dialog, `frontend/src/house-history.ts` and `perfect-view.ts`): for the
 * period of the shared picker, every section always open, each figure once. Sections: the cost bars, Andel av perfekt
 * (the shares and one table) or the battery's modes, Pengar, SpotNav sparade, Prognosfel, Dygn för dygn and Så räknas
 * det with Källor. Pure: the view draws what this says.
 */
internal object HouseDetails {
    /** A day's share needs the perfect plan to save at least this much (minor unit; `hindsight.MIN_PERFECT_SAVING`). */
    private const val MIN_PERFECT_SAVING = 500.0

    private val WAYS = listOf("own", "self_consumption", "balanced", "max_profit")
    private val MODES = listOf("self_consumption", "balanced", "max_profit")

    /** A note first where the period has no meter at all, then the sections the period has. */
    fun sections(text: SiteText, locale: Locale, history: HouseHistory, house: House?): List<HouseDetailSection> {
        val parts = buildList {
            houseBars(text, locale, history)?.let { add(it) }
            add(perfectSection(text, history) ?: modesSection(text, history))
            add(moneySection(text, history))
            add(spotnavSection(text, history))
            add(forecastSection(text, history))
            add(daysSection(text, locale, history))
            add(howSection(text, history, house))
        }.filterNotNull()
        if (history.energyFlow == null && history.buckets.isEmpty()) {
            return listOf(HouseDetailSection("no-meters", "", listOf(DetailBlock.Note(text.words.say("hist.house.noMeters"))))) + parts
        }
        return parts
    }

    private fun kwh(text: SiteText, value: Double) = "${text.fixed(value, if (value >= 100) 0 else 1)} kWh"

    private fun share(text: SiteText, part: Double, whole: Double) = if (whole > 0) text.percent(part / whole * 100) else "–"

    private fun days(text: SiteText, count: Int) = text.words.say("hist.days", "days" to count.toString())

    // ---------------------------------------------------------------------------------------------- the cost bars

    /** What a money bar is made of: the filled cost, the hatched saving on top, or the cost's own top hatched in warning where the battery made it dearer. */
    class MoneyParts(val solid: Double, val hatch: Double, val warn: Double) {
        val whole: Double get() = solid + hatch + warn
    }

    fun moneyParts(cost: Double?, saving: Double?): MoneyParts {
        val paid = maxOf(0.0, cost ?: 0.0)
        if (cost == null || saving == null) return MoneyParts(paid, 0.0, 0.0)
        val without = cost + saving
        if (without >= paid) return MoneyParts(paid, without - paid, 0.0)
        val solid = maxOf(0.0, without)
        return MoneyParts(solid, 0.0, paid - solid)
    }

    const val COST = "cost"
    const val SAVING = "saving"
    const val EXTRA = "extra"
    const val USE = "use"

    private fun houseBars(text: SiteText, locale: Locale, answer: HouseHistory): HouseDetailSection? {
        val words = text.words
        if (answer.buckets.isEmpty() || answer.buckets.all { it.periods == 0 }) return null
        val n = answer.buckets.size
        val label = words.say("hist.house.chart")
        val hint = words.say("hist.house.hint")
        // Without a price in the period there is no money to draw: the energy, in one colour.
        if (answer.buckets.all { it.cost == null }) {
            val bars = answer.buckets.mapIndexed { index, item ->
                val (`when`, axis) = HouseGraph.bucketWords(locale, answer.period, item.key, index, n)
                val said = if (item.periods == 0) words.say("hist.house.barNone", "when" to `when`)
                else words.say("hist.house.barNoCost", "when" to `when`, "use" to kwh(text, item.use), "bought" to kwh(text, item.gridIn))
                Bar(item.key, if (item.periods == 0 || item.use <= 0) emptyList() else listOf(listOf(BarPart(USE, item.use))), said, axis)
            }
            val series = listOf(BarSeries(USE, "", 0))
            return HouseDetailSection("bars", label, listOf(DetailBlock.Bars(label, bars, series, { kwh(text, it) }, hint)))
        }
        val bars = answer.buckets.mapIndexed { index, item ->
            val (`when`, axis) = HouseGraph.bucketWords(locale, answer.period, item.key, index, n)
            val saving = if (item.cost == null) null else item.batterySaving
            val cost = if (item.periods == 0) null else item.cost
            fun amount(value: Double) = text.amount(value, answer.money)
            var said: String
            if (item.periods == 0) {
                said = words.say("hist.house.barNone", "when" to `when`)
            } else if (item.cost == null) {
                said = words.say("hist.house.barNoCost", "when" to `when`, "use" to kwh(text, item.use), "bought" to kwh(text, item.gridIn))
            } else if (saving == null) {
                said = words.say(if (answer.period == HistoryKind.DAY) "hist.house.barHourly" else "hist.house.barUnreviewed", "when" to `when`, "cost" to amount(item.cost))
            } else {
                said = words.say(
                    if (saving < 0) "hist.house.barWorse" else "hist.house.barMoney",
                    "when" to `when`, "cost" to amount(item.cost), "without" to amount(item.cost + saving),
                    "saving" to amount(saving), "extra" to amount(-saving)
                )
                // A year's month may be reviewed on some of its days only: say how many.
                if (answer.period == HistoryKind.YEAR) said += " (${days(text, item.batteryDays)})"
            }
            val parts = moneyParts(cost, saving)
            // Bottom up: the filled cost, then the hatched extra, then the hatched saving.
            val lane = listOf(BarPart(COST, parts.solid), BarPart(EXTRA, parts.warn), BarPart(SAVING, parts.hatch)).filter { it.value > 0 }
            Bar(item.key, if (lane.isEmpty()) emptyList() else listOf(lane), said, axis)
        }
        // A swatch per part the bars hold: the saving and the extra only where a bar has one.
        val shown = answer.buckets.map { moneyParts(if (it.periods == 0) null else it.cost, if (it.cost == null) null else it.batterySaving) }
        val series = buildList {
            if (shown.any { it.solid > 0 }) add(BarSeries(COST, words.say("hist.legend.cost"), 0))
            if (shown.any { it.hatch > 0 }) add(BarSeries(SAVING, words.say("hist.house.legend.saving"), 0, hatched = true))
            if (shown.any { it.warn > 0 }) add(BarSeries(EXTRA, words.say("hist.house.legend.extra"), 0, hatched = true))
        }
        return HouseDetailSection("bars", label, listOf(DetailBlock.Bars(label, bars, series, { text.amount(it, answer.money) }, hint)))
    }

    // ---------------------------------------------------------------------------------------------- money

    private fun moneySection(text: SiteText, answer: HouseHistory): HouseDetailSection {
        val words = text.words
        val blocks = mutableListOf<DetailBlock>()
        fun amount(value: Double) = text.amount(value, answer.money)
        val spent = answer.cost
        if (spent == null || spent.priced == 0) {
            blocks += DetailBlock.Note(words.say(if (answer.money == null) "hist.house.noPriceSource" else "hist.house.noPrices"))
        } else {
            blocks += DetailBlock.Figure(words.say("hist.house.cost"), amount(spent.net))
            blocks += DetailBlock.Note(
                words.say(
                    "hist.house.costParts", "bought" to amount(spent.bought), "kwhIn" to kwh(text, spent.kwhIn),
                    "sold" to amount(spent.sold), "kwhOut" to kwh(text, spent.kwhOut)
                )
            )
            if (spent.priced < spent.periods) {
                blocks += DetailBlock.Note(words.say("hist.house.costPartial", "share" to share(text, spent.priced.toDouble(), spent.periods.toDouble())))
            }
        }
        val reviewed = answer.review
        if (reviewed == null) {
            blocks += DetailBlock.Note(words.say("hist.house.noReview"))
            return HouseDetailSection("money", words.say("hist.house.money"), blocks)
        }
        // What each saved, and over how many days the saving is measured: one count in the note, and a row's own only where
        // it has fewer days than that.
        val rows = listOf(
            Triple("battery", reviewed.batterySaving, reviewed.batteryDays),
            Triple("sun", reviewed.sunGave, reviewed.sunDays),
            Triple("both", reviewed.againstNothing, reviewed.nothingDays)
        ).filter { it.second != null }
        val measured = rows.maxOfOrNull { it.third } ?: 0
        for ((key, value, count) in rows) {
            val figure = amount(value!!)
            blocks += DetailBlock.Figure(
                words.say("hist.house.saved.$key"),
                if (count < measured) words.say("hist.house.savedDays.${text.form(count)}", "figure" to figure, "days" to count.toString()) else figure
            )
        }
        if (rows.isNotEmpty()) blocks += DetailBlock.Note(words.say("hist.house.baselinesNote"))
        blocks += DetailBlock.Note(
            words.say(
                "hist.house.reviewed",
                "days" to (if (rows.isNotEmpty()) measured else reviewed.days).toString(),
                "total" to maxOf(reviewed.days, reviewed.periodDays).toString()
            )
        )
        if (reviewed.archived > 0) blocks += DetailBlock.Note(words.say("hist.house.archived", "days" to reviewed.archived.toString()))
        return HouseDetailSection("money", words.say("hist.house.money"), blocks)
    }

    // ---------------------------------------------------------------------------------------------- SpotNav's saving

    private fun spotnavSection(text: SiteText, answer: HouseHistory): HouseDetailSection? {
        val saving = answer.saving ?: return null
        val words = text.words
        fun amount(value: Double) = text.amount(value, answer.money)
        val blocks = mutableListOf<DetailBlock>(DetailBlock.Figure(words.say("hist.house.spotnav.total"), amount(saving.total)))
        for ((key, value, count) in listOf(Triple("battery", saving.battery, saving.batteryDays), Triple("car", saving.car, saving.carDays), Triple("heat", saving.heat, saving.heatDays))) {
            if (value == null) continue
            val figure = amount(value)
            blocks += DetailBlock.Figure(
                words.say("hist.house.spotnav.$key"),
                if (count < saving.days) words.say("hist.house.savedDays.${text.form(count)}", "figure" to figure, "days" to count.toString()) else figure
            )
        }
        blocks += DetailBlock.Note(words.say("hist.house.spotnav.note"))
        return HouseDetailSection("spotnav-saving", words.say("hist.house.spotnav"), blocks)
    }

    // ---------------------------------------------------------------------------------------------- ways

    private fun wayName(text: SiteText, way: String): String =
        if (way == "own") text.words.say("house.compare.own") else text.words.say("house.compare.spotnav", "mode" to text.words.say("house.mode.$way"))

    /** The ways in the order shown: the battery's own, the chosen mode, the other modes, self-consumption last. */
    private fun order(mode: String?): List<String> {
        val modes = MODES.filter { it != "self_consumption" }
        return listOf("own") + modes.filter { it == mode } + modes.filter { it != mode } + "self_consumption"
    }

    fun hasPerfect(perfect: HouseHistory.Perfect?): Boolean = perfect != null && ((perfect.days > 0 && perfect.saving != null) || perfect.partial > 0)

    private fun onlyPartial(perfect: HouseHistory.Perfect) = perfect.days == 0 || perfect.saving == null

    /** Whether a way lost money against no battery: its share is then no share ("0 %") but a loss with its amount. */
    private fun lost(perfect: HouseHistory.Perfect, way: String): Boolean = (perfect.savings[way] ?: 0.0) < 0

    /** A way's figure beside its bar: its share, or "förlust 47 kr" where it lost money against no battery. */
    private fun shareText(text: SiteText, perfect: HouseHistory.Perfect, way: String, value: Double, money: House.Money?): String =
        if (lost(perfect, way)) text.words.say("perfect.loss", "amount" to wholeMoney(text, Math.abs(perfect.savings[way]!!), money)) else text.percent(value)

    /**
     * "Sigenergy AI 82 % av perfekt · SpotNav Balanserad 88 %"; a way that lost money against no battery says so with
     * its amount ("Sigenergy AI: förlust 47 kr mot inget batteri"); `null` when neither has a share.
     */
    private fun perfectHeadline(text: SiteText, perfect: HouseHistory.Perfect, money: House.Money?): String? {
        val own = perfect.shares["own"]
        val mode = WAYS.firstOrNull { it != "own" && it == perfect.mode }
        val plan = mode?.let { perfect.shares[it] }
        val words = text.words
        fun part(way: String, value: Double): String =
            if (lost(perfect, way)) words.say("perfect.headlineLoss", "name" to wayName(text, way), "amount" to wholeMoney(text, Math.abs(perfect.savings[way]!!), money))
            else words.say("perfect.headlineOne", "name" to wayName(text, way), "share" to text.percent(value))
        if (own != null && mode != null && plan != null) {
            if (lost(perfect, "own") || lost(perfect, mode)) return "${part("own", own)} · ${part(mode, plan)}"
            return words.say("perfect.headline", "own" to wayName(text, "own"), "ownShare" to text.percent(own), "plan" to wayName(text, mode), "planShare" to text.percent(plan))
        }
        val (way, value) = when {
            own != null -> "own" to own
            mode != null && plan != null -> mode to plan
            else -> return null
        }
        return part(way, value)
    }

    private fun perfectRows(text: SiteText, perfect: HouseHistory.Perfect, inForce: String?, money: House.Money?): List<DetailBlock> {
        if (onlyPartial(perfect)) return emptyList()
        val words = text.words
        return order(perfect.mode).mapNotNull { way ->
            val value = perfect.shares[way]
            val ran = perfect.savings[way] != null
            if (!ran && way != "own") return@mapNotNull null // a mode not planned beside the battery on these days
            DetailBlock.Share(
                name = wayName(text, way), inForce = if (way == inForce) words.say("house.compare.inForce") else null,
                percent = value, muted = value == null,
                figure = if (value != null) shareText(text, perfect, way, value, money) else words.say(if (ran) "perfect.nothing" else "house.compare.none")
            )
        }
    }

    private fun perfectNotes(text: SiteText, perfect: HouseHistory.Perfect, singleDay: Boolean): List<DetailBlock.Note> {
        val words = text.words
        val notes = mutableListOf<String>()
        if (onlyPartial(perfect)) {
            notes += if (singleDay) words.say("perfect.partialDay") else words.say("perfect.partialOnly.${text.form(perfect.partial)}", "days" to perfect.partial.toString())
            return notes.map { DetailBlock.Note(it) }
        }
        notes += words.say(
            when (perfect.rules) { "max_profit" -> "perfect.explainSell"; "no_sell" -> "perfect.explainNoSell"; else -> "perfect.explain" },
            "mode" to words.say("house.mode.max_profit")
        )
        if (singleDay) notes += words.say("perfect.midnight")
        if (perfect.provisional > 0) {
            notes += if (singleDay) words.say("perfect.provisionalDay")
            else words.say("perfect.provisional.${text.form(perfect.provisional)}", "days" to text.numberWord(perfect.provisional))
        }
        if (perfect.partial > 0) notes += words.say("perfect.partial.${text.form(perfect.partial)}", "days" to perfect.partial.toString())
        // Clamped at 100 % only: a way clamped at 0 % lost money and says so with its amount in its row.
        val clamped = perfect.clamped.filter { it in WAYS && !lost(perfect, it) }
        if (clamped.isNotEmpty()) notes += words.say("perfect.clamped", "names" to clamped.joinToString(", ") { wayName(text, it) })
        return notes.map { DetailBlock.Note(it) }
    }

    private fun wholeMoney(text: SiteText, minor: Double, money: House.Money?): String =
        if (money?.majorUnit == null) text.amount(minor, money) else "${text.fixed(minor / 100, 0)} ${money.majorUnit}"

    private fun perfectTable(text: SiteText, perfect: HouseHistory.Perfect, money: House.Money?): DetailBlock.Ways? {
        if (onlyPartial(perfect)) return null
        val words = text.words
        val head = listOf("perfect.money.saved", "perfect.work.cycles", "perfect.work.switches", "perfect.work.fromGrid", "perfect.work.toGrid").map { words.say(it) }
        val rows = order(perfect.mode).filter { perfect.savings[it] != null }.map { Triple(it, wayName(text, it), perfect.savings[it]) } +
            Triple("perfect", words.say("perfect.money.perfect"), perfect.saving)
        return DetailBlock.Ways(
            head,
            rows.map { (way, name, saving) ->
                val work = perfect.work[way]
                name to listOf(
                    saving?.let { text.amount(it, money) } ?: "–",
                    work?.let { text.fixed(it.cycles, 2) } ?: "–",
                    work?.let { text.fixed(it.switches.toDouble(), 0) } ?: "–",
                    work?.let { text.fixed(it.gridChargeKwh, 1) } ?: "–",
                    work?.let { text.fixed(it.toGridKwh, 1) } ?: "–"
                )
            }
        )
    }

    private fun perfectTemperature(text: SiteText, perfect: HouseHistory.Perfect): DetailBlock.Note? {
        val t = perfect.temperature ?: return null
        return DetailBlock.Note(
            text.words.say(
                "perfect.temperature.${text.form(t.days)}",
                "mean" to "${text.fixed(t.meanC, 1)} °C", "max" to "${text.fixed(t.maxC, 1)} °C",
                "minutes" to text.fixed(t.minutesAbove.toDouble(), 0), "days" to text.numberWord(t.days)
            )
        )
    }

    /** The battery's ways over the period, each figure once: the share of the perfect plan as the headline and a bar per way, then one table. `null` before the first whole day with a perfect plan. */
    private fun perfectSection(text: SiteText, answer: HouseHistory): HouseDetailSection? {
        val reviewed = answer.review
        val perfect = reviewed?.perfect
        if (reviewed == null || !hasPerfect(perfect) || onlyPartial(perfect!!)) return null
        val words = text.words
        val blocks = mutableListOf<DetailBlock>()
        perfectHeadline(text, perfect, answer.money)?.let { blocks += DetailBlock.Headline(it) }
        blocks += perfectRows(text, perfect, reviewed.mode, answer.money)
        // The figures first, then every word about them together.
        val table = perfectTable(text, perfect, answer.money)
        table?.let { blocks += it }
        blocks += perfectNotes(text, perfect, answer.period == HistoryKind.DAY)
        if (table != null) {
            blocks += DetailBlock.Note(words.say("perfect.money.note", "amount" to wholeMoney(text, MIN_PERFECT_SAVING, answer.money)))
            blocks += DetailBlock.Note(words.say("perfect.work.note"))
        }
        // The same comparison stands under Money with another sum: say why, with both figures.
        val own = perfect.savings["own"]?.let { text.amount(it, answer.money) }
        if (own != null && reviewed.batterySaving != null) {
            blocks += DetailBlock.Note(
                words.say(
                    "hist.house.perfectDiffers", "name" to wayName(text, "own"), "own" to own,
                    "battery" to text.amount(reviewed.batterySaving, answer.money), "days" to perfect.days.toString(), "batteryDays" to reviewed.batteryDays.toString()
                )
            )
        }
        perfectTemperature(text, perfect)?.let { blocks += it }
        return HouseDetailSection("perfect", words.say("perfect.headingPeriod"), blocks)
    }

    /** Before the first whole day with a perfect plan: SpotNav's modes and the battery's own way against plain self-consumption. */
    private fun modesSection(text: SiteText, answer: HouseHistory): HouseDetailSection? {
        val reviewed = answer.review ?: return null
        val words = text.words
        val blocks = mutableListOf<DetailBlock>()
        // Self-consumption is what the others are measured against, so it is not a row of its own.
        for (mode in MODES.filter { it != "self_consumption" }) {
            val figures = reviewed.modes[mode]
            var figure = words.say("house.compare.none")
            if (figures?.saving != null) {
                figure = text.amount(figures.saving, answer.money)
                if (figures.days < reviewed.days) figure += " (${days(text, figures.days)})"
            }
            val name = words.say("house.compare.spotnav", "mode" to words.say("house.mode.$mode")) +
                if (mode == reviewed.mode) " · ${words.say("house.compare.inForce")}" else ""
            blocks += DetailBlock.Figure(name, figure, muted = figures?.saving == null)
        }
        blocks += DetailBlock.Figure(words.say("house.compare.own"), text.amount(reviewed.ownSaving, answer.money))
        blocks += DetailBlock.Note(words.say("hist.house.modesNote"))
        val perfect = reviewed.perfect
        if (perfect != null) {
            // Only incomplete days so far: say so, and the cells' temperature where the battery reports it.
            if (hasPerfect(perfect)) blocks += perfectNotes(text, perfect, answer.period == HistoryKind.DAY)
            perfectTemperature(text, perfect)?.let { blocks += it }
        }
        return HouseDetailSection("modes", words.say("hist.house.modes"), blocks)
    }

    // ---------------------------------------------------------------------------------------------- forecast, days

    private fun forecastSection(text: SiteText, answer: HouseHistory): HouseDetailSection {
        val words = text.words
        val blocks = mutableListOf<DetailBlock>()
        val f = answer.forecast
        var any = false
        for ((label, error, unit) in listOf(
            Triple("hist.forecast.house", f.house, "kW"), Triple("hist.forecast.sun", f.sun, "kWh"),
            Triple("hist.forecast.sunCorrected", f.sunCorrected, "kWh"), Triple("hist.forecast.soc", f.soc, "%")
        )) {
            if (error == null) continue
            any = true
            val value = if (unit == "%") "±${text.fixed(error.mae, 0)} %" else "±${text.fixed(error.mae, if (error.mae < 1) 2 else 1)} $unit"
            blocks += DetailBlock.Figure(words.say(label), "$value (${days(text, error.days)})")
        }
        blocks += DetailBlock.Note(words.say(if (any) "hist.forecast.note" else "hist.forecast.none"))
        return HouseDetailSection("forecast", words.say("hist.forecast.heading"), blocks)
    }

    private fun daysSection(text: SiteText, locale: Locale, answer: HouseHistory): HouseDetailSection? {
        val reviewed = answer.review
        if (reviewed == null || answer.period == HistoryKind.DAY || reviewed.byDay.isEmpty()) return null
        val words = text.words
        val shares = reviewed.byDay.any { it.ownShare != null }
        val head = buildList {
            addAll(listOf("house.days.date", "hist.house.col.battery", "hist.house.col.plan", "hist.house.col.own").map { words.say(it) })
            if (shares) add(words.say("hist.house.col.share"))
        }
        val muted = mutableSetOf<Pair<Int, Int>>()
        val rows = reviewed.byDay.mapIndexed { row, day ->
            buildList {
                add(HouseGraph.bucketWords(locale, HistoryKind.MONTH, day.date.toString(), 0, 1).first)
                for (value in listOf(day.batterySaving, day.planSaving, day.ownSaving)) add(value?.let { text.amount(it, answer.money) } ?: "–")
                if (shares) {
                    add(
                        when {
                            day.shareNote == "loss" -> words.say("hist.house.share.loss")
                            day.ownShare != null -> text.percent(day.ownShare)
                            day.shareNote == null -> "–"
                            else -> words.say("hist.house.share.${day.shareNote}").also { muted += row to 4 }
                        }
                    )
                }
            }
        }
        return HouseDetailSection("days", words.say("hist.house.days"), listOf(DetailBlock.Table(head, rows, muted), DetailBlock.Note(words.say("hist.house.daysNote"))))
    }

    // ---------------------------------------------------------------------------------------------- how it is counted

    /** The date as a short label without a closing full stop ("10 nov"), for a sentence that ends in its own. */
    private fun shortDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern("d MMM", locale).format(date).replace(".", "")

    /** Last: how the figures above are counted, what SpotNav's saving is compared with, how the energy flow is allocated, and where the figures come from. */
    private fun howSection(text: SiteText, answer: HouseHistory, house: House?): HouseDetailSection {
        val words = text.words
        val blocks = mutableListOf<DetailBlock>(DetailBlock.Note(words.say("hist.house.footnote")))
        val saving = answer.saving
        if (saving != null || answer.ownControl != null) {
            val lines = mutableListOf<String>()
            if (saving?.battery != null) lines += words.say("house.saving.details.battery")
            else if (answer.ownControl != null) lines += words.say("house.saving.details.batteryOwn")
            if (saving?.car != null) {
                val since = house?.saving?.carSince?.let { " " + words.say("house.saving.details.carSince", "date" to shortDate(it, text.locale)) }.orEmpty()
                lines += words.say("house.saving.details.car") + since
            }
            if (saving?.heat != null) lines += words.say("house.saving.details.heat")
            if (lines.isNotEmpty()) {
                blocks += DetailBlock.Note(words.say("house.saving.details.head"))
                lines.forEach { blocks += DetailBlock.Note(it) }
            }
        }
        val flow = answer.energyFlow
        if (flow != null) {
            blocks += DetailBlock.Note(words.say(if (flow.stepMin < 60) "house.flow.ruleQuarter" else "house.flow.rule"))
            if (flow.carHours < flow.hours && flow.nodes.any { it.role == "car" }) blocks += DetailBlock.Note(words.say("house.flow.carPart"))
            if (flow.nodes.any { it.role == "heat" }) blocks += DetailBlock.Note(words.say(if (flow.heatHours < flow.hours) "house.flow.heatPart" else "house.flow.heatRule"))
        }
        house?.sources?.let { sources(text, house, it) }?.let { blocks += it }
        return HouseDetailSection("how", words.say("hist.house.how"), blocks)
    }

    private fun plusMinus(text: SiteText, value: Double, unit: String) = "±${text.fixed(value, if (value < 1) 2 else 1)} $unit"

    /** Where the house's figures come from and what was assumed (the card's `houseSources`): a list of facts, then notes. */
    private fun sources(text: SiteText, house: House, sources: HouseSources): DetailBlock {
        val words = text.words
        val facts = mutableListOf<String>()
        if (sources.weatherEntity == null) facts += words.say("house.sources.weatherNone")
        else facts += words.say(if (sources.weatherOk) "house.sources.weather" else "house.sources.weatherNoForecast", "name" to sources.weatherEntity)
        if (sources.outdoor == "sensor" && sources.outdoorSensor != null) facts += words.say("house.sources.outdoorSensor", "name" to sources.outdoorSensor)
        else facts += words.say(if (sources.outdoor == "weather") "house.sources.outdoorWeather" else "house.sources.outdoorNone")
        facts += if (sources.holidayCalendar == null) words.say("house.sources.holidaysNone") else words.say("house.sources.holidays", "name" to sources.holidayCalendar)
        sources.temperature?.let {
            facts += if (it.active) words.say("house.sources.temperatureOn")
            else words.say("house.sources.temperatureOff", "reason" to words.say(if (it.reason == "too_few_hours") "house.temperature.tooFew" else "house.temperature.tooLittle"))
        }
        if (sources.solarForecasts.isNotEmpty()) {
            val names = sources.solarForecasts.joinToString(", ")
            val left = house.sun?.takeIf { it.status == "learning" }?.daysLeft
            facts += if (left == null || left <= 0) words.say("house.sources.solar", "names" to names)
            else words.say("house.sources.solarLearning", "names" to names, "left" to HouseNowModel.daysLeft(text, left))
        }
        sources.heat?.let { heat ->
            val from = when (heat.source) { "energy_dashboard" -> "house.sources.heatFromDashboard"; "integration" -> "house.sources.heatFromIntegration"; else -> "house.sources.heatFromSetting" }
            facts += words.say(from, "name" to (heat.name ?: heat.entityId))
            if (heat.splitMae != null && heat.houseMae != null && heat.hours != null) {
                facts += words.say("house.sources.heatSplit", "split" to plusMinus(text, heat.splitMae, "kW"), "whole" to plusMinus(text, heat.houseMae, "kW"), "hours" to heat.hours.toString())
            }
        }
        val battery = house.battery
        val notes = mutableListOf<String>()
        if (battery != null) {
            val size = battery.usableKwh?.let { " · ${text.fixed(it, 1)} kWh" }.orEmpty()
            facts += words.say("house.sources.battery", "name" to "${battery.label ?: battery.brand.orEmpty()}$size")
            val today = house.today
            if (today?.storedKwh != null && today.averageCost != null) {
                facts += words.say(
                    if (today.estimated) "house.sources.storedEstimated" else "house.sources.stored",
                    "kwh" to "${text.fixed(today.storedKwh, 1)} kWh",
                    "cost" to "${text.fixed(today.averageCost, 0)} ${house.money?.minorUnit.orEmpty()}/kWh".replace(Regex("^/"), "")
                )
            }
            notes += words.say(if (battery.control == "on") "house.note.controlling" else if (battery.management == "self_managed") "house.note.selfManaged" else "house.note.shadow")
            val assumed = sources.batteryEstimates.filter { it in ESTIMATES }.map { words.say("house.estimate.$it") }
            if (assumed.isNotEmpty()) notes += words.say("house.note.assumed", "list" to assumed.joinToString(", "))
        }
        if (sources.countsBattery) notes += words.say("house.note.countsBattery")
        return DetailBlock.Sources(words.say("house.sources.heading"), facts, notes)
    }

    private val ESTIMATES = setOf(
        "min_soc_assumed", "round_trip_assumed", "solar_in_house_history", "battery_mode_unknown", "wear_currency_unknown",
        "no_solar_forecast", "horizon_cut_by_forecast", "capacity_from_ah", "rated_from_current", "capacity_learned", "rated_learned"
    )
}
