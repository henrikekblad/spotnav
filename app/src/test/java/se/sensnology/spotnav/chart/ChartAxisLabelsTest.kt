package se.sensnology.spotnav.chart

import org.junit.Assert.assertEquals
import org.junit.Test
import se.sensnology.spotnav.prices.PricePoint
import se.sensnology.spotnav.prices.PriceResult
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The chart's hour labels on the Home Assistant card's `CROSS_AXIS_FROM_NOW` scene (Stockholm, now
 * 2026-10-11 16:00, tomorrow published, so From now runs 16:00 to 2026-10-13 00:00, 32 hours). The
 * positions and texts are the fixture's `labels`, `ticks` and `dayLabels`; the label font is the card's
 * 11 px.
 */
class ChartAxisLabelsTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val now = OffsetDateTime.parse("2026-10-11T16:00:00+02:00").toInstant()
    private val fontPx = 11f

    /** English's `Intl` order, as the card's tests read the day cue. */
    private val english = DateTimeFormatter.ofPattern("d EEE", Locale.ENGLISH)
    private fun dayCue(instant: Instant): String = english.format(instant.atZone(zone))

    private fun day(date: LocalDate): List<PricePoint> = (0 until 96).map { index ->
        PricePoint(date.atStartOfDay(zone).plusMinutes(index * 15L).toOffsetDateTime(), 1.0)
    }

    private val result = PriceResult(day(LocalDate.of(2026, 10, 11)), day(LocalDate.of(2026, 10, 12)), 0L)
    private val range = ChartTimeAxis.of(ChartAxisMode.FROM_NOW, result, zone, now)

    private fun labels(axis: ChartTimeAxis, width: Float): List<ChartAxisLabel> =
        ChartAxisLabels.of(axis, { it / axis.spanMinutes * width }, fontPx, ::dayCue)

    private fun positions(labels: List<ChartAxisLabel>, axis: ChartTimeAxis) = labels.map { it.tick.minute / axis.spanMinutes }

    @Test
    fun fromNowLabelsEveryThreeHoursWithTheDayCueAtMidnight() {
        val shown = labels(range, 480f)
        assertEquals(listOf("18", "21", "12 Mon", "03", "06", "09", "12", "15", "18", "21", "24"), shown.map { it.text })
        assertEquals(listOf(2, 5, 8, 11, 14, 17, 20, 23, 26, 29, 32).map { it / 32f }, positions(shown, range))
        assertEquals(listOf(false, false, true, false, false, false, false, false, false, false, false), shown.map { it.tick.dayStart })
        assertEquals(OffsetDateTime.parse("2026-10-12T00:00:00+02:00").toInstant(), shown[2].tick.instant)
    }

    @Test
    fun whereThreeHourlyLabelsWouldTouchTheSixHourTicksAreLabelled() {
        val shown = labels(range, 280f)
        assertEquals(listOf("18", "12 Mon", "06", "12", "18", "24"), shown.map { it.text })
        assertEquals(listOf(2, 8, 14, 20, 26, 32).map { it / 32f }, positions(shown, range))
        // The ticks themselves read the strip's texts, the midnight "00".
        assertEquals(listOf("18", "00", "06", "12", "18", "24"), range.ticks().map { ChartAxisLabels.text(it.copy(dayStart = false)) { "" } })
    }

    @Test
    fun dayLabelsEveryThreeHoursFromZeroToTwentyFour() {
        val shown = labels(ChartTimeAxis.WallClock, 280f)
        assertEquals(listOf("00", "03", "06", "09", "12", "15", "18", "21", "24"), shown.map { it.text })
        assertEquals((0..8).map { it / 8f }, positions(shown, ChartTimeAxis.WallClock))
    }

    @Test
    fun aMeasuredWiderCueThinsTheLabelsSooner() {
        // At 480 px the estimate fits; a cue drawn twice as wide does not.
        val shown = ChartAxisLabels.of(range, { it / range.spanMinutes * 480f }, fontPx, ::dayCue) { text ->
            if (text == "12 Mon") 80f else 0f
        }
        assertEquals(6, shown.size)
    }
}
