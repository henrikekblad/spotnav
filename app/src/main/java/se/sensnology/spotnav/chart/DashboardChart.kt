package se.sensnology.spotnav.chart

import se.sensnology.spotnav.app.MoneyText
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardMarket
import se.sensnology.spotnav.ha.dashboard.DashboardPriceInterval
import se.sensnology.spotnav.ha.dashboard.DashboardPrices
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.FiscalInput
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Home Assistant's plan as the paired plan card shows it: periods, cost, energy and distance, read from
 * the dashboard's `plan`, never calculated here. Figures come from the proposal; with only an installed
 * schedule the periods are shown and the figures are left blank.
 */
internal data class PairedPlanFigures(
    val periods: List<ChargingPeriod>,
    val fromProposal: Boolean,
    val costMajor: Double?,
    val costCurrency: String?,
    /** The label beside the cost: the market's major unit or the ISO code, see [DashboardChart.moneyUnit]. */
    val costUnit: String?,
    val unpriced: Boolean,
    val unpricedSlots: Int,
    val energyKwh: Double?,
    val distanceMil: Double?,
    /** Whether the distance is written in miles: the market is in Great Britain. */
    val miles: Boolean = false
)

/** The chart a paired charger draws: Home Assistant's own prices and plan as the renderer's types. */
internal data class PairedChart(
    val market: ChartMarket,
    /** The dashboard's intervals as price days, already all-in (see [DashboardChart.prices]). */
    val prices: PriceResult,
    val bands: List<ChartBand>,
    val footer: ChartFooterPlan?,
    val figures: PairedPlanFigures?
)

/**
 * The dashboard's `prices` and `plan` as a chart and figures.
 *
 * `effective_price` already has every fiscal component applied, so the market is built with
 * [FiscalInput.OFF] and each price is handed over as `effective / 100` "major" so [ChartMarket.apply]
 * (`major x 100`) returns the stated number. Intervals with no effective price are left out (a gap, never
 * a zero). Bands shade the proposal's periods, else the installed schedule's; the footer needs every fact
 * of the proposal.
 */
internal object DashboardChart {
    /**
     * The renderer's price days, cut by the local clock: the intervals on [now]'s date in the market's
     * zone are today, later dates are tomorrow, earlier ones are dropped. The answer's own `today` and
     * generation time are deliberately not consulted: a stored answer read the next morning must not
     * keep drawing the day it was composed on. `null` when nothing priced remains.
     */
    fun prices(prices: DashboardPrices, zone: ZoneId, fetchedAt: Long, now: Instant = Instant.now()): PriceResult? {
        val points = prices.intervals.flatMap { interval ->
            val effective = interval.effectivePrice ?: return@flatMap emptyList()
            // A half-hour or an hour (Great Britain's 30-minute rows, an hourly market) is drawn as
            // its quarter-hours at one price, the grid the renderer and the local prices share.
            // Each quarter remembers the published interval's length, so a graph draws it as published.
            val minutes = publishedMinutes(interval)
            quarterStarts(interval).map { instant ->
                val start = atZone(instant, zone)
                PricePoint(start, effective / 100.0, minutes) to start.toLocalDate()
            }
        }
        if (points.isEmpty()) return null
        val today = localDate(now, zone)
        val todayPoints = points.filter { it.second == today }.map { it.first }.sortedBy { it.start }
        val tomorrowPoints = points.filter { it.second.isAfter(today) }.map { it.first }.sortedBy { it.start }
        if (todayPoints.isEmpty() && tomorrowPoints.isEmpty()) return null
        return PriceResult(today = todayPoints, tomorrow = tomorrowPoints, fetchedAt = fetchedAt)
    }

    /**
     * The quarter-hour starts of one interval, by elapsed time: one for a quarter-hour, two for a
     * half-hour, four for an hour (three or five on a clock-change hour). An interval that is not a
     * whole number of quarter-hours keeps its one start, as before.
     */
    private fun quarterStarts(interval: DashboardPriceInterval): List<OffsetDateTime> {
        val minutes = java.time.Duration.between(interval.start.toInstant(), interval.end.toInstant()).toMinutes()
        if (minutes <= QUARTER_MINUTES || minutes % QUARTER_MINUTES != 0L) return listOf(interval.start)
        return (0 until minutes / QUARTER_MINUTES).map { interval.start.plusMinutes(it * QUARTER_MINUTES) }
    }

    /** One interval's published length in whole minutes; an empty or reversed one counts as a quarter-hour. */
    private fun publishedMinutes(interval: DashboardPriceInterval): Int {
        val minutes = java.time.Duration.between(interval.start.toInstant(), interval.end.toInstant()).toMinutes()
        return if (minutes > 0L) minutes.toInt() else QUARTER_MINUTES.toInt()
    }

    private const val QUARTER_MINUTES = 15L

    /** The date it is at [now] in [zone]: the one definition of "today" for a drawn dashboard. */
    fun localDate(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()

    /** The date the chart of [dashboard] is cut for at [now], or `null` without a clock. */
    fun localDate(dashboard: Dashboard, now: Instant): LocalDate? = zone(dashboard)?.let { localDate(now, it) }

    private fun atZone(instant: OffsetDateTime, zone: ZoneId): OffsetDateTime =
        instant.atZoneSameInstant(zone).toOffsetDateTime()

    /** The market's zone: the dashboard's `market.timezone`, else the catalogue's zone for the area, else `null`. */
    fun zone(dashboard: Dashboard): ZoneId? =
        dashboard.market.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: dashboard.market.areaId?.let { PriceMarkets.find(it)?.zoneId }

    /** The chart for [dashboard], each interval as published, or `null` without an area, a clock or a priced interval. */
    fun build(
        dashboard: Dashboard,
        fetchedAt: Long = stamp(dashboard),
        now: Instant = Instant.now()
    ): PairedChart? {
        val areaId = dashboard.market.areaId?.takeIf { it.isNotBlank() } ?: return null
        val zone = zone(dashboard) ?: return null
        val prices = prices(dashboard.prices, zone, fetchedAt, now) ?: return null
        val market = ChartMarket(
            areaId = areaId,
            vat = FiscalInput.OFF,
            tax = FiscalInput.OFF,
            transfer = FiscalInput.OFF
        )
        val figures = figures(dashboard)
        val periods = figures?.periods.orEmpty()
        val bands = if (periods.isEmpty()) emptyList() else ChartOverlay.planned(periods).bands(prices, zone)
        return PairedChart(market, prices, bands, footer(figures), figures)
    }

    /** Home Assistant's composition time as the price result's stamp, never the wall clock, so equal dashboards give equal charts. */
    fun stamp(dashboard: Dashboard): Long =
        dashboard.generatedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

    /** The plan card's figures, or `null` when there is neither a plan nor a proposal. */
    fun figures(dashboard: Dashboard): PairedPlanFigures? {
        val proposal = dashboard.plan.proposal
        val installed = dashboard.plan.installed
        if (proposal != null && proposal.periods.isNotEmpty()) {
            val cost = proposal.costValue?.takeIf { proposal.costCurrency != null }
            return PairedPlanFigures(
                periods = proposal.periods.map { ChargingPeriod(it.start, it.end) },
                fromProposal = true,
                costMajor = cost,
                costCurrency = proposal.costCurrency,
                costUnit = proposal.costCurrency?.let { moneyUnit(it, dashboard.market) },
                unpriced = proposal.unpriced || (proposal.unpricedSlots ?: 0) > 0,
                unpricedSlots = proposal.unpricedSlots ?: 0,
                energyKwh = proposal.plannedKwh,
                distanceMil = proposal.distanceMil,
                miles = inGreatBritain(dashboard.market)
            )
        }
        if (installed != null && installed.periods.isNotEmpty()) {
            return PairedPlanFigures(
                periods = installed.periods.map { ChargingPeriod(it.start, it.end) },
                fromProposal = false,
                costMajor = null, costCurrency = null, costUnit = null,
                unpriced = false, unpricedSlots = 0, energyKwh = null, distanceMil = null
            )
        }
        return null
    }

    /** Whether [market] is in Great Britain: as the answer states its countries, else as the catalogue does. */
    fun inGreatBritain(market: DashboardMarket): Boolean =
        market.inGreatBritain || market.areaId?.let { PriceMarkets.find(it)?.inGreatBritain } == true

    /** The cost label: the market's major unit when the cost is in the market's currency, else the ISO code. */
    fun moneyUnit(currency: String, market: DashboardMarket): String =
        if (market.currency == currency) market.majorUnit?.takeIf { it.isNotBlank() } ?: currency else currency

    /** The footer's facts, only when the proposal states every one of them. */
    private fun footer(figures: PairedPlanFigures?): ChartFooterPlan? {
        figures ?: return null
        if (!figures.fromProposal) return null
        val energy = figures.energyKwh?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val distance = figures.distanceMil?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val start = figures.periods.minOf { it.start }
        val end = figures.periods.maxOf { it.end }
        if (!end.isAfter(start)) return null
        return ChartFooterPlan(
            start = start,
            end = end,
            periodCount = figures.periods.size,
            unpriced = figures.unpriced,
            energyKwh = energy,
            distanceMil = distance
        )
    }

    /** `"34.60 kr"` (`"£1.33"` for the pound, see [MoneyText]): a cost and its unit, in [locale]. */
    fun costText(figures: PairedPlanFigures, locale: Locale): String? {
        val cost = figures.costMajor ?: return null
        val unit = figures.costUnit ?: return null
        return MoneyText.amount(cost, figures.costCurrency, unit, locale)
    }
}
