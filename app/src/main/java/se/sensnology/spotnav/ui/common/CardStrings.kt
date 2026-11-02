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
        "gridFee.window.sameHours" to R.string.card_grid_fee_window_same_hours,
        "demand.title" to R.string.card_demand_title,
        "demand.part" to R.string.card_demand_part,
        "demand.addPart" to R.string.card_demand_add_part,
        "demand.add" to R.string.card_demand_add,
        "demand.top.1" to R.string.card_demand_top_1,
        "demand.top.3" to R.string.card_demand_top_3,
        "demand.top.5" to R.string.card_demand_top_5,
        "demand.allHours" to R.string.card_demand_all_hours,
        "demand.value" to R.string.card_demand_value,
        "demand.prices" to R.string.card_demand_prices,
        "demand.month" to R.string.card_demand_month,
        "demand.month.none" to R.string.card_demand_month_none,
        "demand.reason.no_site" to R.string.card_demand_reason_no_site,
        "demand.reason.needs_total_grid_power" to R.string.card_demand_reason_needs_total_grid_power,
        "demand.reason.unknown" to R.string.card_demand_reason_unknown,
        "demand.help" to R.string.card_demand_help,
        "demand.counted" to R.string.card_demand_counted,
        "demand.counted.all" to R.string.card_demand_counted_all,
        "demand.counted.window" to R.string.card_demand_counted_window,
        "demand.topN" to R.string.card_demand_top_n,
        "demand.priceWinter" to R.string.card_demand_price_winter,
        "demand.priceSummer" to R.string.card_demand_price_summer,
        "demand.remove" to R.string.card_demand_remove,
        "demand.error.price" to R.string.card_demand_error_price,
        "site.importTarget" to R.string.card_site_import_target,
        "site.importTarget.help" to R.string.card_site_import_target_help,
        "site.importTarget.hour" to R.string.card_site_import_target_hour,
        "site.importTarget.noHour" to R.string.card_site_import_target_no_hour,
        "settings.value.none" to R.string.card_settings_value_none,
        "settings.value.off" to R.string.card_settings_value_off,
        "gridFee.error.conflict" to R.string.card_grid_fee_error_conflict,
        "history.view.label" to R.string.card_history_view_label,
        "history.view.charges" to R.string.card_history_view_charges,
        "history.view.power" to R.string.card_history_view_power,
        "power.intro" to R.string.card_power_intro,
        "power.loading" to R.string.card_power_loading,
        "power.failed" to R.string.card_power_failed,
        "power.empty" to R.string.card_power_empty,
        "power.chart.label" to R.string.card_power_chart_label,
        "power.dayChart.label" to R.string.card_power_day_chart_label,
        "power.hint.month" to R.string.card_power_hint_month,
        "power.hint.monthDaysOnly" to R.string.card_power_hint_month_days_only,
        "power.hint.day" to R.string.card_power_hint_day,
        "power.back" to R.string.card_power_back,
        "power.day" to R.string.card_power_day,
        "power.hour" to R.string.card_power_hour,
        "power.split" to R.string.card_power_split,
        "power.counted" to R.string.card_power_counted,
        "power.countedPart" to R.string.card_power_counted_part,
        "power.held" to R.string.card_power_held,
        "power.paused" to R.string.card_power_paused,
        "power.incomplete" to R.string.card_power_incomplete,
        "power.noData" to R.string.card_power_no_data,
        "power.noPeak" to R.string.card_power_no_peak,
        "power.part.day" to R.string.card_power_part_day,
        "power.part.dayInline" to R.string.card_power_part_day_inline,
        "power.part.night" to R.string.card_power_part_night,
        "power.part.nightInline" to R.string.card_power_part_night_inline,
        "power.part.all" to R.string.card_power_part_all,
        "power.part.allInline" to R.string.card_power_part_all_inline,
        "power.legend" to R.string.card_power_legend,
        "power.legend.house" to R.string.card_power_legend_house,
        "power.legend.car" to R.string.card_power_legend_car,
        "power.legend.unsplit" to R.string.card_power_legend_unsplit,
        "power.legend.target" to R.string.card_power_legend_target,
        "power.legend.countedPart" to R.string.card_power_legend_counted_part,
        "power.legend.marks" to R.string.card_power_legend_marks,
        "gridFee.window.help" to R.string.card_grid_fee_window_help,
        "gridFee.window.suggestion" to R.string.card_grid_fee_window_suggestion,
        "gridFee.window.first" to R.string.card_grid_fee_window_first,
        "gridFee.window.next" to R.string.card_grid_fee_window_next,
        "market.included" to R.string.card_market_included,
        "settings.value.unset" to R.string.card_settings_value_unset,
        "settings.error.range" to R.string.card_settings_error_range
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
