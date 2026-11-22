package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * A planned car charge that does not fit under the ceiling that acts now by its departure (Home Assistant 3.0.0b3; the
 * card's `does-not-fit.ts`): the same block sits in the charger dashboard as `does_not_fit` (asked for with the `reads`
 * value `does_not_fit`) and in the car's lane of `get_plan`. Each choice is for this departure only.
 *
 * Read tolerantly: a key the app does not know is ignored, a choice of a code it does not know is left out, and an
 * unknown `chosen` reads as nothing chosen; a missing or mistyped key it needs drops the block (no line, never half a
 * one).
 */
internal data class DoesNotFit(
    val departure: Instant?,
    /** Missing by the departure; `null` once the peak is accepted. */
    val shortKwh: Double?,
    /** What still arrives; `null` when the need is unknown or the peak is accepted. */
    val deliverableKwh: Double?,
    /** `import_target` or `power_level` (any other value is kept and worded by its kW alone). */
    val ceiling: String,
    /** The level holding this hour (above [setKw] when the month's peak has lifted it). */
    val ceilingKw: Double,
    /** The target (or power level) as set. */
    val setKw: Double,
    /** `takes_what_fits`, `accept_peak` or `null`. */
    val chosen: String?,
    /** The choices not yet chosen, in Home Assistant's order. */
    val choices: List<Choice>
) {
    data class Choice(
        val code: String,
        val kwh: Double?,
        val peakKw: Double?,
        /** What the peak adds to the month's demand charge, in the minor unit; `null` without one. */
        val cost: Double?,
        /** "Later departure": the departure it moves to; `null` where none fits (the departure editor is the way). */
        val departure: Instant?
    ) {
        /** "Later departure" with its time: pressing it moves the departure (without one it opens the editor). */
        val moves: Boolean get() = code == LATER && departure != null
    }

    companion object {
        const val TAKES_WHAT_FITS = "takes_what_fits"
        const val LATER = "later"
        const val ACCEPT_PEAK = "accept_peak"
        private val CODES = setOf(TAKES_WHAT_FITS, LATER, ACCEPT_PEAK)

        /** The status lines the "does not fit" line says in their place (the card's `DOES_NOT_FIT_SAYS`). */
        private val SAYS = setOf("import_target_at_risk", "import_target_released")

        /** The dashboard's status as the charger page words it: without the lines the "does not fit" line stands in for. */
        fun statusShown(dashboard: Dashboard): DashboardStatus =
            if (dashboard.doesNotFit == null) dashboard.status
            else dashboard.status.copy(lines = dashboard.status.lines.filter { it.code !in SAYS })

        /** The block, or `null` when it is absent, `null` or unreadable. */
        fun parse(raw: Any?): DoesNotFit? {
            val block = raw as? JSONObject ?: return null
            return runCatching { decode(block) }.getOrNull()
        }

        private fun decode(block: JSONObject): DoesNotFit {
            val ceilingKw = number(block.opt("ceiling_kw")) ?: error("ceiling_kw")
            val setKw = number(block.opt("set_kw")) ?: error("set_kw")
            val ceiling = block.opt("ceiling") as? String ?: error("ceiling")
            val choices = block.opt("choices") as? JSONArray ?: error("choices")
            return DoesNotFit(
                departure = instant(block.opt("departure")),
                shortKwh = number(block.opt("short_kwh")),
                deliverableKwh = number(block.opt("deliverable_kwh")),
                ceiling = ceiling,
                ceilingKw = ceilingKw,
                setKw = setKw,
                chosen = (block.opt("chosen") as? String)?.takeIf { it == TAKES_WHAT_FITS || it == ACCEPT_PEAK },
                choices = (0 until choices.length()).mapNotNull { index ->
                    val item = choices.opt(index) as? JSONObject ?: error("choice")
                    val code = item.opt("code") as? String ?: error("choice.code")
                    if (code !in CODES) return@mapNotNull null
                    Choice(code, number(item.opt("kwh")), number(item.opt("peak_kw")), number(item.opt("cost")), instant(item.opt("departure")))
                }
            )
        }

        private fun number(raw: Any?): Double? = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }

        private fun instant(raw: Any?): Instant? =
            (raw as? String)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: error("instant") }
    }
}
