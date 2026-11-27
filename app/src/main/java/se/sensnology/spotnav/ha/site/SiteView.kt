package se.sensnology.spotnav.ha.site

import org.json.JSONObject
import se.sensnology.spotnav.ha.client.WriteEnvelope
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.house.StrictJson

/**
 * "No export at negative prices" (Home Assistant 3.0, `get_site_settings_view`'s `export_curtail`): the site's switch
 * for its inverter's export limit, for a site with or without a battery. The row is shown only where [found] (a
 * brand's export limit SpotNav can run). An administrator switches it in Home Assistant (`spotnav/battery_control`,
 * never over a webhook), so the app shows it read-only. [need] is at most one thing the person can act on
 * (`control_disabled`, `limit_unset`, `not_obeyed`, `import_rose`, `not_taken`, `needs_hacs_integration:<repo>`,
 * `beta_only`, `no_grid_reading`); [saving] what exporting at a negative sell price cost in the last [Saving.days].
 */
internal data class ExportCurtail(
    val found: Boolean,
    val enabled: Boolean,
    val brand: String?,
    val state: String,
    val beta: Boolean,
    val need: String?,
    val saving: Saving
) {
    /** [minor] in the area's minor unit; the units `null` while no price area is known. */
    data class Saving(val days: Int, val kwh: Double, val minor: Double, val majorUnit: String?, val minorUnit: String?)
}

/** What the Settings screen reads of the site's settings view: the priority list and the export switch, each `null`
 * where the view has none (a Home Assistant before it, or a site not running). */
internal data class SiteView(val priority: SitePriority?, val exportCurtail: ExportCurtail?)

internal object SiteViewRead {
    fun payload(): JSONObject = SitePriorityRead.payload()

    /** The webhook's answer; a refusal, or a block that is not its shape, is [SiteRead.Failed]. */
    fun answer(status: Int?, body: String?): SiteRead<SiteView> {
        if (status == null) return SiteRead.Failed(null)
        val head = WriteEnvelope.head(body) ?: return SiteRead.Failed(null)
        if (!head.ok) return SiteRead.Failed(head.error)
        return decode(head.json)
    }

    /** The view itself (the webhook's and the WebSocket's alike). */
    fun decode(view: JSONObject): SiteRead<SiteView> {
        val priority = when (val read = SitePriorityRead.decode(view)) {
            is SiteRead.Read -> read.value
            else -> return SiteRead.Failed(null)
        }
        return runCatching { SiteRead.Read(SiteView(priority, exportCurtail(view))) }.getOrElse { SiteRead.Failed(null) }
    }

    /** The block, `null` where the view has none; absent from a Home Assistant before it. */
    private fun exportCurtail(view: JSONObject): ExportCurtail? = with(StrictJson) {
        additive(view, "export_curtail", null) { raw -> objOrNull(raw, "export_curtail")?.let(::block) }
    }

    private fun block(json: JSONObject): ExportCurtail = with(StrictJson) {
        val saving = obj(json.opt("saving"), "export_curtail.saving")
        ExportCurtail(
            found = flag(json.opt("found"), "export_curtail.found"),
            enabled = flag(json.opt("enabled"), "export_curtail.enabled"),
            brand = textOrNull(json.opt("brand"), "export_curtail.brand"),
            state = text(json.opt("state"), "export_curtail.state"),
            beta = flag(json.opt("beta"), "export_curtail.beta"),
            need = textOrNull(json.opt("need"), "export_curtail.need"),
            saving = ExportCurtail.Saving(
                days = count(saving.opt("days"), "export_curtail.saving.days"),
                kwh = number(saving.opt("kwh"), "export_curtail.saving.kwh"),
                minor = number(saving.opt("minor"), "export_curtail.saving.minor"),
                majorUnit = textOrNull(saving.opt("major_unit"), "export_curtail.saving.major_unit"),
                minorUnit = textOrNull(saving.opt("minor_unit"), "export_curtail.saving.minor_unit")
            )
        )
    }
}
