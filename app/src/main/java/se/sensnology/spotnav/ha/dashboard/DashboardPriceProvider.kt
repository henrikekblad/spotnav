package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import java.net.URI

/**
 * The dashboard's `prices.provider` block (asked for with the `reads` value `price_provider`): where the area's
 * prices come from ([key] `nordpool`, `entsoe`, `pvpc`, `octopus` or `relay`; `null` when the area has no source at
 * all), and, while the area has no source of its own, the one thing an administrator can do about it in Home
 * Assistant ([notice]). `null` on the dashboard without an area, from an older Home Assistant, or for a block this
 * app cannot read.
 */
internal data class DashboardPriceProvider(
    val key: String?,
    val name: String?,
    /** The provider's address, kept only when it is http(s). */
    val url: String?,
    val notice: Notice?
) {
    /** A stable [code] and its plain [params] (`region` for `octopus_region_mismatch`). */
    data class Notice(val code: String, val params: Map<String, String>)

    companion object {
        private val KEYS = setOf("key", "name", "url", "notice")
        private val NOTICE_KEYS = setOf("code", "params")

        private class Unreadable : Exception()

        private fun bad(): Nothing = throw Unreadable()

        /**
         * The block read as the card reads it (`validate.ts:priceProviderOrNull`): exactly its keys, each of its
         * type. Anything else is `null`, as if absent (no source line, no notice); it never fails the dashboard.
         */
        fun parse(raw: Any?): DashboardPriceProvider? {
            val block = raw as? JSONObject ?: return null
            return try {
                exactKeys(block, KEYS)
                val notice = when (val value = block.opt("notice")) {
                    JSONObject.NULL -> null
                    is JSONObject -> notice(value)
                    else -> bad()
                }
                val name = textOrNull(block, "name")
                val url = textOrNull(block, "url")?.takeIf { name != null && isWebAddress(it) }
                DashboardPriceProvider(textOrNull(block, "key"), name, url, notice)
            } catch (_: Unreadable) {
                null
            }
        }

        private fun notice(item: JSONObject): Notice {
            exactKeys(item, NOTICE_KEYS)
            val code = item.opt("code") as? String ?: bad()
            val params = item.opt("params") as? JSONObject ?: bad()
            val values = params.keys().asSequence().associateWith { params.opt(it) as? String ?: bad() }
            return Notice(code, values)
        }

        private fun exactKeys(item: JSONObject, keys: Set<String>) {
            if (item.keys().asSequence().toSet() != keys) bad()
        }

        /** A string, or `null` for JSON `null`; any other type is unreadable. */
        private fun textOrNull(item: JSONObject, key: String): String? = when (val value = item.opt(key)) {
            JSONObject.NULL -> null
            is String -> value
            else -> bad()
        }

        private fun isWebAddress(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            return (scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()
        }
    }
}
