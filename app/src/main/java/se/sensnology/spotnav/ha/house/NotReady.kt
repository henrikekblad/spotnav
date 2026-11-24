package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import java.time.Instant

/**
 * What will not be ready in time across the site (Home Assistant 3.0, `get_plan`'s top-level `not_ready`; the card's
 * `not-ready-sheet.ts`, the backend's `site/not_ready.py`): the cars' departures under the ceiling, the spa's baths and
 * the sauna's sessions, with every choice ranked by how much it frees. `null` when everything fits.
 *
 * A car's [Item.loadId] is its charger's entry id (the plan's car lane id), a bath's `spa` and a session's `sauna`.
 */
internal data class NotReady(val items: List<Item>, val ranked: List<Ranked>) {
    data class Choice(
        /** `takes_what_fits`, `later` or `accept_peak`. */
        val code: String,
        val freesKwh: Double,
        /** A car's "take what fits": what it still gets. */
        val kwh: Double?,
        /** A bath's or a session's "take what fits": the temperature it reaches. */
        val reachC: Double?,
        /** "Later": the time it moves to; `null` where none fits. */
        val later: Instant?,
        val peakKw: Double?,
        /** What the peak adds to the month's demand charge, in the minor unit. */
        val cost: Double?
    )

    data class Item(
        val loadId: String,
        /** `car`, `spa` or `sauna`. */
        val kind: String,
        val name: String?,
        val deadline: Instant?,
        val shortKwh: Double?,
        val shortC: Double?,
        val reachC: Double?,
        /** `rate`, `import_target`, `power_level`, `fuse`, or `null`. */
        val cause: String?,
        /** `takes_what_fits`, `accept_peak` or `null` (still asking). */
        val chosen: String?,
        val choices: List<Choice>
    )

    /** One choice in the backend's rank; [loadId] `null` is "Allow the peak" for every load under the ceiling. */
    data class Ranked(val loadId: String?, val code: String, val freesKwh: Double)

    /** Whether a load in it still asks (a choice to make). */
    val asks: Boolean get() = ranked.isNotEmpty() && items.any { it.chosen == null }

    companion object {
        const val TAKES_WHAT_FITS = "takes_what_fits"
        const val LATER = "later"
        const val ACCEPT_PEAK = "accept_peak"
        val KINDS = setOf("car", "spa", "sauna")
        val CODES = setOf(TAKES_WHAT_FITS, LATER, ACCEPT_PEAK)
    }
}

internal object NotReadyRead {
    /**
     * The block, read by [StrictJson]'s rule: `null` stays `null`, and a block whose keys are not the promised shape,
     * or that names a load kind or a choice the app does not know, throws [MalformedDocument] (the card refuses such a
     * block too; [PlanRead] then shows the plan without the sheet).
     */
    fun decode(raw: Any?): NotReady? = with(StrictJson) {
        val block = objOrNull(raw, "not_ready") ?: return null
        fun oneOf(value: Any?, allowed: Set<String>, what: String): String = text(value, what).takeIf { it in allowed } ?: bad(what)
        NotReady(
            items = list(block.opt("items"), "not_ready.items").map { entry ->
                val item = obj(entry, "not_ready.item")
                NotReady.Item(
                    loadId = text(item.opt("load_id"), "item.load_id"),
                    kind = oneOf(item.opt("kind"), NotReady.KINDS, "item.kind"),
                    name = textOrNull(item.opt("name"), "item.name"),
                    deadline = instantOrNull(item.opt("deadline"), "item.deadline"),
                    shortKwh = numberOrNull(item.opt("short_kwh"), "item.short_kwh"),
                    shortC = numberOrNull(item.opt("short_c"), "item.short_c"),
                    reachC = numberOrNull(item.opt("reach_c"), "item.reach_c"),
                    cause = textOrNull(item.opt("cause"), "item.cause"),
                    chosen = if (item.opt("chosen") === JSONObject.NULL) null
                    else oneOf(item.opt("chosen"), setOf(NotReady.TAKES_WHAT_FITS, NotReady.ACCEPT_PEAK), "item.chosen"),
                    choices = list(item.opt("choices"), "item.choices").map { raw ->
                        val choice = obj(raw, "choice")
                        NotReady.Choice(
                            code = oneOf(choice.opt("code"), NotReady.CODES, "choice.code"),
                            freesKwh = numberOrNull(choice.opt("frees_kwh"), "choice.frees_kwh") ?: 0.0,
                            kwh = numberOrNull(choice.opt("kwh"), "choice.kwh"),
                            reachC = numberOrNull(choice.opt("reach_c"), "choice.reach_c"),
                            later = instantOrNull(choice.opt("later"), "choice.later"),
                            peakKw = numberOrNull(choice.opt("peak_kw"), "choice.peak_kw"),
                            cost = numberOrNull(choice.opt("cost"), "choice.cost")
                        )
                    }
                )
            },
            ranked = list(block.opt("ranked"), "not_ready.ranked").map { entry ->
                val ranked = obj(entry, "not_ready.ranked[]")
                NotReady.Ranked(
                    loadId = textOrNull(ranked.opt("load_id"), "ranked.load_id"),
                    code = oneOf(ranked.opt("code"), NotReady.CODES, "ranked.code"),
                    freesKwh = numberOrNull(ranked.opt("frees_kwh"), "ranked.frees_kwh") ?: 0.0
                )
            }
        )
    }

    /** The block, or `null` when it is absent, `null` or unreadable (no sheet, the plan still shown). */
    fun orNull(raw: Any?): NotReady? = runCatching { decode(raw) }.getOrNull()
}
