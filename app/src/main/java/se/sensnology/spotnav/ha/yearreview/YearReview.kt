package se.sensnology.spotnav.ha.yearreview

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.MalformedDocument
import se.sensnology.spotnav.ha.house.StrictJson
import java.time.LocalDate

/**
 * The year's review ("Året med SpotNav", webhook `get_year_review`, API v1): the twin of the card's
 * `spotnav/get_year_review`, answered for this phone (its secret `app_id`) instead of a Home Assistant user. Home
 * Assistant works every page out and leaves out a page with nothing to stand on; the app only words what it is given.
 * Money in the area's minor unit (öre, cent), prices per kWh in the minor unit, dates ISO.
 */
internal data class YearReviewAnswer(
    val siteId: String,
    val year: Int,
    val state: State,
    /** The band at the top of the house and charger pages: offered, ready, and this phone has neither opened nor closed it. */
    val banner: Boolean,
    /** The row under Settings → Support: offered and ready. */
    val row: Boolean,
    /** The review, only when [state] is ready. */
    val review: YearReview?
) {
    enum class State { READY, COMPUTING, NONE }
}

internal data class YearReview(
    val year: Int,
    val through: LocalDate,
    /** Where SpotNav's own record starts, when that is after the year's first days. */
    val since: LocalDate?,
    val partial: Boolean,
    val money: Money?,
    val pages: List<YearReviewPage>
) {
    data class Money(val currency: String?, val majorUnit: String?, val minorUnit: String?)
}

/** One page of the story, in the order Home Assistant sends them. */
internal sealed interface YearReviewPage {
    val id: String

    data object Cover : YearReviewPage { override val id = "cover" }

    data class Saved(
        val total: Double,
        val battery: Double?,
        val car: Double?,
        val heat: Double?,
        val estimated: Boolean,
        val startsBattery: LocalDate?,
        val startsCar: LocalDate?,
        val startsHeat: LocalDate?
    ) : YearReviewPage { override val id = "saved" }

    data class Car(
        val cheapSharePct: Double?,
        val charges: Int,
        val kwh: Double,
        val cheapestPrice: Double?,
        val cheapestDate: LocalDate?,
        val estimated: Boolean,
        val since: LocalDate?
    ) : YearReviewPage { override val id = "car" }

    data class Sun(
        val kwh: Double,
        val homePct: Double?,
        /** Whether what went out earned anything: the story says "sold" only when it did. */
        val exportPaid: Boolean?,
        val since: LocalDate?
    ) : YearReviewPage { override val id = "sun" }

    data class Battery(
        val earned: Double?,
        val earnedSince: LocalDate?,
        /** What the battery saved at each hour's price, whoever controlled it (≈). */
        val saved: Double?,
        val savedSince: LocalDate?,
        val cycles: Double?,
        val buyPrice: Double?,
        val usePrice: Double?,
        val kwhIn: Double?,
        val kwhOut: Double?
    ) : YearReviewPage { override val id = "battery" }

    data class Fuse(val count: Int, val since: LocalDate?) : YearReviewPage { override val id = "fuse" }

    data class Expensive(
        val price: Double,
        /** The hour's local start with its offset, as Home Assistant wrote it ("2026-01-28T08:00:00+01:00"). */
        val start: String,
        val boughtKwh: Double?,
        val since: LocalDate?
    ) : YearReviewPage { override val id = "expensive" }

    data object Thanks : YearReviewPage { override val id = "thanks" }
}

/**
 * `get_year_review` and its decoder. In the app a key it does not know is let be and a page it does not know is left
 * out (a newer Home Assistant may add one); [decode] with `strict` (the contract tests) refuses both, as the card's
 * decoder does in its tests.
 */
internal object YearReviewRead {
    const val API_VERSION = 1
    const val ACTION = "get_year_review"

    sealed interface Outcome {
        data class Read(val answer: YearReviewAnswer) : Outcome

        /**
         * No review to offer from this Home Assistant: one without the action ("Unsupported action"), another API
         * version, a home not loaded, or an answer that is not the version's shape. No band and no row.
         */
        data object None : Outcome

        /** No answer at all (offline, a server error): what was held stays, asked again later. */
        data object Later : Outcome
    }

    fun payload(appId: String?): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", ACTION)
        put("api_version", API_VERSION)
        if (appId != null) put("app_id", appId)
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null || status in 500..599) return Outcome.Later
        val head = WriteEnvelope.head(body) ?: return Outcome.None
        if (!head.ok) return Outcome.None
        return decode(head.json, strict = false)?.let(Outcome::Read) ?: Outcome.None
    }

    private val ANSWER_KEYS = setOf("api_version", "site_id", "year", "state", "banner", "row", "review")
    /** The webhook's envelope around the command's answer. */
    private val ENVELOPE_KEYS = setOf("ok", "action")
    private val REVIEW_KEYS = setOf("year", "through", "since", "partial", "money", "pages")
    private val MONEY_KEYS = setOf("currency", "major_unit", "minor_unit")
    val PAGE_KEYS: Map<String, Set<String>> = mapOf(
        "cover" to setOf("id"),
        "saved" to setOf("id", "total", "battery", "car", "heat", "estimated", "starts"),
        "car" to setOf("id", "cheap_share_pct", "charges", "kwh", "cheapest", "estimated", "since"),
        "sun" to setOf("id", "kwh", "home_pct", "export_paid", "since"),
        "battery" to setOf("id", "earned", "earned_since", "saved", "saved_since", "cycles", "buy_price", "use_price", "kwh_in", "kwh_out"),
        "fuse" to setOf("id", "count", "since"),
        "expensive" to setOf("id", "price", "start", "bought_kwh", "since"),
        "thanks" to setOf("id")
    )
    private val PART_KEYS = setOf("battery", "car", "heat")
    private val INSTANT = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$""")

    /**
     * The answer, or `null` for one this app cannot read (another API version, a broken promise). With [strict] a key
     * or a page the app does not know is a broken promise too.
     */
    fun decode(json: JSONObject, strict: Boolean): YearReviewAnswer? {
        if (json.opt("api_version") != API_VERSION) return null
        return try {
            Strict(strict).answer(json)
        } catch (_: MalformedDocument) {
            null
        }
    }

    private class Strict(private val strict: Boolean) {
        private fun keys(json: JSONObject, allowed: Set<String>, what: String) {
            if (!strict) return
            for (key in json.keys()) if (key !in allowed) StrictJson.bad("$what.$key")
            for (key in allowed) if (!json.has(key)) StrictJson.bad("$what.$key")
        }

        private fun percentOrNull(raw: Any?, what: String): Double? =
            StrictJson.numberOrNull(raw, what)?.also { if (it < 0 || it > 100) StrictJson.bad(what) }

        private fun flagOrNull(raw: Any?, what: String): Boolean? = if (raw === JSONObject.NULL) null else StrictJson.flag(raw, what)

        fun answer(json: JSONObject): YearReviewAnswer = with(StrictJson) {
            keys(json, ANSWER_KEYS + (ENVELOPE_KEYS.filter { json.has(it) }), "answer")
            val state = when (text(json.opt("state"), "state")) {
                "ready" -> YearReviewAnswer.State.READY
                "computing" -> YearReviewAnswer.State.COMPUTING
                "none" -> YearReviewAnswer.State.NONE
                else -> bad("state")
            }
            val review = objOrNull(json.opt("review"), "review")?.let(::review)
            if ((state == YearReviewAnswer.State.READY) != (review != null)) bad("review")
            YearReviewAnswer(
                siteId = text(json.opt("site_id"), "site_id"),
                year = count(json.opt("year"), "year"),
                state = state,
                banner = flag(json.opt("banner"), "banner"),
                row = flag(json.opt("row"), "row"),
                review = review
            )
        }

        private fun review(json: JSONObject): YearReview = with(StrictJson) {
            keys(json, REVIEW_KEYS, "review")
            val money = objOrNull(json.opt("money"), "money")?.let { raw ->
                keys(raw, MONEY_KEYS, "money")
                YearReview.Money(
                    textOrNull(raw.opt("currency"), "currency"),
                    textOrNull(raw.opt("major_unit"), "major_unit"),
                    textOrNull(raw.opt("minor_unit"), "minor_unit")
                )
            }
            YearReview(
                year = count(json.opt("year"), "review.year"),
                through = day(json.opt("through"), "through"),
                since = dayOrNull(json.opt("since"), "since"),
                partial = flag(json.opt("partial"), "partial"),
                money = money,
                pages = list(json.opt("pages"), "pages").mapNotNull { page(obj(it, "page")) }
            )
        }

        private fun page(json: JSONObject): YearReviewPage? = with(StrictJson) {
            val id = json.opt("id") as? String ?: bad("page.id")
            val allowed = PAGE_KEYS[id] ?: return if (strict) bad("page $id") else null
            keys(json, allowed, id)
            when (id) {
                "cover" -> YearReviewPage.Cover
                "thanks" -> YearReviewPage.Thanks
                "saved" -> {
                    val starts = obj(json.opt("starts"), "starts")
                    keys(starts, PART_KEYS, "starts")
                    YearReviewPage.Saved(
                        total = number(json.opt("total"), "total"),
                        battery = numberOrNull(json.opt("battery"), "battery"),
                        car = numberOrNull(json.opt("car"), "car"),
                        heat = numberOrNull(json.opt("heat"), "heat"),
                        estimated = flag(json.opt("estimated"), "estimated"),
                        startsBattery = dayOrNull(starts.opt("battery"), "starts.battery"),
                        startsCar = dayOrNull(starts.opt("car"), "starts.car"),
                        startsHeat = dayOrNull(starts.opt("heat"), "starts.heat")
                    )
                }
                "car" -> {
                    val cheapest = objOrNull(json.opt("cheapest"), "cheapest")
                    cheapest?.let { keys(it, setOf("price", "date"), "cheapest") }
                    YearReviewPage.Car(
                        cheapSharePct = percentOrNull(json.opt("cheap_share_pct"), "cheap_share_pct"),
                        charges = count(json.opt("charges"), "charges"),
                        kwh = number(json.opt("kwh"), "kwh"),
                        cheapestPrice = cheapest?.let { number(it.opt("price"), "cheapest.price") },
                        cheapestDate = cheapest?.let { day(it.opt("date"), "cheapest.date") },
                        estimated = flag(json.opt("estimated"), "estimated"),
                        since = dayOrNull(json.opt("since"), "since")
                    )
                }
                "sun" -> YearReviewPage.Sun(
                    kwh = number(json.opt("kwh"), "kwh"),
                    homePct = percentOrNull(json.opt("home_pct"), "home_pct"),
                    // Added with the paid-export wording: an answer without it says nothing of selling.
                    exportPaid = additive(json, "export_paid", null) { flagOrNull(it, "export_paid") },
                    since = dayOrNull(json.opt("since"), "since")
                )
                "battery" -> YearReviewPage.Battery(
                    earned = numberOrNull(json.opt("earned"), "earned"),
                    earnedSince = dayOrNull(json.opt("earned_since"), "earned_since"),
                    saved = additive(json, "saved", null) { numberOrNull(it, "saved") },
                    savedSince = additive(json, "saved_since", null) { dayOrNull(it, "saved_since") },
                    cycles = numberOrNull(json.opt("cycles"), "cycles"),
                    buyPrice = numberOrNull(json.opt("buy_price"), "buy_price"),
                    usePrice = numberOrNull(json.opt("use_price"), "use_price"),
                    kwhIn = numberOrNull(json.opt("kwh_in"), "kwh_in"),
                    kwhOut = numberOrNull(json.opt("kwh_out"), "kwh_out")
                )
                "fuse" -> YearReviewPage.Fuse(count(json.opt("count"), "count"), dayOrNull(json.opt("since"), "since"))
                "expensive" -> {
                    val start = text(json.opt("start"), "start")
                    if (!INSTANT.matches(start)) bad("start")
                    YearReviewPage.Expensive(
                        price = number(json.opt("price"), "price"),
                        start = start,
                        boughtKwh = numberOrNull(json.opt("bought_kwh"), "bought_kwh"),
                        since = dayOrNull(json.opt("since"), "since")
                    )
                }
                else -> null
            }
        }
    }
}

/** `year_review_seen`: this phone opened the review of a year (the band goes for good) or closed its band. */
internal object YearReviewSeen {
    const val ACTION = "year_review_seen"

    enum class Seen(val key: String) { VIEWED("viewed"), DISMISSED("dismissed") }

    fun payload(appId: String, year: Int, seen: Seen): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", ACTION)
        put("api_version", YearReviewRead.API_VERSION)
        put("app_id", appId)
        put("year", year)
        put("seen_action", seen.key)
    }

    /** Whether Home Assistant kept it; the app keeps its own note either way. */
    fun accepted(status: Int?, body: String?): Boolean =
        status == 200 && WriteEnvelope.head(body)?.ok == true
}
