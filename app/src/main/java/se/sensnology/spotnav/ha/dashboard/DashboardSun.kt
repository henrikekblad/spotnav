package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** One hour of the sun: its mean power in kW, measured (before the running hour) or the plan's corrected forecast. */
internal data class SunHour(val start: Instant, val kw: Double, val measured: Boolean)

/** One measured quarter hour of today: its mean power in kW. */
internal data class SunQuarter(val start: Instant, val kw: Double)

/**
 * The dashboard's `sun` block (Home Assistant 3.0, asked for with the `reads` value `sun`): the sun's mean power per
 * hour of today and tomorrow in kW, the measured production before the running hour and the corrected forecast the
 * plan uses from it on. An hour with no figure is left out. [quarters] is today's measured power per quarter hour up
 * to now, finer than the measured hours it then replaces; empty where Home Assistant has none. [peakKw] is the site's
 * peak production the sun is scaled against, `null` where unknown.
 *
 * Read as the card's `sunOrNull` reads it, but for unknown keys, which are ignored: the keys it knows must be what the
 * contract says, and a block that is not is no block at all (the chart then draws no sun).
 */
internal data class DashboardSun(val hours: List<SunHour>, val quarters: List<SunQuarter>, val peakKw: Double?) {
    companion object {
        /** A day has at most this many measured quarter hours. */
        private const val MAX_QUARTERS = 96

        fun parse(raw: Any?): DashboardSun? {
            val block = raw as? JSONObject ?: return null
            return runCatching { decode(block) }.getOrNull()
        }

        private fun decode(block: JSONObject): DashboardSun {
            val peak = if (block.has("peak_kw")) number(block.opt("peak_kw")).also { require(it > 0.0) } else null
            val quarters = (if (block.has("quarters")) items(block.opt("quarters")) else emptyList()).map { item ->
                SunQuarter(instant(item.opt("start")), power(item.opt("kw")))
            }
            require(quarters.size <= MAX_QUARTERS)
            val hours = items(block.opt("hours")).map { item ->
                SunHour(instant(item.opt("start")), power(item.opt("kw")), item.opt("measured") as? Boolean ?: bad())
            }
            return DashboardSun(hours.sortedBy { it.start }, quarters.sortedBy { it.start }, peak)
        }

        private fun items(raw: Any?): List<JSONObject> {
            val list = raw as? JSONArray ?: bad()
            return (0 until list.length()).map { list.opt(it) as? JSONObject ?: bad() }
        }

        private fun number(raw: Any?): Double = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: bad()

        private fun power(raw: Any?): Double = number(raw).also { require(it >= 0.0) }

        private fun instant(raw: Any?): Instant = OffsetDateTime.parse(raw as? String ?: bad()).toInstant()

        private fun bad(): Nothing = throw IllegalArgumentException("not a sun block")
    }
}
