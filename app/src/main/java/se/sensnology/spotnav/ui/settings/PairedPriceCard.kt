package se.sensnology.spotnav.ui.settings

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import org.json.JSONObject
import se.sensnology.spotnav.ha.client.GridTariffUpdate
import se.sensnology.spotnav.ha.client.PriceSourceUpdate
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.dashboard.DashboardPriceProvider
import se.sensnology.spotnav.ha.dashboard.DashboardPriceSource
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.NumberSpec
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords
import se.sensnology.spotnav.ui.common.editNumber
import se.sensnology.spotnav.ui.common.chooseOne
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of a paired charger: where the price lives (the electricity price Home Assistant's
 * charger uses, how many chargers share it, the same-area question and the "Price from" choice, see
 * [showSource]), then the confirmed area, VAT, energy tax and grid fee as value rows (see [PriceRows]), then the grid
 * tariff beside the grid fee as the card has it ([showTariff]: the grid fee as a percentage of spot, the high-load fee
 * and its hours, written with `update_grid_tariff`) and the demand charge's parts under them, then where
 * the area's prices come from and Home Assistant's notice when it has no source of its own ([showProvider]). Each of
 * those changes its one value through the paired settings path ([submit], the whole record with that one field
 * changed); the screen owns that path and says what came of it.
 */
internal class PairedPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val sourceRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tariffRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val demandRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val providerRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // The grid tariff: as the last dashboard stated it, or as a write here answered until the next one.
    private var tariff: DashboardGridTariff? = null
    private var tariffDashboard: Dashboard? = null
    private var writeTariff: (Int, JSONObject, (GridTariffUpdate.Outcome) -> Unit) -> Unit =
        { _, _, done -> done(GridTariffUpdate.Outcome.Failed(null)) }
    private var old: WidgetSettings? = null
    private var submit: (SettingsFormValues, ((HaPlanningSettings) -> SettingsFormValues)?, done: () -> Unit) -> Unit =
        { _, _, done -> done() }

    // The price source: as the last dashboard stated it, or as a write here answered until the next one.
    private class Shown(val source: DashboardPriceSource?, val chargerTotal: Int, val onSite: Boolean)
    private var shown: Shown? = null
    private var adopted: DashboardPriceSource? = null
    private var sourceNotice: String? = null
    private var removableNote = false
    private var answering = false
    private var choose: (String, (PriceSourceUpdate.Outcome) -> Unit) -> Unit =
        { _, done -> done(PriceSourceUpdate.Outcome.Failed(null)) }
    private var answer: (String, String?, (PriceSourceUpdate.Outcome) -> Unit) -> Unit =
        { _, _, done -> done(PriceSourceUpdate.Outcome.Failed(null)) }

    /**
     * Add the overview to [parent]; [submit] carries one write out (the values, and the same one value
     * on a newer record, should the charger's revision have moved) and calls `done` when it has answered.
     */
    fun add(
        parent: LinearLayout,
        old: WidgetSettings,
        submit: (SettingsFormValues, ((HaPlanningSettings) -> SettingsFormValues)?, done: () -> Unit) -> Unit
    ) {
        this.submit = submit
        this.old = old
        parent.addView(sourceRows)
        parent.addView(rows)
        parent.addView(tariffRows)
        parent.addView(demandRows)
        parent.addView(providerRows)
    }

    /** Where the price source's two writes go (the screen owns the connection and reads the dashboard again). */
    fun attachSourceWrites(
        choose: (String, (PriceSourceUpdate.Outcome) -> Unit) -> Unit,
        answer: (String, String?, (PriceSourceUpdate.Outcome) -> Unit) -> Unit
    ) {
        this.choose = choose
        this.answer = answer
    }

    /** Where the grid tariff's write goes (`update_grid_tariff`; the screen owns the connection and reads again). */
    fun attachTariffWrite(write: (Int, JSONObject, (GridTariffUpdate.Outcome) -> Unit) -> Unit) {
        writeTariff = write
    }

    /**
     * Paint the grid tariff of [dashboard] beside the grid fee, as the card does: the percentage of spot, the high-load
     * fee and, while that fee is on or hours are stored, its hours. Locked where the price already includes the grid
     * fee, and read-only where the tariff is not writable. Then the demand charge (Home Assistant 2.4): "Effektavgift:
     * Inget" until a part is added, then each part with its prices and this month's mean and peaks beneath, and a row
     * adding another (at most three). Nothing where Home Assistant sends no tariff.
     */
    fun showTariff(dashboard: Dashboard?) {
        tariffDashboard = dashboard
        tariff = dashboard?.gridTariff
        repaintTariff()
    }

    private fun tariffText() = GridTariffText(cardWords(), AppLanguageSettings.numberLocale(activity))

    private fun repaintTariff() {
        tariffRows.removeAllViews()
        repaintDemand()
        val block = tariff ?: return
        for (row in tariffText().rows(block)) {
            val edit: (() -> Unit)? = if (row.included || !block.writable) null else when (row.key) {
                GridTariffText.Key.PERCENT -> ({ editPercent() })
                GridTariffText.Key.HIGH, GridTariffText.Key.WINDOW -> ({ editHighLoad() })
            }
            settingRow(tariffRows, row.label, row.value, onTap = edit)
        }
    }

    /**
     * One change of the tariff: [change] builds the body on the tariff held when it is sent, so the other parts are kept
     * as stored; [done] hears `null` when it took, or what to say.
     */
    private fun writeTariffChange(change: (DashboardGridTariff) -> JSONObject, done: (String?) -> Unit) {
        val held = tariff ?: return done(t(R.string.paired_error_generic))
        val body = change(held)
        // The tariff as stored: nothing to write.
        if (body.toString() == held.storedBody().toString()) return done(null)
        writeTariff(held.writeRevision, body) { outcome ->
            when (outcome) {
                is GridTariffUpdate.Outcome.Updated -> {
                    outcome.block?.let { tariff = it }
                    done(null)
                    repaintTariff()
                }
                is GridTariffUpdate.Outcome.Conflict -> {
                    // Written elsewhere since this read: read again, and the next Save applies the change to what is stored now.
                    outcome.block?.let { tariff = it }
                    repaintTariff()
                    done(cardWords().say("gridFee.error.conflict"))
                }
                GridTariffUpdate.Outcome.Refused -> done(t(R.string.paired_error_invalid))
                GridTariffUpdate.Outcome.NotPermitted -> done(t(R.string.site_error_not_permitted))
                is GridTariffUpdate.Outcome.Failed -> done(t(R.string.paired_error_generic))
            }
        }
    }

    private fun repaintDemand() {
        demandRows.removeAllViews()
        val block = tariff ?: return
        val words = demandText(tariffDashboard)
        for (row in words.rows(block.demand)) {
            settingRow(demandRows, row.label, row.value, onTap = if (block.writable) ({ editDemand(row.index) }) else null)
            row.lines.forEach { demandRows.addView(demandLine(it)) }
        }
    }

    /** One part's editor ([index] `null` adds one): Save writes the tariff with it, Remove without it. */
    private fun editDemand(index: Int?) {
        val block = tariff ?: return
        val parts = block.demand?.components.orEmpty()
        val at = index ?: parts.size
        editDemandComponent(cardWords(), index?.let { parts.getOrNull(it) }, block.demand?.priceUnit) { part, done ->
            writeTariffChange({ it.bodyWith(at, part) }, done)
        }
    }

    /** The grid fee as a percentage of spot, 0-100 with two decimals, or off (its stored figure kept). */
    private fun editPercent() {
        val fee = tariff?.transferPercent ?: return
        val words = cardWords()
        editNumber(
            words.say("gridFee.percent"), NumberSpec(0.0, 100.0, 2), fee.unit ?: "%", fee.effectiveValue ?: fee.explicitValue,
            t(R.string.paired_error_number), help = words.say("gridFee.percent.help"),
            noneLabel = words.say("settings.value.off"), noneChecked = fee.effectiveValue == null
        ) { value, done -> writeTariffChange({ it.bodyWithPercent(value) }, done) }
    }

    /**
     * The grid fee in high-load hours and its hours, in one dialog, from either row: Save writes the fee and the hours in
     * one write, Remove turns the fee off and clears the hours in one write.
     */
    private fun editHighLoad() {
        val block = tariff ?: return
        editHighLoad(cardWords(), block.transferHigh, block.highLoadWindow) { choice, done ->
            writeTariffChange({ held ->
                if (choice == null) held.bodyWithoutHighLoad() else held.bodyWithHighLoad(choice.first, choice.second)
            }, done)
        }
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
            submit(PriceEdits.paired(confirmed, edit), { fresh -> PriceEdits.paired(fresh, edit) }) { done(null) }
        }
    }

    /**
     * Paint the dashboard's [source] (`null`: the charger prices with its own settings, or an older Home Assistant;
     * nothing is shown). [chargerTotal] is how many chargers there are, [onSite] whether this one is on a site.
     */
    fun showSource(source: DashboardPriceSource?, chargerTotal: Int, onSite: Boolean) {
        shown = Shown(source, chargerTotal, onSite)
        adopted = null
        repaintSource()
    }

    /**
     * Paint Home Assistant's `prices.provider`: the source line under the area ("Prices from Nord Pool (via Home
     * Assistant)"), and its notice with one action, opening Home Assistant at [homeAssistantUrl], where the fix is
     * made (design 5.4, O-A3). [area] is the area as the notice names it. `null`: nothing is shown.
     */
    fun showProvider(provider: DashboardPriceProvider?, area: String, homeAssistantUrl: String?) {
        providerRows.removeAllViews()
        HaPriceProviderText.line(provider)?.let { providerRows.addView(muted(t(it), top = 8)) }
        val notice = HaPriceProviderText.notice(provider, area) ?: return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(0x00000000)
                setStroke(dp(1), muted)
                cornerRadius = dp(8).toFloat()
            }
        }
        box.addView(TextView(context).apply {
            text = t(notice.text, *notice.args.toTypedArray()); textSize = 14f; setTextColor(dark)
        })
        val address = homeAssistantUrl?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (address != null) {
            box.addView(Button(context).apply {
                text = t(R.string.ha_price_open_home_assistant)
                isAllCaps = false
                setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) } }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        providerRows.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private val words = object : PriceSourceText.Words {
        override fun title(area: String) = t(R.string.price_source_title, area)
        override fun usedByAll(title: String, count: Int) = t(R.string.price_source_used_by_all, title, count.toString())
        override fun usedBy(title: String, count: Int) = t(R.string.price_source_used_by, title, count.toString())
        override fun here(title: String) = t(R.string.price_choice_here, title)
        override fun other(title: String) = t(R.string.price_choice_other, title)
        override fun off() = t(R.string.price_value_off)
        override fun included() = t(R.string.price_value_included)
        override fun unset() = t(R.string.value_not_set)
        override fun vat(value: String) = t(R.string.price_duplicate_fee_vat, value)
        override fun tax(value: String) = t(R.string.price_duplicate_fee_tax, value)
        override fun transfer(value: String) = t(R.string.price_duplicate_fee_transfer, value)
    }

    private fun muted(text: String, top: Int = 0, bottom: Int = 0) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, dp(top), 0, dp(bottom))
    }

    private fun repaintSource() {
        sourceRows.removeAllViews()
        val state = shown ?: return
        val source = adopted ?: state.source ?: return
        val count = source.chargerCount
        sourceRows.addView(muted(PriceSourceText.sourceLine(source.title, count, state.chargerTotal, words), top = 8, bottom = 2))
        if (count > 1) sourceRows.addView(muted(t(R.string.price_source_shared), bottom = 4))
        source.duplicate?.takeIf { source.writable }?.let { addDuplicateQuestion(it, source.entryId) }
        if (source.choices.size > 1) {
            val label = t(R.string.price_from_label)
            val labels = PriceSourceText.labels(source.choices.map { it.entryId to it.title }, source.entryId, words)
            settingRow(sourceRows, label, PriceSourceText.title(source.title, words), onTap = if (source.writable) ({
                val ids = source.choices.map { it.entryId }
                chooseOne(label, ids.map { labels[it] ?: it }, ids.indexOf(source.entryId),
                    intro = t(if (state.onSite) R.string.price_from_help_site else R.string.price_from_help)) { index, done ->
                    choose(ids[index]) { outcome -> settle(outcome, done) }
                }
            }) else null)
        }
        if (removableNote) sourceRows.addView(muted(t(R.string.price_duplicate_removable), top = 4))
        sourceNotice?.let { sourceRows.addView(muted(it, top = 4)) }
    }

    /**
     * "Two electricity prices for SE4 with different fees. Use one?": one button per entry, its fees under its name,
     * and one to keep them all. Answered once; nothing is removed.
     */
    private fun addDuplicateQuestion(duplicate: DashboardPriceSource.Duplicate, current: String) {
        val many = duplicate.entries.size > 2
        sourceRows.addView(muted(
            if (many) t(R.string.price_duplicate_question_many, duplicate.entries.size.toString(), duplicate.areaId)
            else t(R.string.price_duplicate_question, duplicate.areaId),
            top = 8, bottom = 4
        ).apply { setTextColor(dark); textSize = 14f })
        val labels = PriceSourceText.labels(duplicate.entries.map { it.entryId to it.title }, current, words)
        val locale = AppLanguageSettings.numberLocale(activity)
        val failure = muted("").apply { setTextColor(0xFFD65C5C.toInt()); visibility = android.view.View.GONE }
        val buttons = mutableListOf<Button>()
        fun button(text: String, keep: String?) = Button(context).apply {
            this.text = text
            isAllCaps = false
            isEnabled = !answering
            setOnClickListener {
                answering = true
                buttons.forEach { it.isEnabled = false }
                failure.visibility = android.view.View.GONE
                answer(duplicate.areaId, keep) { outcome ->
                    answering = false
                    if (outcome is PriceSourceUpdate.Outcome.Updated && keep != null) removableNote = true
                    settle(outcome) { message ->
                        if (message != null) {
                            failure.text = message
                            failure.visibility = android.view.View.VISIBLE
                            buttons.forEach { it.isEnabled = true }
                        }
                    }
                }
            }
        }.also { buttons += it; sourceRows.addView(it) }
        for (entry in duplicate.entries) {
            val name = t(R.string.price_duplicate_use, labels[entry.entryId] ?: entry.title)
            val fees = PriceSourceText.feesLine(entry.fees, locale, words)
            button(if (fees == null) name else "$name\n$fees", entry.entryId)
        }
        button(t(if (many) R.string.price_duplicate_keep_all else R.string.price_duplicate_keep_both), null)
        sourceRows.addView(failure)
    }

    /**
     * How one write's answer is taken: written (the block it answered shown until the next dashboard), changed
     * elsewhere first (said, and the screen reads again), or refused with the reason handed to [done].
     */
    private fun settle(outcome: PriceSourceUpdate.Outcome, done: (String?) -> Unit) {
        when (outcome) {
            is PriceSourceUpdate.Outcome.Updated -> {
                sourceNotice = null
                outcome.source?.let { adopted = it }
                done(null)
                repaintSource()
            }
            PriceSourceUpdate.Outcome.Stale -> {
                sourceNotice = t(R.string.site_error_conflict)
                done(null)
                repaintSource()
            }
            PriceSourceUpdate.Outcome.Refused -> done(t(R.string.paired_error_invalid))
            PriceSourceUpdate.Outcome.NotPermitted -> done(t(R.string.site_error_not_permitted))
            PriceSourceUpdate.Outcome.NotSupported -> done(t(R.string.paired_error_version))
            is PriceSourceUpdate.Outcome.Failed -> done(t(R.string.paired_error_generic))
        }
    }

    companion object {
        /** Whether the screen reads the dashboard again after [outcome]: whenever something may have changed. */
        fun reloads(outcome: PriceSourceUpdate.Outcome): Boolean =
            outcome is PriceSourceUpdate.Outcome.Updated || outcome == PriceSourceUpdate.Outcome.Stale
    }
}
