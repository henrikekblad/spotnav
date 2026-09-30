package se.sensnology.spotnav.chart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.DistanceUnit
import se.sensnology.spotnav.ha.dashboard.StatusTone
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceAggregation
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetStatusLine
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

internal object ChartRenderer {
    /** One drawn graph and its geometry; [metrics] is `null` for the empty state, which has no plot to select on. */
    internal class ChartFrame(val bitmap: Bitmap, val metrics: ChartMetrics?)

    /** Opacity of the focus lines; solid versus dashed tells the kinds apart. */
    private const val LINE_ALPHA = 110

    /**
     * The widget's entry point: the bitmap only. [nowMinute] is the current interval's mark, read from the
     * clock by the caller; the widget has no selection. [theme] defaults to the dark panel.
     */
    fun render(
        context: Context,
        width: Int,
        height: Int,
        scaledDensity: Float,
        inputs: PlanningInputs,
        result: PriceResult,
        profile: ChartProfile = ChartProfile.WIDGET,
        showPlan: Boolean = true,
        nowMinute: Float? = null,
        theme: ChartTheme? = null
    ): Bitmap {
        // The only place a caller's plan is calculated for a bitmap; bands and footer come from one call.
        val chart = LocalCharts.of(inputs, result, showPlan)
        return renderFrame(
            context, width, height, scaledDensity, chart.market, result, chart.bands, chart.footer, profile,
            null, nowMinute, theme
        ).bitmap
    }

    /**
     * The empty-state placeholder frame. Public for a widget whose catalogue never resolved an area, which
     * has neither prices nor valid [PlanningInputs].
     */
    fun emptyState(context: Context, width: Int, height: Int, theme: ChartTheme? = null): Bitmap {
        val w = width.coerceIn(180, 1400)
        val h = height.coerceIn(100, 900)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val colors = theme ?: ChartTheme.Dark
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colors.surface
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, paint)
        drawPlaceholder(context, canvas, paint, w, h, colors)
        return bitmap
    }

    /**
     * A panel of words on the chart's surface, for a widget with no plan to draw. [title] is always drawn;
     * [details] follow for as long as the bitmap allows, so the least important are lost first. Takes no
     * `Context`: the words and their language are the caller's.
     */
    fun noticeState(
        width: Int,
        height: Int,
        title: String,
        details: List<String> = emptyList(),
        theme: ChartTheme? = null
    ): Bitmap {
        val w = width.coerceIn(180, 1400)
        val h = height.coerceIn(100, 900)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val colors = theme ?: ChartTheme.Dark
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colors.surface
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, paint)
        val scale = ChartLayout.scaleFor(w, h)
        val lineHeight = 16f * scale
        val lines = (listOf(title) + details).take((h / lineHeight).toInt().coerceAtLeast(1))
        var y = h / 2f - (lines.size - 1) * lineHeight / 2f + 4f * scale
        lines.forEachIndexed { index, line ->
            val isTitle = index == 0
            paint.typeface = if (isTitle) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            paint.textSize = (if (isTitle) 12f else 11f) * scale
            paint.color = if (isTitle) colors.text else colors.muted
            canvas.drawText(line, 14f * scale, y, paint)
            y += lineHeight
        }
        return bitmap
    }

    /** The one placeholder drawing, so the empty state and a dataless frame are one picture. */
    private fun drawPlaceholder(
        context: Context,
        canvas: Canvas,
        paint: Paint,
        w: Int,
        h: Int,
        colors: ChartTheme
    ) {
        val scale = ChartLayout.scaleFor(w, h)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = 12f * scale
        paint.color = colors.muted
        canvas.drawText(AppLanguageSettings.text(context, R.string.could_not_fetch), 14f * scale, h / 2f, paint)
    }

    /**
     * The one drawing algorithm: a market's prices and the shading it is handed. Owns the market
     * arithmetic, axes, marks, current marker, selection line and footer words, but no planner: [bands]
     * arrive already clipped, split and merged (see [ChartBand]).
     *
     * [nowMinute] is the current interval's mark, passed in because the renderer has no clock; `null`
     * means no line and no current figure. [selectionMinute] is the card's selection line (see
     * `ChartMetrics.xAt`); exactly one line is drawn, the selection if any, else now (see `ChartNow.line`).
     * [theme] `null` means [ChartTheme.Dark].
     */
    fun renderFrame(
        context: Context,
        width: Int,
        height: Int,
        scaledDensity: Float,
        market: ChartMarket,
        result: PriceResult,
        /** The shading, already normalized; empty means nothing is shaded. */
        bands: List<ChartBand>,
        /** The plan's facts for the footer words, or `null`. */
        footer: ChartFooterPlan? = null,
        profile: ChartProfile = ChartProfile.WIDGET,
        selectionMinute: Float? = null,
        nowMinute: Float? = null,
        theme: ChartTheme? = null,
        /** A ready-made footer line (a paired widget's status) drawn in place of the plan footer; the caller decides its words. */
        statusFooter: WidgetStatusLine.Line? = null
    ): ChartFrame {
        // The chart's surface is dark in both system themes, so the palette is not resolved from night mode.
        val colors = theme ?: ChartTheme.Dark
        val w = width.coerceIn(180, 1400)
        val h = height.coerceIn(100, 900)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = colors.surface
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, paint)
        val today = PriceAggregation.aggregate(result.today, market).map { it.first to market.apply(it.second) }
        val tomorrow = PriceAggregation.aggregate(result.tomorrow, market).map { it.first to market.apply(it.second) }
        val allValues = (today + tomorrow).map { it.second }
        if (allValues.isEmpty()) {
            // No prices: draw the placeholder; null metrics means no plot to select on.
            drawPlaceholder(context, canvas, paint, w, h, colors)
            return ChartFrame(bitmap, null)
        }

        val numbers = AppLanguageSettings.numberLocale(context)
        val area = PriceMarkets.find(market.areaId)
        // The current figure is the price of the mark [nowMinute] names, by the same anchor the line uses;
        // a `now` no row contains has no current figure.
        val current = nowMinute?.let { minute ->
            today.firstOrNull { ChartNow.markMinute(market, it.first) == minute }?.second
        }
        val dayMax = today.maxOfOrNull { it.second }
        val dayMin = today.minOfOrNull { it.second }

        val maxText = dayMax?.let { String.format(numbers, "↑%.1f", it) } ?: "↑–"
        val minText = dayMin?.let { String.format(numbers, "↓%.1f", it) } ?: "↓–"
        val priceUnit = area?.appliedPriceUnit.orEmpty()
        val currentText = current?.let { String.format(numbers, "%.1f %s", it, priceUnit) } ?: "– $priceUnit"
        // The profile decides how much canvas the text may take.
        val metrics = ChartLayout.metrics(profile, w, h, scaledDensity, bands.isNotEmpty() || statusFooter != null) { textSize ->
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = textSize
            paint.measureText(maxText) + paint.measureText(minText) + paint.measureText(currentText)
        }
        val headerSize = metrics.headerTextSize
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = headerSize
        paint.color = colors.expensive
        canvas.drawText(maxText, metrics.pad, metrics.pad + headerSize, paint)
        paint.color = colors.cheap
        val minX = metrics.pad + paint.measureText(maxText) + metrics.pad * 1.4f
        canvas.drawText(minText, minX, metrics.pad + headerSize, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = headerSize
        paint.color = colors.text
        canvas.drawText(currentText, w - metrics.pad, metrics.pad + headerSize, paint)
        paint.textAlign = Paint.Align.LEFT

        val axisText = metrics.axisTextSize
        val scale = metrics.scale
        val top = metrics.top
        val bottom = metrics.bottom
        val left = metrics.left
        val right = metrics.right
        val pad = metrics.pad
        val minValue = min(0.0, allValues.minOrNull() ?: 0.0)
        val maxValue = max(1.0, allValues.maxOrNull() ?: 1.0)
        val range = (maxValue - minValue).coerceAtLeast(1.0)
        fun y(value: Double) = bottom - ((value - minValue) / range * (bottom - top)).toFloat()
        // The one time-to-pixel mapping: marks, shading and focus line read it and the hit test inverts it.
        fun anchor(time: OffsetDateTime) = metrics.xAt(ChartNow.markMinute(market, time))

        if (bands.isNotEmpty()) {
            paint.color = colors.band
            paint.alpha = 38
            // Bands arrive clipped, split and merged; one rectangle each.
            bands.forEach { band ->
                canvas.drawRect(metrics.xAt(band.fromMinute), top, metrics.xAt(band.toMinute), bottom, paint)
            }
            paint.alpha = 255
        }

        paint.strokeWidth = 1f
        for (i in 0..2) {
            val gy = top + i * (bottom - top) / 2f
            paint.color = colors.grid
            canvas.drawLine(left, gy, right, gy, paint)
            paint.color = colors.muted
            paint.textSize = axisText
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format(numbers, "%.0f", maxValue - i * range / 2), left - axisText * .45f, gy + axisText * .35f, paint)
        }
        paint.textAlign = Paint.Align.LEFT

        // Paint order is the contract: bands and grid under the focus line, marks over it. Exactly one
        // line is drawn: a selection hides the now line (see `ChartNow.line`).
        ChartNow.line(nowMinute, selectionMinute)?.let { line ->
            val lineX = metrics.xAt(line.minute)
            val selection = line.kind == ChartLineKind.SELECTION
            paint.color = if (selection) colors.selectionLine else colors.nowLine
            paint.alpha = LINE_ALPHA
            paint.strokeWidth = max(1.5f, 1.4f * scale)
            paint.pathEffect = if (selection) {
                val dash = max(3f, 3f * scale)
                DashPathEffect(floatArrayOf(dash, dash), 0f)
            } else null
            canvas.drawLine(lineX, top, lineX, bottom, paint)
            paint.pathEffect = null
            paint.alpha = 255
        }

        // Tomorrow is drawn first as a neutral comparison, with no current mark: the current interval is today's.
        val hourWidth = (right - left) / 24f
        drawTomorrow(canvas, paint, tomorrow, ::anchor, ::y, scale, market, hourWidth, colors)
        drawToday(canvas, paint, today, ::anchor, ::y, scale, market, nowMinute, hourWidth, colors)

        paint.textSize = axisText
        paint.color = colors.muted
        listOf(0 to "00", 6 to "06", 12 to "12", 18 to "18", 24 to "24").forEach { (hour, label) ->
            val px = left + hour / 24f * (right - left)
            paint.textAlign = when (hour) { 0 -> Paint.Align.LEFT; 24 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(label, px, bottom + axisText * 1.15f, paint)
        }
        paint.textAlign = Paint.Align.LEFT
        // The plan-card profile drops the footer: the card states the same words below the graph.
        if (profile.footer && statusFooter != null) {
            paint.color = if (statusFooter.tone == StatusTone.BLOCKING) colors.expensive else colors.text
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = max(axisText, 13f * scaledDensity)
            paint.textAlign = Paint.Align.CENTER
            val fitted = WidgetStatusLine.fit(statusFooter.text, right - left) { paint.measureText(it) }
            canvas.drawText(fitted, (left + right) / 2f, h - pad * .7f, paint)
            paint.textAlign = Paint.Align.LEFT
        } else if (profile.footer) footer?.let { plan ->
            paint.color = colors.text
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = max(axisText, 13f * scaledDensity)
            paint.textAlign = Paint.Align.CENTER
            val locale = AppLanguageSettings.locale(context)
            val day = plan.start.format(DateTimeFormatter.ofPattern("EEE", locale))
            val start = plan.start.format(DateTimeFormatter.ofPattern("HH:mm"))
            val end = plan.end.format(DateTimeFormatter.ofPattern(
                if (plan.start.toLocalDate() == plan.end.toLocalDate()) "HH:mm" else "EEE HH:mm",
                locale
            ))
            val unpricedPrefix = if (plan.unpriced) "${AppLanguageSettings.text(context, R.string.unpriced)} · " else ""
            // One period is named by day and times; more than one is a plural count, and the quantity is
            // passed twice because `getQuantityString` uses it only to choose the form.
            val timeText = if (plan.periodCount == 1) "$day $start–$end"
                else AppLanguageSettings.quantityText(
                    context, R.plurals.charging_period_count, plan.periodCount, plan.periodCount
                )
            val chargingText = "$unpricedPrefix${AppLanguageSettings.text(context, R.string.charge)} $timeText · ${String.format(numbers, "%.1f kWh", plan.energyKwh)}"
            val rangeText = DistanceUnit.text(plan.distanceMil, AppLanguageSettings.language(context), numbers)
            val chargingTextWithRange = "$chargingText · $rangeText"
            val footerText = if (paint.measureText(chargingTextWithRange) <= right - left) chargingTextWithRange else chargingText
            canvas.drawText(footerText, (left + right) / 2f, h - pad * .7f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        return ChartFrame(bitmap, metrics)
    }

    private fun drawTomorrow(
        canvas: Canvas,
        paint: Paint,
        values: List<Pair<OffsetDateTime, Double>>,
        anchor: (OffsetDateTime) -> Float,
        y: (Double) -> Float,
        scale: Float,
        market: ChartMarket,
        hourWidth: Float,
        colors: ChartTheme
    ) {
        paint.style = Paint.Style.FILL; paint.color = colors.tomorrow; paint.alpha = 190
        val mark = ChartMarks.geometry(market, ChartMarks.tomorrowRadius(canvas.height, scale), hourWidth)
        values.forEach { item ->
            if (mark.kind == ChartMarkKind.SEGMENT) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = mark.thickness
                val center = anchor(item.first)
                canvas.drawLine(center - mark.halfLength, y(item.second), center + mark.halfLength, y(item.second), paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
            } else {
                canvas.drawCircle(anchor(item.first), y(item.second), mark.radius, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    /**
     * Today's marks from [ChartMarks.plan]: one primitive per value, none resized for being current
     * (currentness is the solid now line). The geometry has no `now` parameter, so a test can compare plans
     * under different `now` values.
     */
    private fun drawToday(canvas: Canvas, paint: Paint, values: List<Pair<OffsetDateTime, Double>>,
                          anchor: (OffsetDateTime) -> Float, y: (Double) -> Float, scale: Float,
                          market: ChartMarket, nowMinute: Float?, hourWidth: Float, colors: ChartTheme) {
        if (values.isEmpty()) return
        val plan = ChartMarks.plan(
            market = market,
            values = values,
            radius = ChartMarks.todayRadius(canvas.height, scale),
            hourWidth = hourWidth,
            nowMinute = nowMinute,
            mean = values.map { it.second }.average(),
            cheap = colors.cheap,
            expensive = colors.expensive
        )
        plan.forEach { draw ->
            val x = anchor(draw.start)
            paint.style = Paint.Style.FILL
            paint.color = draw.colour
            paint.alpha = draw.alpha
            if (draw.mark.kind == ChartMarkKind.SEGMENT) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = draw.mark.thickness
                canvas.drawLine(x - draw.mark.halfLength, y(draw.value), x + draw.mark.halfLength, y(draw.value), paint)
                paint.strokeCap = Paint.Cap.BUTT
            } else {
                canvas.drawCircle(x, y(draw.value), draw.mark.radius, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }
}
