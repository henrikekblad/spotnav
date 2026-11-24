package se.sensnology.spotnav.ha.site

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.house.StrictJson

/**
 * The site's priority list (Home Assistant 3.0, `get_site_settings_view`'s `priority`): the loads the site has, top
 * first, the top gets energy first and gives way last. The app shows it read-only: the order is set in Home Assistant
 * (`writable` is true for an administrator's socket only). A row whose id the app does not know is skipped, as the
 * integration asks of a reader.
 */
internal data class SitePriority(val rows: List<Row>) {
    /** [kind] is the row's id without a charger's entry id: `heat`, `charger`, `spa`, ...; [name] a charger's title. */
    data class Row(val id: String, val kind: String, val name: String?)

    companion object {
        /** The ids a row may have; a charger's is `charger:<entry id>`. */
        val KINDS = listOf(
            "heat", "charger", "spa", "sauna", "cooling", "water_heater", "battery", "spa_idle", "pool_circulation", "pool_heat"
        )

        /** The row's kind, or `null` for an id the app does not know. */
        fun kindOf(id: String): String? = when {
            id.startsWith("charger:") && id.length > "charger:".length -> "charger"
            id in KINDS && id != "charger" -> id
            else -> null
        }
    }
}

internal object SitePriorityRead {
    fun payload(): JSONObject = JSONObject().put("version", 1).put("action", "get_site_settings_view")

    /**
     * The webhook's answer: the list, or [SiteRead.Read] of `null` where the view has no `priority` (a Home Assistant
     * before 3.0's list). A refusal or a block that is not the list's shape is [SiteRead.Failed].
     */
    fun answer(status: Int?, body: String?): SiteRead<SitePriority?> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The view itself (the webhook's and the WebSocket's alike). */
    fun decode(view: JSONObject): SiteRead<SitePriority?> = runCatching {
        SiteRead.Read(StrictJson.additive(view, "priority", null) { raw -> StrictJson.objOrNull(raw, "priority")?.let(::priority) })
    }.getOrElse { SiteRead.Failed(null) }

    private fun priority(json: JSONObject): SitePriority = with(StrictJson) {
        flag(json.opt("writable"), "priority.writable")
        SitePriority(
            list(json.opt("order"), "priority.order").mapNotNull { raw ->
                val row = obj(raw, "priority.row")
                val id = text(row.opt("id"), "priority.row.id")
                val name = textOrNull(row.opt("name"), "priority.row.name")
                SitePriority.kindOf(id)?.let { SitePriority.Row(id, it, name) }
            }
        )
    }
}
