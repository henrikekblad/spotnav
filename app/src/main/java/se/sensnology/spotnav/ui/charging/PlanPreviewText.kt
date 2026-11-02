package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.ha.client.PlanPreview
import se.sensnology.spotnav.ha.dashboard.HaStatusText
import se.sensnology.spotnav.ha.dashboard.HaStatusWording
import se.sensnology.spotnav.ha.dashboard.StatusFormat
import se.sensnology.spotnav.ha.dashboard.StatusLine
import se.sensnology.spotnav.ha.dashboard.StatusTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the Plan card says while a slider is held: the plan Home Assistant would make if it were let go, in the card's
 * words (`plan-preview.ts`' `previewLines` and `previewStripModel`). Pure: the view only draws what this returns.
 */
internal object PlanPreviewText {
    /** One of the plan's own status lines, worded, and whether it warns. */
    data class Note(val text: String, val warning: Boolean)

    /** `03:30–05:15 · 18.5 kWh · 72.40 kr · klar 05:15` ([plan] is `null` without a plan), and the notes under it. */
    data class Lines(val plan: String?, val notes: List<Note>)

    /** The most periods named in full; the rest are counted ("+2"). */
    private const val PERIODS_SHOWN = 2

    fun lines(preview: PlanPreview.Preview, format: StatusFormat, now: Instant): Lines {
        val notes = preview.status.lines
            .filter { it.code != "settings_suggested" }
            .map { line ->
                Note(
                    HaStatusText.line(line, format, now).replace(Regex("[.。]$"), ""),
                    warning = preview.status.tone != StatusTone.NORMAL
                )
            }
        val plan = preview.plan
        val zone = format.zone
        if (plan == null || zone == null) return Lines(null, notes)
        val times = Times(zone, now, format)
        val parts = mutableListOf<String>()
        val shown = plan.periods.take(PERIODS_SHOWN).map { "${times.at(it.start)}–${times.at(it.end)}" }
        val more = plan.periods.size - shown.size
        parts += if (more > 0) "${shown.joinToString(", ")} +$more" else shown.joinToString(", ")
        parts += HaStatusText.line(StatusLine("plan_energy", mapOf("kwh" to plan.plannedKwh)), format, now)
        plan.cost?.let { cost ->
            parts += HaStatusText.line(
                StatusLine("plan_cost", mapOf("amount_minor" to cost * 100, "currency" to plan.currency)), format, now
            )
        }
        val finish = plan.finish
        val shortfall = preview.status.lines.firstOrNull { it.code == "departure_shortfall" }
            ?.params?.get("departure") as? String
        val shortfallAt = shortfall?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        if (finish != null && (shortfallAt == null || times.clock(shortfallAt) != times.clock(finish))) {
            parts += HaStatusWording.text(format.language, "settings.preview.ready").orEmpty()
                .replace("{time}", times.at(finish))
        }
        return Lines(parts.joinToString(HaStatusText.SEPARATOR), notes)
    }

    /**
     * Times along one line, each with its day only when the line has not named that day yet (today goes without).
     */
    private class Times(private val zone: ZoneId, now: Instant, private val format: StatusFormat) {
        private var named = now.atZone(zone).toLocalDate()
        private val clock = DateTimeFormatter.ofPattern("HH:mm")
        private val day = DateTimeFormatter.ofPattern("EEE d", format.locale)

        fun clock(instant: Instant): String = clock.format(instant.atZone(zone))

        fun at(instant: Instant): String {
            val date = instant.atZone(zone).toLocalDate()
            if (date == named) return clock(instant)
            named = date
            return "${day.format(instant.atZone(zone))} ${clock(instant)}"
        }
    }

    /** One quarter of the strip: shares of its width, whether it is planned, and cheap or dear against its own day's mean. */
    data class Cell(val left: Float, val width: Float, val planned: Boolean, val cheap: Boolean?)

    /** The strip's geometry: one cell per quarter inside it, and the start and end as times. */
    data class Strip(val cells: List<Cell>, val startLabel: String, val endLabel: String)

    private const val QUARTER_S = 15 * 60L

    fun strip(strip: PlanPreview.Strip, zone: ZoneId?): Strip? {
        val span = (strip.end.epochSecond - strip.start.epochSecond).toFloat()
        if (span <= 0f || zone == null) return null
        val means = strip.quarters.groupBy { it.start.atZone(zone).toLocalDate() }
            .mapValues { (_, quarters) -> quarters.map { it.price }.average() }
        val cells = strip.quarters
            .filter { it.start >= strip.start && it.start < strip.end }
            .map { quarter ->
                val mean = means[quarter.start.atZone(zone).toLocalDate()]
                Cell(
                    left = (quarter.start.epochSecond - strip.start.epochSecond) / span,
                    width = minOf(QUARTER_S, strip.end.epochSecond - quarter.start.epochSecond) / span,
                    planned = quarter.planned,
                    cheap = mean?.let { quarter.price <= it }
                )
            }
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        return Strip(cells, clock.format(strip.start.atZone(zone)), clock.format(strip.end.atZone(zone)))
    }
}
