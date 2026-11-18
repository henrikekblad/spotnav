package se.sensnology.spotnav.ui.site

import android.content.Context
import se.sensnology.spotnav.app.KeyValueStore
import se.sensnology.spotnav.app.SharedPreferencesKeyValueStore

/**
 * What the house and heat pages remember on this phone, per site: the tab last shown on a page, the parts of a chart
 * hidden through its legend, and a page's switches (kWh or money). Display choices only, never a setting of Home
 * Assistant's. A store that throws or holds something unusable means nothing is remembered: the first tab, every
 * part shown, the switch's own default.
 */
internal class SitePreferences(private val store: KeyValueStore) {
    /** The tab last shown on [page] (`house`, `heat`) of [site], when it is one of [offered]. */
    fun tab(site: String, page: String, offered: Collection<String>): String? =
        read("tab.$page.$site")?.takeIf { it in offered }

    fun setTab(site: String, page: String, tab: String) = write("tab.$page.$site", tab)

    /** The parts of [chart] (`house.graph`, `house.earn`, `heat.use`) hidden through its legend. */
    fun hidden(site: String, chart: String): Set<String> =
        read("hidden.$chart.$site")?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    fun setHidden(site: String, chart: String, parts: Set<String>) {
        require(parts.none { ',' in it }) { "a part's name has no comma" }
        write("hidden.$chart.$site", parts.sorted().joinToString(","))
    }

    /** A switch of a page ([name] such as `heat.use.money`), [fallback] until it is set. */
    fun flag(site: String, name: String, fallback: Boolean): Boolean = when (read("flag.$name.$site")) {
        "1" -> true
        "0" -> false
        else -> fallback
    }

    fun setFlag(site: String, name: String, value: Boolean) = write("flag.$name.$site", if (value) "1" else "0")

    private fun read(key: String): String? = runCatching { store.getString(key) }.getOrNull()

    private fun write(key: String, value: String) {
        runCatching { store.putString(key, value) }
    }

    companion object {
        const val PREFS = "site_view"

        fun of(context: Context): SitePreferences = SitePreferences(SharedPreferencesKeyValueStore(context.applicationContext, PREFS))
    }
}
