package se.sensnology.spotnav.ui.prices

import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.AppThemeSettings
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.prices.ChargeCoverage
import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceRepository
import se.sensnology.spotnav.prices.PriceTableCell
import se.sensnology.spotnav.prices.PriceTableModel
import se.sensnology.spotnav.prices.PriceTableModels
import se.sensnology.spotnav.prices.PriceTableRow
import se.sensnology.spotnav.prices.PriceTableSubjects
import se.sensnology.spotnav.prices.stillCurrent
import se.sensnology.spotnav.ui.ScreenPart
import se.sensnology.spotnav.ui.ScreenShell
import se.sensnology.spotnav.ui.common.CHARGING_RAIL_DP
import se.sensnology.spotnav.ui.common.CHARGING_SEGMENT_GAP_DP
import se.sensnology.spotnav.ui.common.weight
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The price table: the day's prices, and nothing that can be edited. */
internal class PriceTableScreen(shell: ScreenShell) : ScreenPart(shell) {
    fun show() {
        beginScreen()
        // The panel's chrome from the first frame:
        showPanel(t(R.string.table))
        val generation = viewGeneration
        content.addView(ProgressBar(context).apply { isIndeterminate = true })
        // The launch subject, captured here on the screen's own thread from the authority the
        // widget screen already resolved:
        val stored = WidgetSettings.loadStored(context, widgetId) ?: WidgetSettings.load(context, widgetId)
        val subject = PriceTableSubjects.of(
            authority = authorityController?.authority,
            localArea = stored.area,
            localInputs = LocalPlanningInputs.ofOrNull(stored),
            profileId = stored.chargerProfileId,
            generation = generation,
            catalogue = PriceMarkets.all
        )
        ioExecutor.execute {
            val result = PriceRepository.load(applicationContext, subject.areaId)
            runOnUiThread {
                if (isDestroyed || generation != viewGeneration) return@runOnUiThread
                // A late answer for a market the authority has since left is inert: this pass
                // renders only its own subject's prices.
                if (!PriceTableSubjects.stillCurrent(subject, authorityController?.authority, stored.area, PriceMarkets.all)) {
                    return@runOnUiThread
                }
                content.removeAllViews()
                val market = PriceMarkets.find(subject.areaId)
                val unit = market?.appliedPriceUnit.orEmpty()
                // The title names the published interval of the prices shown, as the rows are drawn.
                val minutes = PriceAggregation.aggregate(result.today.ifEmpty { result.tomorrow }).firstOrNull()?.minutes
                val title = minutes?.let { t(R.string.table_title, subject.areaId, it, unit) }
                    ?: listOf(subject.areaId, unit).filter { it.isNotEmpty() }.joinToString(" · ")
                content.addView(TextView(context).apply { text = title; textSize = 19f; setTextColor(dark); setPadding(0, dp(8), 0, dp(12)) })
                val tableHeader = addTableHeader()
                scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    val offset = (scrollY - tableHeader.top).coerceAtLeast(0)
                    tableHeader.translationY = offset.toFloat()
                    tableHeader.elevation = if (offset > 0) dp(3).toFloat() else 0f
                }
                val inputs = subject.inputs
                val model = if (subject.market == null) {
                    // No complete market presentation: never understate a fiscal component or
                    // borrow the widget's local market for an authoritative record.
                    PriceTableModel(emptyList(), null, null, 0.0 to 0.0, 0.0 to 0.0)
                } else if (inputs == null) {
                    PriceTableModels.create(
                        result, subject.market,
                        OffsetDateTime.now(market?.zoneId ?: java.time.ZoneId.systemDefault())
                    )
                } else {
                    PriceTableModels.create(
                        result, inputs,
                        OffsetDateTime.now(market?.zoneId ?: java.time.ZoneId.systemDefault()),
                        authorityController?.handoverFor(inputs, result, subject.revision)
                    )
                }
                val rowViews = model.rows.map { row -> addRow(row, model, unit) }
                if (model.rows.isEmpty()) content.addView(TextView(context).apply { text = t(R.string.no_prices); setPadding(0, dp(20), 0, 0) })
                if (model.currentIndex >= 0) {
                    content.post {
                        // The row's own view, not an index into the content's children:
                        val row = rowViews.getOrNull(model.currentIndex) ?: return@post
                        scrollView.scrollTo(0, (row.top - scrollView.height / 3).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    private fun addTableHeader(): View {
        val header = rowView(
            t(R.string.time),
            TableCell(t(R.string.today), null, null, null),
            TableCell(t(R.string.tomorrow), null, null, null),
            bold = true, framed = false
        ).apply { setBackgroundColor(appBackground) }
        content.addView(header)
        return header
    }

    /**
     * One row: the wall-clock label it is displayed with, and then each day's own cell. The label
     * is display only -- every cell carries its own interval and its own coverage (see
     * `PriceTableModels`).
     */
    private fun addRow(row: PriceTableRow, model: PriceTableModel, unit: String): View {
        val view = rowView(
            row.position.time.format(DateTimeFormatter.ofPattern("HH:mm")),
            row.today?.let { cellSpec(it, model.todayAverage, model.todayRange, unit) },
            row.tomorrow?.let { cellSpec(it, model.tomorrowAverage, model.tomorrowRange, unit) },
            bold = row.current, framed = row.current
        )
        content.addView(view)
        return view
    }

    /** One price cell as the row builder needs it: text, price bar, charging, words. */
    private class TableCell(
        val text: String,
        val style: Pair<Boolean, Int>?,
        /** `null` in the header row, and whatever the plan covers in a price row. */
        val coverage: ChargeCoverage?,
        /** What a screen reader hears: the price with its unit, then its charging. */
        val description: String?
    )

    private fun cellSpec(cell: PriceTableCell, average: Double?, range: Pair<Double, Double>, unit: String): TableCell {
        val text = String.format(AppLanguageSettings.numberLocale(context), "%.2f", cell.price)
        return TableCell(
            text = text,
            style = cellStyle(cell.price, average ?: cell.price, range),
            coverage = cell.coverage,
            description = chargingDescription(cell.coverage)?.let { charging -> "$text $unit, $charging" }
        )
    }

    /**
     * an unmarked cell says nothing about charging at all, and a marked one says how much of it is
     * charged rather than implying the whole interval is.
     */
    private fun chargingDescription(coverage: ChargeCoverage?): String? = when {
        coverage == null || coverage.isEmpty -> null
        // One quarter per cell: nothing to be a fraction of.
        coverage.segmentCount == 1 -> t(R.string.table_charging_quarter)
        else -> tq(
            R.plurals.table_charging_quarters,
            coverage.selectedCount, coverage.selectedCount, coverage.segmentCount
        )
    }

    private fun cellStyle(value: Double, average: Double, range: Pair<Double, Double>): Pair<Boolean, Int> {
        val span = range.second - range.first
        val fraction = if (span <= 0.0) 1.0 else (value - range.first) / span
        return (value <= average) to (1200 + fraction.coerceIn(0.0, 1.0) * 8800).toInt()
    }

    private fun priceBackground(cheap: Boolean, level: Int) = LayerDrawable(arrayOf(
        ColorDrawable(if (AppThemeSettings.isDark(context)) {
            if (cheap) 0xFF17372C.toInt() else 0xFF482524.toInt()
        } else if (cheap) 0xFFDDF3E6.toInt() else 0xFFF9DEDC.toInt()),
        ClipDrawable(ColorDrawable(if (AppThemeSettings.isDark(context)) {
            if (cheap) 0xFF246348.toInt() else 0xFF7B3835.toInt()
        } else if (cheap) 0xFF9DDEB9.toInt() else 0xFFF2A29E.toInt()), Gravity.START, ClipDrawable.HORIZONTAL).apply {
            this.level = level
        }
    ))

    /** The row: the time label, then one price cell per day. */
    private fun rowView(time: String, today: TableCell?, tomorrow: TableCell?, bold: Boolean, framed: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = time
            textSize = 14f
            setTextColor(dark)
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(5), dp(7), dp(5))
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, weight())
        addView(cellView(today, TableColumn.TODAY), weight())
        addView(cellView(tomorrow, TableColumn.TOMORROW), weight())
        if (framed) foreground = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(dp(2), if (AppThemeSettings.isDark(context)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        }
    }

    /** One price cell: the left edge of today's column, the right edge of tomorrow's. */
    private fun cellView(cell: TableCell?, column: TableColumn) = FrameLayout(context).apply {
        addView(TextView(context).apply {
            text = cell?.text ?: "–"
            textSize = 14f
            setTextColor(dark)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(7), dp(5), dp(7), dp(5))
            cell?.style?.let { background = priceBackground(it.first, it.second) }
            // Only a marked cell says anything about charging, and it says the price too, because a
            // description replaces the text for a screen reader rather than adding to it.
            cell?.description?.let { contentDescription = it }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val coverage = cell?.coverage
        if (coverage != null && !coverage.isEmpty) {
            addView(chargeRail(coverage), FrameLayout.LayoutParams(
                dp(CHARGING_RAIL_DP), ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = if (column.markOnRightEdge) Gravity.END else Gravity.START })
        }
    }

    /** The charging mark: */
    private fun chargeRail(coverage: ChargeCoverage) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        TableMarker.segmentsTopDown(coverage).forEachIndexed { segment, selected ->
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(if (selected) accent else 0x00000000)
                    cornerRadius = dp(1).toFloat()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (segment > 0) topMargin = dp(CHARGING_SEGMENT_GAP_DP)
            })
        }
    }
}
