package se.sensnology.spotnav.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.sensnology.spotnav.chart.LocalCharts
import se.sensnology.spotnav.planning.PriceWait.Action
import se.sensnology.spotnav.planning.PriceWait.KnownInterval
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** The pure price-wait decision, and closed-loop days that prove it keeps the deadline on real prices. */
class PriceWaitTest {
    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    private fun hourly(start: Instant, hours: Int) = (0 until hours).map {
        KnownInterval(start.plus(Duration.ofHours(it.toLong())), start.plus(Duration.ofHours(it + 1L)), 0.1 * (it % 5))
    }

    private val now = at("2026-09-22T08:00:00+00:00")
    private val deadline = at("2026-09-23T06:00:00+00:00")
    private val publication = at("2026-09-22T11:45:00+00:00")

    private fun decide(need: Double, kw: Double = 2.0, at: Instant = now, known: List<KnownInterval> = hourly(at, 16), pub: Instant = publication) =
        PriceWait.decide(at, deadline, need, kw, known, pub)

    // ---- the pure decision

    @Test fun aNeedThatFitsAfterThePublicationWaits() {
        val d = decide(20.0)
        assertEquals(Action.WAIT, d.action)
        assertEquals(0.0, d.mustBuyKwh, 0.0)
        assertEquals(publication, d.publicationAt)
        assertEquals(deadline.minusNanos((20.0 / 1.6 * 3.6e12).toLong()), d.latestSafeStart)
    }

    @Test fun onlyWhatCannotWaitIsBoughtFromKnownIntervalsBeforeThePublication() {
        val d = decide(33.0)
        assertEquals(Action.BUY_NOW, d.action)
        assertEquals(33.0 - 18.25 * 1.6, d.mustBuyKwh, 1e-9)
        assertEquals(publication, d.windowEnd)
        assertTrue(d.eligible.isNotEmpty() && d.eligible.all { !it.end.isAfter(publication) })
    }

    @Test fun theGuaranteeIsDueOneSlotBeforeTheLatestSafeStart() {
        val safeStart = deadline.minusNanos((20.0 / 1.6 * 3.6e12).toLong())
        val justBefore = safeStart.minus(PriceWait.START_LAG).minusSeconds(10)
        val later = safeStart.minus(PriceWait.START_LAG).plusSeconds(10)
        assertFalse(decide(20.0, at = justBefore).action == Action.GUARANTEE)
        val due = decide(20.0, at = later)
        assertEquals(Action.GUARANTEE, due.action)
        assertEquals(20.0, due.mustBuyKwh, 0.0)
        assertEquals(safeStart.minus(PriceWait.START_LAG), due.actBy)
    }

    @Test fun aNeedExactlyAtTheWaitCapacityWaitsDespiteFloatNoise() {
        val exact = Duration.between(publication, deadline).seconds / 3600.0 * 2.0 * 0.8
        for (nudge in listOf(0.0, 1e-9, -1e-9, 1e-7)) assertEquals("$nudge", Action.WAIT, decide(exact + nudge).action)
    }

    @Test fun anOverduePublicationIsMeasuredFromNowNotFromThePast() {
        val overdue = at("2026-09-22T05:00:00+00:00")
        assertEquals(Action.WAIT, decide(20.0, pub = overdue).action)
        val tight = decide(20.0, at = at("2026-09-23T00:00:00+00:00"), known = hourly(now, 16), pub = overdue)
        assertEquals(Action.GUARANTEE, tight.action)
    }

    @Test fun knownPricesTooFewToHoldThePurchaseFallBackToTheGuarantee() {
        assertEquals(Action.GUARANTEE, decide(35.0, known = hourly(now, 1)).action)
    }

    @Test fun aDecisionNeedsSomethingToCharge() {
        try {
            decide(0.0)
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun publicationIsThirteenHundredBrusselsPlusTheMarginOnTheDayBefore() {
        assertEquals(at("2026-09-22T11:45:00Z"), PriceWait.expectedPublicationAt(LocalDate.of(2026, 9, 23)))
        assertEquals(at("2026-01-14T12:45:00Z"), PriceWait.expectedPublicationAt(LocalDate.of(2026, 1, 15)))
    }

    @Test fun publicationIsDstSafeAcrossBothClockChanges() {
        fun pub(y: Int, m: Int, d: Int) = PriceWait.expectedPublicationAt(LocalDate.of(y, m, d))
        assertEquals(at("2026-03-28T12:45:00Z"), pub(2026, 3, 29))
        assertEquals(at("2026-03-29T11:45:00Z"), pub(2026, 3, 30))
        assertEquals(at("2026-10-24T11:45:00Z"), pub(2026, 10, 25))
        assertEquals(at("2026-10-25T12:45:00Z"), pub(2026, 10, 26))
        assertEquals(Duration.ofHours(23), Duration.between(pub(2026, 3, 29), pub(2026, 3, 30)))
        assertEquals(Duration.ofHours(25), Duration.between(pub(2026, 10, 25), pub(2026, 10, 26)))
    }

    // ---- the closed loop: the real planner, replanned every quarter-hour

    private val stockholm = ZoneId.of("Europe/Stockholm")

    /** A real day (23, 24 or 25 hours by instant): cheap night, dear morning, a cheap afternoon dip. */
    private fun day(date: LocalDate): List<PricePoint> {
        val first = date.atStartOfDay(stockholm).toInstant()
        val last = date.plusDays(1).atStartOfDay(stockholm).toInstant()
        val count = (Duration.between(first, last).seconds / 900).toInt()
        return (0 until count).map { index ->
            val start = first.plusSeconds(900L * index)
            val local = start.atZone(stockholm)
            var price = mapOf(0 to 0.08, 1 to 0.05, 2 to 0.04, 3 to 0.04, 4 to 0.06, 5 to 0.09)[local.hour] ?: 0.30
            if (local.hour in 13..15) price = 0.03 + 0.01 * (date.dayOfMonth % 3)
            if (local.hour in 17..20) price = 0.55
            PricePoint(start.atZone(stockholm).toOffsetDateTime(), price)
        }
    }

    private class Outcome {
        var delivered = 0.0
        var finishedAt: Instant? = null
        var priced = 0
        var unpriced = 0
        var firstCharge: Instant? = null
        val decisions = mutableListOf<String>()
    }

    private fun baseInputs(amps: Int, phases: Int, hour: Int = 8) = LocalPlanningInputs.of(
        WidgetSettings(
            area = "SE4", chargingPhases = phases, chargingAmps = amps, chargingKwh = 1.0,
            useDepartureTime = true, departureHour = hour, departureMinute = 0, maxChargingPeriods = 4
        )
    )

    private fun run(
        start: Instant, need: Double, amps: Int, phases: Int, pricesArriveAt: Instant?,
        today: LocalDate, tomorrow: LocalDate, actualRate: Double = 1.0
    ): Pair<Outcome, Instant> {
        val kw = ChargingPlanner.powerKw(amps, phases)
        val outcome = Outcome()
        val base = baseInputs(amps, phases)
        val priced = day(today)
        val later = day(tomorrow)
        // The departure is one instant fixed at the start (08:00 local the next morning).
        val deadline = tomorrow.atTime(8, 0).atZone(stockholm).toInstant()
        var installed = emptyList<Triple<Instant, Instant, Boolean>>()
        var now = start
        while (true) {
            val published = pricesArriveAt != null && now >= pricesArriveAt
            val result = PriceResult(priced, if (published) later else emptyList(), 0)
            val remaining = need - outcome.delivered
            if (remaining <= 1e-9) {
                outcome.finishedAt = now
                return outcome to deadline
            }
            if (now >= deadline) return outcome to deadline
            val stamp = now.atZone(stockholm).toOffsetDateTime()
            val planned = ChargingPlanner.outcome(result, base.copy(requestedEnergyKwh = remaining), stamp)
            val plan = planned.plan
            if (plan != null) {
                val unpriced = plan.unpricedSlots > 0
                outcome.decisions += if (unpriced) "guarantee" else if (plan.awaiting != null) "buy_now" else "plan"
                installed = plan.periods.map { Triple(it.start.toInstant(), it.end.toInstant(), !unpriced) }
            } else {
                outcome.decisions += if (planned.waiting != null) "wait" else "none"
                // "wait" installs nothing new: what was installed stays, as the executor leaves it.
            }
            for ((begin, end, isPriced) in installed) {
                if (begin <= now && now < end) {
                    outcome.delivered += kw * actualRate * 0.25
                    if (isPriced) outcome.priced++ else outcome.unpriced++
                    if (outcome.firstCharge == null) outcome.firstCharge = now
                    break
                }
            }
            now = now.plusSeconds(900)
        }
    }

    private val today = LocalDate.of(2026, 9, 22)
    private val tomorrow = LocalDate.of(2026, 9, 23)
    private val morning = at("2026-09-22T09:00:00+02:00")

    @Test fun pricesAt1305AreWaitedForAndTheWholeChargeUsesRealPrices() {
        val (o, deadline) = run(morning, 40.0, 16, 3, at("2026-09-22T13:05:00+02:00"), today, tomorrow)
        assertEquals(at("2026-09-23T08:00:00+02:00"), deadline)
        assertTrue(o.delivered >= 40.0 - 1e-9)
        assertTrue(o.finishedAt!! <= deadline)
        assertEquals("only real prices", 0, o.unpriced)
        assertEquals(setOf("wait"), o.decisions.take(4 * 4 + 1).toSet())
        assertTrue(o.firstCharge!! >= at("2026-09-22T13:05:00+02:00"))
    }

    @Test fun pricesAt1600ChangeNothingButTheTimeThePlanIsMade() {
        val (o, deadline) = run(morning, 40.0, 16, 3, at("2026-09-22T16:00:00+02:00"), today, tomorrow)
        assertTrue(o.delivered >= 40.0 - 1e-9)
        assertTrue(o.finishedAt!! <= deadline)
        assertEquals(0, o.unpriced)
        assertTrue(o.firstCharge!! >= at("2026-09-22T16:00:00+02:00"))
        assertFalse("guarantee" in o.decisions)
    }

    @Test fun pricesThatNeverComeAreGuaranteedAtTheLatestSafeStart() {
        for (rate in listOf(1.0, 0.9)) {
            val (o, deadline) = run(morning, 40.0, 16, 3, null, today, tomorrow, actualRate = rate)
            assertTrue(o.delivered >= 40.0 - 1e-9)
            assertTrue(o.finishedAt!! <= deadline)
            val first = o.decisions.indexOf("guarantee")
            assertTrue(first > 0)
            assertEquals("it waits, never buying at unknown prices before it must", setOf("wait"), o.decisions.take(first).toSet())
            assertTrue(o.unpriced > 0 && o.priced == 0)
            val safeStart = deadline.minusNanos((40.0 / (ChargingPlanner.powerKw(16, 3) * 0.8) * 3.6e12).toLong())
            assertTrue(o.firstCharge!! >= safeStart.minusSeconds(30 * 60) && o.firstCharge!! <= safeStart.plusSeconds(15 * 60))
        }
    }

    @Test fun aNeedTooLargeToWaitBuysOnlyTheExcessBeforeThePublication() {
        val (o, deadline) = run(morning, 40.0, 10, 1, at("2026-09-22T13:05:00+02:00"), today, tomorrow)
        assertEquals("buy_now", o.decisions[0])
        assertTrue(o.delivered >= 40.0 - 1e-9)
        assertTrue(o.finishedAt!! <= deadline)
        assertEquals("the purchase was made in known prices", 0, o.unpriced)
        assertTrue(o.firstCharge!! < at("2026-09-22T13:05:00+02:00"))
    }

    @Test fun aNeedTooLargeToWaitAndPricesThatNeverComeStillMeetsTheDeadline() {
        val (o, deadline) = run(morning, 40.0, 10, 1, null, today, tomorrow)
        assertTrue(o.delivered >= 40.0 - 1e-9)
        assertTrue(o.finishedAt!! <= deadline)
        assertTrue("buy_now" in o.decisions && "guarantee" in o.decisions)
        assertTrue("what could be bought in known prices was", o.priced > 0)
    }

    @Test fun theAutumnClockChangeDayKeepsTheDeadlineAndTheRealPrices() {
        val d1 = LocalDate.of(2026, 10, 24)
        val d2 = LocalDate.of(2026, 10, 25)
        val start = at("2026-10-24T09:00:00+02:00")
        val (published, deadline) = run(start, 45.0, 16, 3, at("2026-10-24T13:05:00+02:00"), d1, d2)
        val (never, _) = run(start, 45.0, 16, 3, null, d1, d2)
        assertEquals("08:00 is CET, an hour later", at("2026-10-25T08:00:00+01:00"), deadline)
        for (o in listOf(published, never)) {
            assertTrue(o.delivered >= 45.0 - 1e-9)
            assertTrue(o.finishedAt!! <= deadline)
        }
        assertTrue(published.unpriced == 0 && never.unpriced > 0)
    }

    @Test fun theSpringClockChangeDayKeepsTheDeadlineAndTheRealPrices() {
        val d1 = LocalDate.of(2026, 3, 28)
        val d2 = LocalDate.of(2026, 3, 29)
        val start = at("2026-03-28T09:00:00+01:00")
        val (published, deadline) = run(start, 45.0, 16, 3, at("2026-03-28T13:05:00+01:00"), d1, d2)
        val (never, _) = run(start, 45.0, 16, 3, null, d1, d2)
        assertEquals("08:00 is CEST, an hour earlier", at("2026-03-29T08:00:00+02:00"), deadline)
        for (o in listOf(published, never)) {
            assertTrue(o.delivered >= 45.0 - 1e-9)
            assertTrue(o.finishedAt!! <= deadline)
        }
        assertTrue(published.unpriced == 0 && never.unpriced > 0)
    }

    @Test fun aTinyNeedAfterThePublicationWaitsAllTheWayAndNeverBuysEarly() {
        val (o, _) = run(morning, 5.0, 16, 3, at("2026-09-22T13:05:00+02:00"), today, tomorrow)
        assertTrue(o.delivered >= 5.0 - 1e-9 && o.unpriced == 0)
        assertFalse("buy_now" in o.decisions)
        assertTrue(o.firstCharge!! >= at("2026-09-22T13:05:00+02:00"))
    }

    @Test fun aDayThatIsAlreadyPricedIsJustPlanned() {
        val (o, _) = run(morning, 40.0, 16, 3, morning, today, tomorrow)
        assertEquals(setOf("plan"), o.decisions.toSet())
        assertTrue(o.delivered >= 40.0 - 1e-9 && o.unpriced == 0)
    }

    // ---- the plan card and the chart agree

    @Test fun theWaitStatesTheExpectedPublicationAndPlansNothing() {
        val stamp = at("2026-09-22T09:00:00+02:00").atZone(stockholm).toOffsetDateTime()
        val result = PriceResult(day(today), emptyList(), 0)
        val inputs = baseInputs(16, 3)
        assertNull(ChargingPlanner.calculate(result, inputs, stamp))
        val waiting = ChargingPlanner.waitingFor(result, inputs, stamp)!!
        assertTrue(waiting.forLaterDay)
        assertEquals("13:45", waiting.expectedAt.atZoneSameInstant(stockholm).toLocalTime().toString())
    }

    @Test fun everyPlannedPeriodLiesWithinDrawnIntervalsOrThePlanIsEmptyOrWaiting() {
        val drawnDays = listOf(day(today), day(today) + day(tomorrow))
        for (published in drawnDays) for (amps in listOf(10, 16)) for (phases in listOf(1, 3)) for (hour in listOf(6, 8)) {
            var cursor = at("2026-09-22T06:00:00+02:00")
            val end = at("2026-09-23T00:30:00+02:00")
            while (cursor < end) {
                val stamp = cursor.atZone(stockholm).toOffsetDateTime()
                val result = PriceResult(published.filter { it.start.toLocalDate() == today }, published.filter { it.start.toLocalDate() != today }, 0)
                val inputs = baseInputs(amps, phases, hour)
                val outcome = ChargingPlanner.outcome(result, inputs, stamp)
                val plan = outcome.plan
                if (plan == null) {
                    // empty or waiting: nothing shaded, nothing claimed
                } else if (plan.unpricedSlots == 0) {
                    val drawn = published.map { it.start.toInstant() }.toSet()
                    for (period in plan.periods) {
                        var t = period.start.toInstant()
                        while (t < period.end.toInstant()) {
                            assertTrue("$t of $period at $stamp must be a drawn interval", t in drawn)
                            t = t.plusSeconds(900)
                        }
                    }
                    val chart = LocalCharts.fromPlan(inputs, result, plan)
                    assertTrue("the chart marks the plan", chart.bands.isNotEmpty())
                } else {
                    // A guarantee charges where no price exists, so there is nothing to draw there;
                    // the card says so and states no cost.
                    assertEquals(plan.energyKwh, plan.unpricedSlots * ChargingPlanner.powerKw(amps, phases) * 0.25, 1e-9)
                    assertNotNull(plan.start)
                }
                cursor = cursor.plusSeconds(3 * 900)
            }
        }
    }
}
