package se.sensnology.spotnav.planning

import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import java.time.Duration
import java.time.OffsetDateTime
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.sqrt

data class ChargingPeriod(val start: OffsetDateTime, val end: OffsetDateTime)

data class ChargingPlan(
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val powerKw: Double,
    val energyKwh: Double,
    val distanceMil: Double,
    /** The cost of the priced intervals, in major units; unpriced intervals add nothing. */
    val cost: Double,
    /** Intervals charged without any published price (the deadline guarantee); 0 for a priced plan. */
    val unpricedSlots: Int,
    val periods: List<ChargingPeriod> = listOf(ChargingPeriod(start, end)),
    /** Set when only the part that cannot wait is planned and the rest waits for unpublished prices. */
    val awaiting: PriceWaiting? = null
)

/** Prices the plan still needs: when they are expected, and whether they are the next day's. */
data class PriceWaiting(val expectedAt: OffsetDateTime, val forLaterDay: Boolean)

/** What one calculation decided: a plan, and/or waiting for prices (with no plan at all yet). */
data class PlanOutcome(val plan: ChargingPlan?, val waiting: PriceWaiting?)

object ChargingPlanner {
    fun powerKw(amps: Int, phases: Int = 3): Double = if (phases == 1) {
        230.0 * amps / 1000.0
    } else {
        sqrt(3.0) * 400.0 * amps / 1000.0
    }

    fun durationMinutes(inputs: PlanningInputs): Long {
        val energyPerSlot = powerKw(inputs.amps, inputs.phases) * 0.25
        return ceil(inputs.requestedEnergyKwh / energyPerSlot).toLong().coerceAtLeast(1) * 15
    }

    /**
     * The plan for these inputs, or `null` when they cannot produce one (or the plan is still waiting
     * for prices, see [outcome]). Plans only on published prices.
     */
    fun calculate(result: PriceResult, inputs: PlanningInputs, now: OffsetDateTime = OffsetDateTime.now()): ChargingPlan? =
        outcome(result, inputs, now).plan

    /** The prices the plan waits for, when it has no plan yet because they are not published. */
    fun waitingFor(result: PriceResult, inputs: PlanningInputs, now: OffsetDateTime = OffsetDateTime.now()): PriceWaiting? =
        outcome(result, inputs, now).let { if (it.plan == null) it.waiting else null }

    /**
     * Plans on published prices only. When the window reaches past them, the rule of [PriceWait]
     * decides: wait (no plan), buy only what cannot wait, or charge the remainder unpriced.
     */
    fun outcome(result: PriceResult, inputs: PlanningInputs, now: OffsetDateTime = OffsetDateTime.now()): PlanOutcome {
        val none = PlanOutcome(null, null)
        val published = (result.today + result.tomorrow)
            .distinctBy { it.start.toInstant() }
            .sortedBy { it.start.toInstant() }
        if (published.isEmpty()) return none
        val marketNow = now.withOffsetSameInstant(published.first().start.offset)
        val firstStart = marketNow.withSecond(0).withNano(0).let {
            val minute = (it.minute / 15) * 15
            it.withMinute(minute).let { rounded -> if (rounded < marketNow) rounded.plusMinutes(15) else rounded }
        }
        val horizon = firstStart.plusHours(24)
        // The departure is a wall-clock time in the market's own zone, so a clock-change day keeps it.
        val zone = MarketZone.of(inputs.areaId)
        val departure = if (inputs.departure.enabled) {
            val time = inputs.departure.time
            val local = firstStart.atZoneSameInstant(zone)
            val today = local.toLocalDate().atTime(time).atZone(zone)
            (if (today.toInstant() <= firstStart.toInstant()) local.toLocalDate().plusDays(1).atTime(time).atZone(zone) else today)
                .toOffsetDateTime()
        } else null
        // The instant the charge must be over by: the departure, or the end of the 24-hour horizon.
        val endLimit = departure?.let { minOf(horizon, it) } ?: horizon
        val power = powerKw(inputs.amps, inputs.phases)
        val energyPerSlot = power * SLOT_MINUTES / 60.0
        val slotsNeeded = ceil(inputs.requestedEnergyKwh / energyPerSlot).toInt().coerceAtLeast(1)
        val duration = slotsNeeded * SLOT_MINUTES
        val latestStartExclusive = minOf(horizon, endLimit.minusMinutes(duration).plusMinutes(SLOT_MINUTES))
        if (latestStartExclusive <= firstStart) return none
        // Every interval a slot of the plan could occupy must be priced for the plan to be final.
        val requiredEnd = latestStartExclusive.minusMinutes(SLOT_MINUTES).plusMinutes(duration)

        val byInstant = published.associateBy { it.start.toInstant() }
        val known = ArrayList<PricePoint>()
        var cursor = firstStart
        var gapAt: OffsetDateTime? = null
        while (cursor < requiredEnd) {
            val point = byInstant[cursor.toInstant()]
            if (point == null) {
                gapAt = cursor
                break
            }
            known += point
            cursor = cursor.plusMinutes(SLOT_MINUTES)
        }

        if (gapAt == null) {
            return PlanOutcome(cheapest(known, slotsNeeded, energyPerSlot, power, inputs, endLimit), null)
        }

        val missingDay = gapAt.toInstant().atZone(zone).toLocalDate()
        val publicationAt = PriceWait.expectedPublicationAt(missingDay)
        val decision = PriceWait.decide(
            now = now.toInstant(),
            deadline = endLimit.toInstant(),
            needKwh = inputs.requestedEnergyKwh,
            maxChargeKw = power,
            known = known.map {
                PriceWait.KnownInterval(it.start.toInstant(), it.start.plusMinutes(SLOT_MINUTES).toInstant(), it.pricePerKwh)
            },
            publicationAt = publicationAt
        )
        val usableFrom = maxOf(publicationAt, now.toInstant().plus(PriceWait.START_LAG))
        val waiting = PriceWaiting(
            expectedAt = usableFrom.atOffset(marketNow.offset),
            forLaterDay = missingDay.isAfter(marketNow.toLocalDate())
        )
        when (decision.action) {
            PriceWait.Action.WAIT -> return PlanOutcome(null, waiting)
            PriceWait.Action.BUY_NOW -> {
                val slots = ceil(decision.mustBuyKwh / energyPerSlot - 1e-9).toInt().coerceAtLeast(1)
                val eligibleStarts = decision.eligible.map { it.start }.toSet()
                val eligible = known.filter { it.start.toInstant() in eligibleStarts }
                val bought = cheapest(eligible, slots, energyPerSlot, power, inputs, decision.windowEnd!!.atOffset(marketNow.offset))
                if (bought != null) return PlanOutcome(bought.copy(awaiting = waiting), waiting)
            }
            PriceWait.Action.GUARANTEE -> Unit
        }
        // The deadline is kept, only the price is given up.
        return PlanOutcome(unpriced(firstStart, endLimit, slotsNeeded, energyPerSlot, power, inputs), null)
    }

    /** One run from the first usable slot, at unknown prices: every slot unpriced, cost zero. */
    private fun unpriced(
        firstStart: OffsetDateTime,
        endLimit: OffsetDateTime,
        slotsNeeded: Int,
        energyPerSlot: Double,
        power: Double,
        inputs: PlanningInputs
    ): ChargingPlan? {
        val fitting = (Duration.between(firstStart, endLimit).toMinutes() / SLOT_MINUTES).toInt()
        val count = minOf(slotsNeeded, fitting)
        if (count <= 0) return null
        val end = firstStart.plusMinutes(count * SLOT_MINUTES)
        val energy = count * energyPerSlot
        return ChargingPlan(
            start = firstStart, end = end, powerKw = power, energyKwh = energy,
            distanceMil = energy / inputs.consumptionKwhPer10Km, cost = 0.0, unpricedSlots = count
        )
    }

    /** The cheapest choice of [slotsNeeded] slots from [points] (in order) ending by [endLimit], or `null`. */
    private fun cheapest(
        points: List<PricePoint>,
        slotsNeeded: Int,
        energyPerSlot: Double,
        power: Double,
        inputs: PlanningInputs,
        endLimit: OffsetDateTime
    ): ChargingPlan? {
        if (points.size < slotsNeeded) return null
        val slotMinutes = SLOT_MINUTES
        val plannedEnergy = slotsNeeded * energyPerSlot

        data class State(val selected: Int, val runs: Int, val active: Boolean)
        data class Choice(val cost: Double, val slots: BitSet)
        var states = mapOf(State(0, 0, false) to Choice(0.0, BitSet()))
        points.forEachIndexed { index, point ->
            val next = mutableMapOf<State, Choice>()
            fun keep(state: State, choice: Choice) {
                if (choice.cost < (next[state]?.cost ?: Double.POSITIVE_INFINITY)) next[state] = choice
            }
            states.forEach { (state, choice) ->
                keep(state.copy(active = false), choice)
                val newRuns = state.runs + if (state.active) 0 else 1
                val beforeEnd = point.start.plusMinutes(slotMinutes) <= endLimit
                if (beforeEnd && state.selected < slotsNeeded && newRuns <= inputs.maxPeriods) {
                    val selectedSlots = choice.slots.clone() as BitSet
                    selectedSlots.set(index)
                    keep(
                        State(state.selected + 1, newRuns, true),
                        Choice(choice.cost + energyPerSlot * inputs.apply(point.pricePerKwh), selectedSlots)
                    )
                }
            }
            states = next
        }
        val best = states.filterKeys { it.selected == slotsNeeded }.minByOrNull { it.value.cost }?.value ?: return null
        val selected = points.filterIndexed { index, _ -> best.slots[index] }
        val periods = buildList<ChargingPeriod> {
            selected.forEach { item ->
                val previous = lastOrNull()
                if (previous != null && previous.end.toInstant() == item.start.toInstant()) {
                    this[lastIndex] = previous.copy(end = item.start.plusMinutes(slotMinutes))
                } else add(ChargingPeriod(item.start, item.start.plusMinutes(slotMinutes)))
            }
        }
        return ChargingPlan(
            periods.first().start, periods.last().end, power, plannedEnergy,
            plannedEnergy / inputs.consumptionKwhPer10Km,
            best.cost / 100.0,
            0,
            periods
        )
    }

    private const val SLOT_MINUTES = 15L
}
