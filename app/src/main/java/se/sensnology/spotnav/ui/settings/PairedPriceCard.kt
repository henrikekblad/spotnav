package se.sensnology.spotnav.ui.settings

import android.widget.LinearLayout
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of a paired charger: the confirmed area, VAT, energy tax and grid fee
 * as value rows (see [PriceRows]). Each changes its one value through the paired settings path
 * ([submit], the whole record with that one field changed); the screen owns that path and says what
 * came of it.
 */
internal class PairedPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var old: WidgetSettings? = null
    private var submit: (SettingsFormValues, done: () -> Unit) -> Unit = { _, done -> done() }

    /** Add the overview to [parent]; [submit] carries one write out and calls `done` when it has answered. */
    fun add(
        parent: LinearLayout,
        old: WidgetSettings,
        submit: (SettingsFormValues, done: () -> Unit) -> Unit
    ) {
        this.submit = submit
        this.old = old
        parent.addView(rows)
    }

    /** Paint the confirmed [record]; its rows are editable only while the authority lets it be written. */
    fun show(record: HaPlanningSettings?, writable: Boolean) {
        rows.removeAllViews()
        PriceRows(this).add(
            parent = rows,
            overview = PriceOverview.of(record) { PriceMarkets.find(it) },
            areaId = record?.areaId,
            taxFigure = PriceRows.recordFigure(record, HaAreaOverrideComponent.TAX),
            transferFigure = PriceRows.recordFigure(record, HaAreaOverrideComponent.TRANSFER),
            editable = writable && record != null,
            areaControls = { body -> PriceSettingsCard(this).add(body, old ?: WidgetSettings(), record, writable, areaOnly = true) }
        ) { edit, done ->
            val confirmed = record ?: return@add done(null)
            submit(PriceEdits.paired(confirmed, edit)) { done(null) }
        }
    }
}
