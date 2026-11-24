package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.site.SitePriority
import se.sensnology.spotnav.ui.common.CardWords

/** The priority list in the card's words (`frontend/src/priority-list.ts`): "1. Husets värme", a charger by its title. */
internal object SitePriorityText {
    private val ROW_KEYS = mapOf(
        "heat" to "site.priority.row.heat",
        "charger" to "site.priority.row.charger",
        "spa" to "site.priority.row.spa",
        "sauna" to "site.priority.row.sauna",
        "cooling" to "site.priority.row.cooling",
        "water_heater" to "site.priority.row.waterHeater",
        "battery" to "site.priority.row.battery",
        "spa_idle" to "site.priority.row.spaIdle",
        "pool_circulation" to "site.priority.row.poolCirculation",
        "pool_heat" to "site.priority.row.poolHeat"
    )

    /** A row's name: a charger's own title, else the card's word for the load. */
    fun label(words: CardWords, row: SitePriority.Row): String =
        if (row.kind == "charger") row.name?.trim()?.takeIf { it.isNotEmpty() } ?: words.say("site.priority.row.charger")
        else words.say(ROW_KEYS.getValue(row.kind))

    /** The rows numbered from the top. */
    fun lines(words: CardWords, list: SitePriority): List<String> =
        list.rows.mapIndexed { index, row -> "${index + 1}. ${label(words, row)}" }
}
