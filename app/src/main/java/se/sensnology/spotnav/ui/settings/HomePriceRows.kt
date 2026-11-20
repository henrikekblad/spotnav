package se.sensnology.spotnav.ui.settings

import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.client.HomePrice
import se.sensnology.spotnav.ui.common.ViewScope

/**
 * The electricity price of a home without a charger, in the price card: read-only (the beta writes neither the price
 * source nor the grid tariff over a home's webhook, `spotnav_not_permitted_over_webhook`), with where to change it.
 */
internal class HomePriceRows(scope: ViewScope, parent: LinearLayout) : ViewScope(scope) {
    private val price = TextView(context).apply {
        textSize = 15f
        setTextColor(dark)
        setPadding(0, dp(12), 0, dp(2))
    }

    init {
        parent.addView(price)
        parent.addView(TextView(context).apply {
            text = t(R.string.site_price_read_only)
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    fun show(home: HomePrice?) {
        price.text = home?.let { line(it) } ?: t(R.string.instance_unreachable)
    }

    companion object {
        /** "Electricity price SE4 · Malmö (SE4)", whatever of it is known. */
        fun line(home: HomePrice): String {
            val area = when {
                home.areaName != null && home.areaId != null && home.areaName != home.areaId -> "${home.areaName} (${home.areaId})"
                else -> home.areaName ?: home.areaId
            }
            return listOfNotNull(home.title, area).joinToString(" · ")
        }
    }
}
