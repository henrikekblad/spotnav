package se.sensnology.spotnav.ui.settings

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.ha.client.PriceSourceUpdate
import se.sensnology.spotnav.ha.dashboard.DashboardPriceSource
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.SettingsFormValues
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.chooseOne
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.widget.WidgetSettings

/**
 * The "Electricity price" card of a paired charger: where the price lives (the electricity price Home Assistant's
 * charger uses, how many chargers share it, the same-area question and the "Price from" choice, see
 * [showSource]), then the confirmed area, VAT, energy tax and grid fee as value rows (see [PriceRows]). Each of
 * those changes its one value through the paired settings path ([submit], the whole record with that one field
 * changed); the screen owns that path and says what came of it.
 */
internal class PairedPriceCard(scope: ViewScope) : ViewScope(scope) {
    private val sourceRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
    }

    /** Where the price source's two writes go (the screen owns the connection and reads the dashboard again). */
    fun attachSourceWrites(
        choose: (String, (PriceSourceUpdate.Outcome) -> Unit) -> Unit,
        answer: (String, String?, (PriceSourceUpdate.Outcome) -> Unit) -> Unit
    ) {
        this.choose = choose
        this.answer = answer
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
