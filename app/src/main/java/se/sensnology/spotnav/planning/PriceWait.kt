package se.sensnology.spotnav.planning

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What to do when the prices a plan needs are not published yet. Port of the Home Assistant
 * integration's `planning/price_wait.py`, same slack principle applied to time:
 *
 *     publicationAt = next expected day-ahead publication for the missing day, plus a margin
 *     waitCapacity  = hours from max(publicationAt, now + one slot) to the deadline x maxKw x margin
 *     mustBuyNow    = max(0, need - waitCapacity)
 *
 * - [Action.WAIT]: `mustBuyNow == 0`; everything still fits after the publication, nothing is planned.
 * - [Action.BUY_NOW]: only the part that cannot wait is bought, in the cheapest *published* intervals
 *   before the publication; the rest is planned against real prices once they arrive.
 * - [Action.GUARANTEE]: the published intervals cannot hold what must be bought (the latest safe
 *   start has arrived): the remainder is charged at once at unknown prices. The deadline is kept,
 *   only the price is given up.
 *
 * Pure: no clock, no storage. The publication instant is built in the market's zone and converted to
 * an instant before any subtraction, so clock-change days cannot shift it. Comparisons carry a small
 * epsilon so a need exactly equal to the wait capacity waits instead of buying a sliver.
 */
internal object PriceWait {
    /** Fraction of the charger's maximum rate the deadline must still be reachable at. */
    const val FEASIBILITY_MARGIN = 0.8

    /** Day-ahead auctions clear on one European clock, so one zone serves every area. */
    val PUBLICATION_ZONE: ZoneId = ZoneId.of("Europe/Brussels")
    val PUBLICATION_LOCAL_TIME: LocalTime = LocalTime.of(13, 0)

    /** How long after the expected instant a publication is still "on time". */
    val PUBLICATION_MARGIN: Duration = Duration.ofMinutes(45)

    /** A plan starts on the next quarter-hour, so the guarantee is due one slot before the latest safe start. */
    val START_LAG: Duration = Duration.ofMinutes(15)

    private const val EPSILON_KWH = 1e-6
    private const val EPSILON_SECONDS = 1e-3

    enum class Action { WAIT, BUY_NOW, GUARANTEE }

    /** One interval whose price is published. */
    data class KnownInterval(val start: Instant, val end: Instant, val price: Double)

    data class Decision(
        val action: Action,
        /** The expected publication instant, margin included; reported even when overdue. */
        val publicationAt: Instant,
        /** The energy that cannot wait ([Action.BUY_NOW]), the whole need ([Action.GUARANTEE]), 0 for wait. */
        val mustBuyKwh: Double,
        /** `deadline - need / (maxKw x margin)`: when waiting stops being safe. */
        val latestSafeStart: Instant,
        /** The latest safe start less one slot: when the guarantee is due. */
        val actBy: Instant,
        /** [Action.BUY_NOW] only: published intervals ending before the publication and the deadline. */
        val eligible: List<KnownInterval> = emptyList(),
        /** [Action.BUY_NOW] only: where the purchase window ends. */
        val windowEnd: Instant? = null
    )

    /** When [missingDay] (local date of the first instant with no price) is expected. */
    fun expectedPublicationAt(
        missingDay: LocalDate,
        margin: Duration = PUBLICATION_MARGIN,
        zone: ZoneId = PUBLICATION_ZONE,
        localTime: LocalTime = PUBLICATION_LOCAL_TIME
    ): Instant =
        ZonedDateTime.of(missingDay.minusDays(1), localTime, zone).toInstant().plus(margin)

    /** The last instant charging [needKwh] at the derated rate still ends by [deadline]. */
    fun latestSafeStart(deadline: Instant, needKwh: Double, maxChargeKw: Double, margin: Double = FEASIBILITY_MARGIN): Instant =
        deadline.minus(hours(needKwh / (maxChargeKw * margin)))

    fun decide(
        now: Instant,
        deadline: Instant,
        needKwh: Double,
        maxChargeKw: Double,
        known: List<KnownInterval>,
        publicationAt: Instant,
        margin: Double = FEASIBILITY_MARGIN,
        startLag: Duration = START_LAG
    ): Decision {
        require(needKwh > EPSILON_KWH) { "needKwh must be positive: there is nothing to wait for otherwise" }
        require(maxChargeKw > 0 && margin > 0 && margin <= 1) { "maxChargeKw and margin must be positive (margin at most 1)" }
        val safeStart = latestSafeStart(deadline, needKwh, maxChargeKw, margin)
        val actBy = safeStart.minus(startLag)

        fun guarantee() = Decision(Action.GUARANTEE, publicationAt, needKwh, safeStart, actBy)

        if (seconds(now, deadline) <= EPSILON_SECONDS) return guarantee()

        // Earliest the missing prices can be used: not before expected, not before the next slot.
        val effective = maxOf(publicationAt, now.plus(startLag))
        val afterHours = maxOf(0.0, seconds(effective, deadline) / 3600)
        val mustBuy = needKwh - afterHours * maxChargeKw * margin
        if (mustBuy <= EPSILON_KWH) return Decision(Action.WAIT, publicationAt, 0.0, safeStart, actBy)

        val windowEnd = minOf(effective, deadline)
        val eligible = known
            .filter { seconds(now, it.start) >= -EPSILON_SECONDS && !it.end.isAfter(windowEnd) }
            .sortedBy { it.start }
        val capacity = eligible.sumOf { seconds(it.start, it.end) / 3600 } * maxChargeKw
        // Known prices cannot hold what must be bought; picking "cheapest" would miss the deadline.
        if (capacity + EPSILON_KWH < mustBuy) return guarantee()
        return Decision(Action.BUY_NOW, publicationAt, mustBuy, safeStart, actBy, eligible, windowEnd)
    }

    private fun hours(value: Double): Duration = Duration.ofNanos((value * 3_600_000_000_000.0).toLong())

    private fun seconds(from: Instant, to: Instant): Double = Duration.between(from, to).toNanos() / 1e9
}
