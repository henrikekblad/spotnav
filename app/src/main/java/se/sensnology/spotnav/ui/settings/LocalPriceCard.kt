package se.sensnology.spotnav.ui.settings

import android.widget.LinearLayout
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of an unpaired phone, drawn as the paired one is: the area, VAT,
 * energy tax and grid fee as value rows (see [PriceRows]), each changing that one value of this
 * phone's settings at once, through [store].
 */
internal class LocalPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var current: () -> WidgetSettings = { WidgetSettings() }
    private var store: (WidgetSettings) -> Unit = {}

    fun add(parent: LinearLayout, current: () -> WidgetSettings, store: (WidgetSettings) -> Unit) {
        this.current = current
        this.store = store
        parent.addView(rows)
        show()
    }

    /** Paint this phone's stored price settings. */
    fun show() {
        rows.removeAllViews()
        val settings = current()
        PriceRows(this).add(
            parent = rows,
            overview = PriceOverview.ofLocal(settings) { PriceMarkets.find(it) },
            areaId = settings.area,
            taxFigure = PriceRows.localFigure(settings.taxMinorUnit),
            transferFigure = PriceRows.localFigure(settings.gridFeeMinorUnit),
            editable = true,
            areaControls = { body -> PriceSettingsCard(this).add(body, current(), null, true, areaOnly = true) }
        ) { edit, done ->
            store(PriceEdits.local(current(), edit) { PriceMarkets.find(it) })
            done(null)
            show()
        }
    }
}
