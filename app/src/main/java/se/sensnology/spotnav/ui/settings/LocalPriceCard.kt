package se.sensnology.spotnav.ui.settings

import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.entsoe.EntsoeKeyStore
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of an unpaired phone, drawn as the paired one is: the area, VAT,
 * energy tax and grid fee as value rows (see [PriceRows]), each changing that one value of this
 * phone's settings at once, through [store]. Under the area, the ENTSO-E key that prices it (see
 * [StandalonePrice]); a paired phone has no key, its Home Assistant holds one.
 */
internal class LocalPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var current: () -> WidgetSettings = { WidgetSettings() }
    private var store: (WidgetSettings) -> Unit = {}

    /** Runs after the key was saved or removed: the screens that show prices load them again. */
    var onKeyChanged: () -> Unit = {}

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
            areaControls = { body -> PriceSettingsCard(this).add(body, current(), null, true, areaOnly = true) },
            afterArea = { parent -> addKey(parent, settings.area) }
        ) { edit, done ->
            store(PriceEdits.local(current(), edit) { PriceMarkets.find(it) })
            done(null)
            show()
        }
    }

    /** The key row and the line under it, or only "This area needs Home Assistant" where no key can help. */
    private fun addKey(parent: LinearLayout, areaId: String) {
        val area = PriceMarkets.find(areaId)
        val state = EntsoeKeyStore.forContext(applicationContext).state()
        val line = when (StandalonePrice.line(area, state)) {
            StandalonePrice.Line.OWN_KEY -> t(R.string.entsoe_source_own_key)
            StandalonePrice.Line.NEEDS_HOME_ASSISTANT -> t(R.string.area_needs_home_assistant)
            null -> null
        }
        if (!StandalonePrice.keyRowShown(area)) {
            line?.let { parent.addView(helpLine(it)) }
            return
        }
        val value = when (StandalonePrice.keyValue(state)) {
            StandalonePrice.KeyValue.ADDED -> t(R.string.entsoe_key_added)
            StandalonePrice.KeyValue.MISSING -> t(R.string.setup_missing)
            StandalonePrice.KeyValue.NOT_ACCEPTED -> t(R.string.entsoe_key_not_accepted_value)
        }
        settingRow(parent, t(R.string.entsoe_key_row), value, help = line, onTap = {
            EntsoeKeyDialog(this).open(areaId) {
                show()
                onKeyChanged()
            }
        })
    }

    /** A muted line under a row, as a row's own help is drawn. */
    private fun helpLine(text: String) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(6))
    }
}
