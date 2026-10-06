package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.floor

/**
 * The dashboard's `progress` block (Home Assistant 1.12.1): how far a running charge has come, decided
 * once in Home Assistant so the card and this app draw the same bar.
 *
 * On [Dashboard], `null` means Home Assistant did not say (an older one without the field, or a block
 * this app cannot read): the app's own rules draw the bar then. [None] is Home Assistant's own `null`:
 * no bar.
 */
internal sealed interface DashboardProgress {
    /** Home Assistant sends the field and says no charge runs to show: no bar. */
    data object None : DashboardProgress

    /**
     * The bar as Home Assistant states it: [percent] within 0-100 (`null` only for [ProgressBasis.OPEN]),
     * the expected end, the power and whether current flows.
     */
    data class Bar(
        val basis: ProgressBasis,
        val percent: Int?,
        val endsAt: Instant?,
        val powerKw: Double?,
        val moving: Boolean
    ) : DashboardProgress

    companion object {
        /**
         * The block from the whole dashboard, read leniently: an unknown basis, a percent that is not a
         * number where one belongs, or a `moving` that is not a boolean is a block this app cannot read
         * (`null`); an end or a power it cannot read is simply none.
         */
        fun parse(dashboard: JSONObject): DashboardProgress? {
            if (!dashboard.has("progress")) return null
            if (dashboard.isNull("progress")) return None
            val block = dashboard.opt("progress") as? JSONObject ?: return null
            val basis = ProgressBasis.of(block.opt("basis")) ?: return null
            val moving = block.opt("moving") as? Boolean ?: return null
            val percent = if (basis == ProgressBasis.OPEN) {
                null
            } else {
                val number = (block.opt("percent") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return null
                floor(number).toInt().coerceIn(0, 100)
            }
            val endsAt = (block.opt("ends_at") as? String)
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            val powerKw = (block.opt("power_kw") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
            return Bar(basis, percent, endsAt, powerKw, moving)
        }
    }
}

/** What the `progress` block's percent counts (`basis`). */
internal enum class ProgressBasis(val wire: String) {
    TARGET("target"),
    ENERGY("energy"),
    VEHICLE_LIMIT("vehicle_limit"),
    OPEN("open");

    companion object {
        fun of(raw: Any?): ProgressBasis? = entries.firstOrNull { it.wire == raw }
    }
}
