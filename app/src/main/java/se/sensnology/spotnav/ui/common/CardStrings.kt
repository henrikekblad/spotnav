package se.sensnology.spotnav.ui.common

import se.sensnology.spotnav.R

/**
 * The Home Assistant card's own words this app shows as they are (`frontend/src/i18n`), by the card's key: each is a
 * string resource `card_<key>` in all nine languages, its placeholders kept as the card writes them (`{kw}`) and
 * filled by [CardWords]. Added with the card's key so the app says the same thing in the same words.
 */
internal object CardStrings {
    val IDS: Map<String, Int> = mapOf(
        "gridFee.percent" to R.string.card_grid_fee_percent,
        "gridFee.percent.help" to R.string.card_grid_fee_percent_help,
        "gridFee.high" to R.string.card_grid_fee_high,
        "gridFee.high.help" to R.string.card_grid_fee_high_help,
        "gridFee.window" to R.string.card_grid_fee_window,
        "gridFee.window.line" to R.string.card_grid_fee_window_line,
        "gridFee.window.weekdays" to R.string.card_grid_fee_window_weekdays,
        "gridFee.window.everyDay" to R.string.card_grid_fee_window_every_day,
        "gridFee.window.monSat" to R.string.card_grid_fee_window_mon_sat,
        "gridFee.window.allYear" to R.string.card_grid_fee_window_all_year,
        "gridFee.window.month.1" to R.string.card_grid_fee_window_month_1,
        "gridFee.window.month.2" to R.string.card_grid_fee_window_month_2,
        "gridFee.window.month.3" to R.string.card_grid_fee_window_month_3,
        "gridFee.window.month.4" to R.string.card_grid_fee_window_month_4,
        "gridFee.window.month.5" to R.string.card_grid_fee_window_month_5,
        "gridFee.window.month.6" to R.string.card_grid_fee_window_month_6,
        "gridFee.window.month.7" to R.string.card_grid_fee_window_month_7,
        "gridFee.window.month.8" to R.string.card_grid_fee_window_month_8,
        "gridFee.window.month.9" to R.string.card_grid_fee_window_month_9,
        "gridFee.window.month.10" to R.string.card_grid_fee_window_month_10,
        "gridFee.window.month.11" to R.string.card_grid_fee_window_month_11,
        "gridFee.window.month.12" to R.string.card_grid_fee_window_month_12,
        "gridFee.window.from" to R.string.card_grid_fee_window_from,
        "gridFee.window.to" to R.string.card_grid_fee_window_to,
        "gridFee.window.days" to R.string.card_grid_fee_window_days,
        "gridFee.window.fromMonth" to R.string.card_grid_fee_window_from_month,
        "gridFee.window.toMonth" to R.string.card_grid_fee_window_to_month,
        "gridFee.window.help" to R.string.card_grid_fee_window_help,
        "gridFee.window.suggestion" to R.string.card_grid_fee_window_suggestion,
        "gridFee.window.first" to R.string.card_grid_fee_window_first,
        "gridFee.window.next" to R.string.card_grid_fee_window_next,
        "gridFee.window.sameHours" to R.string.card_grid_fee_window_same_hours,
        "gridFee.error.conflict" to R.string.card_grid_fee_error_conflict,
        "market.included" to R.string.card_market_included,
        "settings.value.off" to R.string.card_settings_value_off,
        "settings.value.unset" to R.string.card_settings_value_unset
    )

    /** The resource name of the card's [key]: `demand.top.1` is `card_demand_top_1`, `site.importTarget` `card_site_import_target`. */
    fun resourceName(key: String): String =
        "card_" + key.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }.replace('.', '_')
}

/** The card's words in the reader's language, with the placeholders filled. */
internal fun interface CardWords {
    fun text(key: String, params: Map<String, String>): String

    fun say(key: String, vararg params: Pair<String, String>): String = text(key, params.toMap())
}

/** The card's words as this screen's language has them. */
internal fun ViewScope.cardWords(): CardWords = CardWords { key, params ->
    var text = CardStrings.IDS[key]?.let { template(it) } ?: key
    for ((name, value) in params) text = text.replace("{$name}", value)
    text
}
