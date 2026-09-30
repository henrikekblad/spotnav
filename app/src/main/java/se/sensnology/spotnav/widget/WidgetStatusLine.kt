package se.sensnology.spotnav.widget

import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardMarket
import se.sensnology.spotnav.ha.dashboard.DashboardStatus
import se.sensnology.spotnav.ha.dashboard.HaStatusText
import se.sensnology.spotnav.ha.dashboard.StatusFormat
import se.sensnology.spotnav.ha.dashboard.StatusTone
import java.time.Instant

/**
 * Home Assistant's `status` block of a stored dashboard, with the market's zone and money and the capture
 * time. A view derived from the stored dashboard ([WidgetDashboardStore]), never held on its own.
 */
internal data class StoredStatus(
    val status: DashboardStatus,
    val timezone: String?,
    val currency: String?,
    val majorUnit: String?,
    val capturedAt: Long
) {
    val format: (String, java.util.Locale?) -> StatusFormat = { language, locale ->
        StatusFormat.of(language, DashboardMarket(null, timezone, currency, majorUnit, null), locale)
    }

    companion object {
        fun of(dashboard: Dashboard, capturedAt: Long) = StoredStatus(
            status = dashboard.status,
            timezone = dashboard.market.timezone,
            currency = dashboard.market.currency,
            majorUnit = dashboard.market.majorUnit,
            capturedAt = capturedAt
        )
    }
}

/**
 * The widget's bottom line for a paired charger. It never waits for the network: it draws the last stored
 * status, adds its age once older than [STALE_AFTER_MS], and a background refresh replaces it.
 */
internal object WidgetStatusLine {
    /** A status older than this is drawn with its age. */
    const val STALE_AFTER_MS = 30 * 60 * 1000L

    /** A background render asks again only when older than this; a forced refresh always asks. */
    const val REFRESH_AFTER_MS = 5 * 60 * 1000L

    data class Line(val text: String, val tone: StatusTone)

    /** The headline alone, or `null` when nothing is stored: the widget stays compact, with no notes. */
    fun compose(stored: StoredStatus?, language: String, now: Instant, locale: java.util.Locale? = null): Line? {
        stored ?: return null
        val text = HaStatusText.render(stored.status, stored.format(language, locale), now) ?: return null
        val age = now.toEpochMilli() - stored.capturedAt
        return if (age > STALE_AFTER_MS) {
            Line("$text${HaStatusText.SEPARATOR}${HaStatusText.staleSuffix(language, age)}", stored.status.tone)
        } else Line(text, stored.status.tone)
    }

    fun refreshDue(stored: StoredStatus?, nowMs: Long, force: Boolean): Boolean =
        refreshDueAt(stored?.capturedAt, nowMs, force)

    /** As above, from the capture time of the held dashboard (`null` when none). */
    fun refreshDueAt(capturedAt: Long?, nowMs: Long, force: Boolean): Boolean =
        force || capturedAt == null || nowMs - capturedAt >= REFRESH_AFTER_MS || nowMs < capturedAt

    /**
     * [text] cut to fit [maxWidth]: whole ` · ` parts are dropped from the end first, and a first part that
     * is still too wide is ellipsized. [measure] is the caller's paint.
     */
    fun fit(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(text) <= maxWidth) return text
        val parts = text.split(HaStatusText.SEPARATOR)
        for (count in parts.size - 1 downTo 1) {
            val shorter = parts.take(count).joinToString(HaStatusText.SEPARATOR)
            if (measure(shorter) <= maxWidth) return shorter
        }
        var head = parts.first()
        while (head.isNotEmpty() && measure("$head…") > maxWidth) head = head.dropLast(1)
        return if (head.isEmpty()) "…" else "$head…"
    }
}
