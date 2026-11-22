package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import se.sensnology.spotnav.app.strictBoolean
import se.sensnology.spotnav.app.strictText
import se.sensnology.spotnav.ha.pairing.PairedSite
import se.sensnology.spotnav.ha.pairing.PairingProtocol
import se.sensnology.spotnav.ha.pairing.SiteCharger

/** What a home's webhook says it serves (`get_site`'s `capabilities`); a flag it does not state is false. */
data class SiteCapabilities(
    val getHouse: Boolean = false,
    val getHeat: Boolean = false,
    val batteryControl: Boolean = false,
    val getSiteSettingsView: Boolean = false,
    val updateSiteSettings: Boolean = false,
    val getPowerMonth: Boolean = false,
    val suggestImportTarget: Boolean = false,
    /** `get_plan` (3.0.0b3): the house page's Plan box and the "does not fit" writes. */
    val getPlan: Boolean = false
)

/** A home as its own webhook states it (`get_site`, Home Assistant 3.0). */
data class HomeSite(
    val siteId: String,
    val name: String,
    val loaded: Boolean,
    val capabilities: SiteCapabilities,
    val chargers: List<SiteCharger>
)

/** The electricity price a home uses, as its settings view states it (read-only from a home without a charger). */
data class HomePrice(val title: String?, val areaId: String?, val areaName: String?)

/**
 * The two reads of the home's door: `get_site` on the home's webhook, and `get_site_access` on a charger's own
 * webhook (how a phone paired before 3.0 moves to the home's webhook without pairing again). Pure: the client hands
 * over the status, the body and whether the webhook is gone.
 */
internal object SiteDoor {
    sealed interface GetSite {
        data class Read(val site: HomeSite) : GetSite

        /** The home is there but not loaded (503 `spotnav_site_unloaded`), or no answer at all: ask again later. */
        data object Later : GetSite

        /** Home Assistant no longer has this webhook (an empty 200, confirmed): pair again. */
        data object Gone : GetSite

        /** Any other answer: nothing to act on. */
        data class Failed(val error: String?) : GetSite
    }

    sealed interface Access {
        /** The charger's home: move to its webhook. */
        data class Granted(val site: PairedSite) : Access

        /** The charger is in no home (400 `spotnav_no_site`): keep the charger's own webhook. */
        data object NoSite : Access

        /** A Home Assistant before 3.0 (400 "Unsupported action", or any other refusal): keep the charger's webhook. */
        data object Unsupported : Access

        /**
         * Ask again later: rate-limited (429, [retryAfterS]), the home not loaded (503 `spotnav_site_unloaded`, never
         * "no home"), or no answer at all.
         */
        data class Later(val retryAfterS: Int? = null) : Access

        /** The charger's own webhook is gone (an empty 200, confirmed): pair again. */
        data object Gone : Access
    }

    const val SITE_UNLOADED = "spotnav_site_unloaded"
    const val NO_SITE = "spotnav_no_site"
    const val RATE_LIMITED = "spotnav_rate_limited"

    fun getSitePayload(): JSONObject = JSONObject().put("version", 1).put("action", "get_site").put("api_version", 1)

    fun accessPayload(): JSONObject = JSONObject().put("version", 1).put("action", "get_site_access").put("api_version", 1)

    fun settingsViewPayload(): JSONObject = JSONObject().put("version", 1).put("action", "get_site_settings_view")

    /** The price block of `get_site_settings_view`, or `null` when there is none to show. */
    fun homePrice(status: Int?, body: String?): HomePrice? {
        if (status != 200) return null
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (json.strictBoolean("ok") != true) return null
        val price = json.optJSONObject("price") ?: return null
        val area = price.strictText("area_id")
        return HomePrice(price.strictText("title"), area, price.strictText("area_name"))
            .takeIf { it.title != null || it.areaId != null }
    }

    fun getSite(status: Int?, body: String?, failure: Throwable?): GetSite {
        if (WebhookGone.isGone(failure)) return GetSite.Gone
        if (status == null) return GetSite.Later
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (status == 503) return GetSite.Later
        if (status != 200 || json == null) return GetSite.Failed(json?.strictText("error"))
        if (json.strictBoolean("ok") != true) return GetSite.Failed(json.strictText("error"))
        val siteId = json.strictText("site_id") ?: return GetSite.Failed(null)
        val flags = json.optJSONObject("capabilities")
        fun flag(key: String) = flags?.strictBoolean(key) == true
        return GetSite.Read(
            HomeSite(
                siteId = siteId,
                name = json.strictText("name") ?: siteId,
                loaded = json.strictBoolean("loaded") != false,
                capabilities = SiteCapabilities(
                    getHouse = flag("get_house"), getHeat = flag("get_heat"), batteryControl = flag("battery_control"),
                    getSiteSettingsView = flag("get_site_settings_view"), updateSiteSettings = flag("update_site_settings"),
                    getPowerMonth = flag("get_power_month"), suggestImportTarget = flag("suggest_import_target"),
                    getPlan = flag("get_plan")
                ),
                chargers = PairingProtocol.siteChargers(json.opt("chargers"))
            )
        )
    }

    fun access(status: Int?, body: String?, failure: Throwable?): Access {
        if (WebhookGone.isGone(failure)) return Access.Gone
        if (status == null) return Access.Later()
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val error = json?.strictText("error")
        return when {
            status == 200 && json?.strictBoolean("ok") == true ->
                (json.optJSONObject("site")?.let(PairingProtocol::site))?.let { Access.Granted(it) } ?: Access.Unsupported
            status == 429 || error == RATE_LIMITED -> Access.Later((json?.opt("retry_after_s") as? Number)?.toInt())
            status == 503 || error == SITE_UNLOADED -> Access.Later()
            status in 500..599 -> Access.Later()
            error == NO_SITE -> Access.NoSite
            else -> Access.Unsupported
        }
    }
}
