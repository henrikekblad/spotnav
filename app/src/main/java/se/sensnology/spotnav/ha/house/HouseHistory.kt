package se.sensnology.spotnav.ha.house

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import java.time.LocalDate

/**
 * One day, month or year of the house (webhook `get_house_history`, the twin of `spotnav/get_house_history`,
 * `api_version` 1), as far as the Energy flow tab reads it: the period and the energy flow over it. The buckets, the
 * cost, the review and the rest are not read here. Shape: the integration's `site/house_history.py`; the card's
 * reader is `frontend/src/house-history.ts`.
 */
internal data class HouseHistory(
    val siteId: String,
    /** `day`, `month` or `year`. */
    val period: String,
    /** The first day of the period. */
    val date: LocalDate,
    /** The first day after it. */
    val end: LocalDate,
    /** The site's local date when the answer was made. */
    val today: LocalDate,
    /** `null` where the site has no meter to build it from. */
    val energyFlow: House.EnergyFlow?
) {
    /** The period holds today: it is still running. */
    val running: Boolean get() = today >= date && today < end
}

internal object HouseHistoryRead {
    const val API_VERSION = 1

    /** [date]: a day inside the period; `null` asks for the running one. */
    fun payload(period: String, date: LocalDate?): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "get_house_history")
        put("api_version", API_VERSION)
        put("period", period)
        date?.let { put("date", it.toString()) }
    }

    fun answer(status: Int?, body: String?): SiteRead<HouseHistory> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    fun decode(json: JSONObject): SiteRead<HouseHistory> {
        val version = json.opt("api_version")
        if (version != API_VERSION) return if (version is Number) SiteRead.Unsupported else SiteRead.Failed(null)
        return runCatching { SiteRead.Read(document(json)) }.getOrElse { SiteRead.Failed(null) }
    }

    private fun document(json: JSONObject): HouseHistory = with(StrictJson) {
        val period = text(json.opt("period"), "period")
        if (period !in PERIODS) bad("period")
        HouseHistory(
            siteId = text(json.opt("site_id"), "site_id"),
            period = period,
            date = day(json.opt("date"), "date"),
            end = day(json.opt("end"), "end"),
            today = day(json.opt("today"), "today"),
            energyFlow = objOrNull(json.opt("energy_flow"), "energy_flow")?.let(HouseRead::energyFlow)
        )
    }

    private val PERIODS = setOf("day", "month", "year")
}
