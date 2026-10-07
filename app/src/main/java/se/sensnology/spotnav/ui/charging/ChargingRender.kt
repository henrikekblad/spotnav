package se.sensnology.spotnav.ui.charging

import android.view.View
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.app.DistanceUnit
import se.sensnology.spotnav.chart.ChartPass
import se.sensnology.spotnav.chart.DashboardChart
import se.sensnology.spotnav.chart.LocalChart
import se.sensnology.spotnav.chart.PairedChart
import se.sensnology.spotnav.chart.PairedPlanFigures
import se.sensnology.spotnav.ha.authority.AuthorityPlan
import se.sensnology.spotnav.ha.authority.PlanSource
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.HaStatusText
import se.sensnology.spotnav.ha.dashboard.StatusFormat
import se.sensnology.spotnav.planning.ChargingPeriod
import se.sensnology.spotnav.planning.ChargingPlanner
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.planning.MarketZone
import se.sensnology.spotnav.planning.PlanReadout
import se.sensnology.spotnav.planning.PriceWaiting
import se.sensnology.spotnav.planning.SchedulePeriodText
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.ui.settings.AreaMoney
import se.sensnology.spotnav.ui.settings.CostLabel
import se.sensnology.spotnav.widget.WidgetPlanCapture
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The render pass of the charging screen:

internal fun ChargingScreen.periodLines(periods: List<ChargingPeriod>, zoneId: ZoneId): String {
    val locale = AppLanguageSettings.locale(context)
    return periods.joinToString("\n") { period ->
        SchedulePeriodText.line(period, zoneId, locale) { start, end ->
            t(R.string.charging_window, start, end)
        }
    }
}

/** A distance in the person's own unit: miles in Great Britain, mil for Swedish and Norwegian, kilometres elsewhere. */
internal fun ChargingScreen.distanceText(mil: Double, miles: Boolean = false): String =
    "${t(R.string.approximately)} " +
        DistanceUnit.text(mil, AppLanguageSettings.language(context), AppLanguageSettings.numberLocale(context), miles)

/** One built paired chart and what it was built from. */
internal class PairedChartMemo(
    val dashboard: Dashboard,
    val date: java.time.LocalDate?,
    val chart: PairedChart?
)

internal fun ChargingScreen.pairedChartFor(dashboard: Dashboard): PairedChart? {
    // The local date is part of the key: the same answer drawn after midnight is a different chart.
    val now = java.time.Instant.now()
    val date = DashboardChart.localDate(dashboard, now)
    pairedChartMemo?.let {
        if (it.dashboard === dashboard && it.date == date) return it.chart
    }
    return DashboardChart.build(dashboard, now = now).also {
        pairedChartMemo = PairedChartMemo(dashboard, date, it)
    }
}

/**
 * the cost with its currency, the energy and the distance, each only when the proposal states it (a
 * figure that is absent is absent, never a placeholder). The periods are the card's title, written
 * by the caller.
 */
internal fun ChargingScreen.showPairedFigures(figures: PairedPlanFigures) {
    val result = planCard.result
    val cost = DashboardChart.costText(figures, AppLanguageSettings.numberLocale(context))
    result.showFigures(true)
    result.costRow.visibility = if (cost != null) View.VISIBLE else View.GONE
    result.energyRow.visibility = if (figures.energyKwh != null) View.VISIBLE else View.GONE
    result.distanceRow.visibility = if (figures.distanceMil != null) View.VISIBLE else View.GONE
    // An unpriced plan has no cost to state: the note below says why.
    if (cost != null && !figures.unpriced) {
        result.cost.text = "${t(R.string.approximately)} $cost"
        result.cost.setTextColor(dark)
    } else {
        result.costRow.visibility = View.GONE
    }
    figures.energyKwh?.let { result.energyResult.text = kwhText(it) }
    figures.distanceMil?.let { result.distance.text = distanceText(it, figures.miles) }
    result.note.text = if (figures.unpriced && figures.unpricedSlots > 0) {
        tq(R.plurals.unpriced_note, figures.unpricedSlots, figures.unpricedSlots)
    } else ""
}

/** When the awaited prices are expected, as the market's own wall clock reads it. */
private fun ChargingScreen.publicationTime(waiting: PriceWaiting, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm", AppLanguageSettings.locale(context))
        .format(waiting.expectedAt.atZoneSameInstant(zone))

internal fun ChargingScreen.render() {
    settings = currentSettings()
    // The calculation value is only built for a widget that has an area: Which authority this pass
    // renders, and the prices that belong to it, captured once from the controller:
    val state = authority.authority
    val prices = authority.currentPrices
    // The last chart this screen confirmed, when it is exactly this record's own (see
    // ConfirmedChart):
    val retained = (state as? VisibleAuthority.ReadOnlyOffline)?.lastConfirmed
        ?.let { record -> authority.confirmedChartFor(record) }
    // A retained chart is the picture this screen last confirmed; it is drawn without being
    // recomputed.
    val source = AuthorityPlan.screenSource(
        authority = state,
        localInputs = LocalPlanningInputs.ofOrNull(settings)
    )
    val inputs = (source as? PlanSource.AndroidCalculates)?.inputs
    // Whether there is nothing to charge is a fact about *these* inputs -- the driver and the
    // target the authority states -- never about the live controls, which a record may have moved
    // on from.
    val nothingToCharge = inputs?.let {
        planCard.nothingToCharge(it.driver, it.targetSocPercent?.toInt())
    } ?: false
    val plan = ChartPass.plan(
        inputs = inputs,
        prices = prices,
        calculates = source is PlanSource.AndroidCalculates,
        nothingToCharge = nothingToCharge
    )
    authority.planMemoFor(source, inputs, retained?.prices ?: prices, plan)
    refreshChargerControls()
    // The one chart this pass draws  A paired
    // charger's graph is Home Assistant's own:
    val held = pairedNow()
    val pairedChart = if (source is PlanSource.RemoteOwned && retained == null) {
        held?.let { pairedChartFor(it) }
    } else {
        null
    }
    // The one chart this pass draws, decided in one place.
    val chart: LocalChart? = when {
        retained != null -> LocalChart(
            market = retained.market,
            bands = retained.bands,
            footer = retained.footer,
            drawnPrices = retained.drawnPrices,
            figures = retained.figures
        )
        pairedChart != null -> LocalChart(
            market = pairedChart.market,
            bands = pairedChart.bands,
            footer = pairedChart.footer,
            drawnPrices = pairedChart.prices,
            figures = pairedChart.figures
        )
        source is PlanSource.RemoteOwned -> null
        else -> ChartPass.chart(inputs, prices, plan)
    }
    // The market's own clock, taken from the same area the graph was priced in:
    val marketZone = (if (retained == null) held?.let { DashboardChart.zone(it) } else null) ?: MarketZone.of(
        retained?.areaId
            ?: inputs?.areaId
    )
    // Retain the chart this pass drew, when the pass is a confirmed and coherent one; any other
    // pass leaves the retained one alone (see ConfirmedChartRules.capture).
    authority.noteChart(state, prices, chart)
    widgetPlans.publish(
        WidgetPlanCapture.of(
            profileId = authorityProfile?.localId,
            subject = authority.currentSubject,
            state = state,
            prices = retained?.prices ?: prices,
            capturedAt = System.currentTimeMillis()
        )
    )
    if (source is PlanSource.RemoteOwned) {
        // Auto:
        planCard.result.showFigures(false)
        val figures = chart?.figures
            ?: if (retained == null) held?.let { DashboardChart.figures(it) } else null
        // The schedule the last *confirmed* status reported, when a retained chart is the one on
        // screen -- otherwise the one this state carries. Never a local proposal.
        val remotePlan = retained?.remotePlan ?: source.remotePlan
        planCard.result.title.text = if (figures != null) {
            periodLines(figures.periods, marketZone)
        } else if (remotePlan != null && remotePlan.hasInstalledPeriods) {
            periodLines(remotePlan.periods, marketZone)
        } else {
            // A plan that waits on prices says so; otherwise nothing is installed yet.
            held?.let {
                HaStatusText.priceWait(
                    it.status,
                    StatusFormat.of(AppLanguageSettings.language(context), it.market, AppLanguageSettings.numberLocale(context)),
                    java.time.Instant.now()
                )
            } ?: t(R.string.authority_auto_waiting)
        }
        planCard.result.note.text = ""
        if (figures != null && figures.fromProposal) showPairedFigures(figures)
    } else if (state != null && state.haOwnsPlanning && source is PlanSource.None && retained == null) {
        planCard.result.title.text = t(R.string.authority_not_ready)
        planCard.result.showFigures(false)
        planCard.result.note.text = ""
    } else if (nothingToCharge) {
        planCard.result.title.text = t(R.string.nothing_to_charge)
        planCard.result.showFigures(false)
        planCard.result.note.text = t(R.string.nothing_to_charge_note)
    } else if (plan == null && inputs != null && prices != null &&
        ChargingPlanner.waitingFor(prices, inputs) != null
    ) {
        // Nothing can be planned yet and nothing needs to be: the prices it needs are still to come.
        val waiting = ChargingPlanner.waitingFor(prices, inputs)!!
        // Once the expected time has passed, the prices may come any minute: no time is named, as the card does.
        planCard.result.title.text = when {
            WaitingTime.passed(waiting) ->
                t(if (waiting.forLaterDay) R.string.waiting_for_prices_later_no_time else R.string.waiting_for_prices_no_time)
            else -> t(
                if (waiting.forLaterDay) R.string.waiting_for_prices_later else R.string.waiting_for_prices,
                publicationTime(waiting, marketZone)
            )
        }
        planCard.result.showFigures(false)
        planCard.result.note.text = ""
    } else if (plan == null && inputs != null) {
        val durationMinutes = ChargingPlanner.durationMinutes(inputs)
        val localNow = OffsetDateTime.now()
        val marketNow = (prices?.today?.firstOrNull() ?: prices?.tomorrow?.firstOrNull())
            ?.let { localNow.withOffsetSameInstant(it.start.offset) } ?: localNow
        val firstStart = marketNow.withSecond(0).withNano(0).let {
            val rounded = it.withMinute((it.minute / 15) * 15)
            if (rounded < it) rounded.plusMinutes(15) else rounded
        }
        // The departure the *inputs* carry:
        val departure = inputs.departure
        val missesDeparture = PlanReadout.missesDeparture(
            firstStart, durationMinutes, departure.enabled,
            departure.time.hour, departure.time.minute
        )
        planCard.result.title.text = if (missesDeparture) t(R.string.misses_departure) else t(R.string.no_plan)
        planCard.result.showFigures(false)
        planCard.result.note.text = if (missesDeparture) {
            val hours = durationMinutes / 60
            val minutes = durationMinutes % 60
            val duration = if (minutes == 0L) tq(R.plurals.duration_hours, hours.toInt(), hours) else t(R.string.duration_hours_minutes, hours, minutes)
            t(R.string.charging_too_long, duration)
        } else {
            t(R.string.not_enough_prices)
        }
    } else if (plan != null) {
        planCard.result.showFigures(true)
        planCard.result.title.text = periodLines(plan.periods, marketZone)
        val unpriced = plan.unpricedSlots > 0
        // `SEK` beside a number is a code where a unit belongs.
        val plannedArea = retained?.areaId ?: inputs!!.areaId
        val money = AreaMoney.of(plannedArea, PriceMarkets.find(plannedArea))
        val amount = CostLabel.amount(plan.cost, money, AppLanguageSettings.numberLocale(context))
        planCard.result.cost.text = if (amount == null) {
            t(R.string.area_unavailable, plannedArea)
        } else {
            "${t(R.string.approximately)} $amount"
        }
        planCard.result.cost.setTextColor(dark)
        // A charge at unknown prices has no cost to state.
        planCard.result.costRow.visibility = if (unpriced) View.GONE else View.VISIBLE
        planCard.result.energyResult.text = kwhText(plan.energyKwh)
        planCard.result.distance.text = distanceText(plan.distanceMil, PriceMarkets.find(plannedArea)?.inGreatBritain == true)
        planCard.result.note.text = when {
            unpriced -> tq(R.plurals.unpriced_note, plan.unpricedSlots, plan.unpricedSlots)
            plan.awaiting != null && WaitingTime.passed(plan.awaiting) -> t(R.string.partly_waiting_for_prices_no_time)
            plan.awaiting != null -> t(R.string.partly_waiting_for_prices, publicationTime(plan.awaiting, marketZone))
            else -> ""
        }
    }
    // The graph is the last thing this pass touches, and it is the one part of the card that is not
    // text:
    planCard.result.chart.show(
        chart?.market, chart?.bands ?: emptyList(), chart?.footer, chart?.drawnPrices ?: retained?.prices ?: prices
    )
    planCard.result.authorityNote.text = authorityNote.orEmpty()
}
