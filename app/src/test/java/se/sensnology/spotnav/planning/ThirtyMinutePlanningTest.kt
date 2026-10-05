package se.sensnology.spotnav.planning

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.chart.ChartNow
import se.sensnology.spotnav.prices.AreaPublication
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.prices.PriceRepository
import se.sensnology.spotnav.prices.PriceResult
import se.sensnology.spotnav.prices.PriceTableModels
import se.sensnology.spotnav.prices.RelayContractVersion
import se.sensnology.spotnav.testing.FakeKeyValueStore
import se.sensnology.spotnav.testing.RelayV2Fixtures
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Great Britain's half-hours on the app's quarter-hour grid: the planner chooses quarter slots from
 * them, the table and the now line read them at 15 and at 60 minutes, and a publication the plan
 * waits for is the market day's.
 */
class ThirtyMinutePlanningTest {
    private val gb = RelayV2Fixtures.area("GB-C")

    @Before
    fun catalogue() {
        PriceMarkets.replace(RelayV2Fixtures.areas(), RelayContractVersion.V2)
        PriceRepository.clearMemoryCacheForTest()
    }

    @After
    fun clear() {
        PriceMarkets.replace(emptyList())
    }

    private fun londonDay(files: List<String>): PriceResult = PriceRepository.load(
        FakeKeyValueStore(), RelayV2Fixtures.transport(files), gb, LocalDate.parse("2026-10-04"), 1_000_000L,
        forceRefresh = true, version = RelayContractVersion.V2
    )

    private fun settings(departureHour: Int = 7, kwh: Int = 1) = WidgetSettings(
        area = "GB-C", chargingPhases = 1, chargingAmps = 10, chargingKwh = kwh.toDouble(),
        useDepartureTime = true, departureHour = departureHour, departureMinute = 0, maxChargingPeriods = 1
    )

    @Test
    fun theCheapestHalfHourIsPlannedAsQuarterSlots() {
        val result = londonDay(listOf("GB-C_2026-10-04.json", "GB-C_2026-10-05.json"))
        val now = OffsetDateTime.parse("2026-10-04T20:10:00+01:00")
        val inputs = LocalPlanningInputs.of(settings())

        val plan = ChargingPlanner.calculate(result, inputs, now)

        assertNotNull(plan)
        // The negative hour of the Paris file of the 5th is 03:00–04:00 London; 1 kWh at 2.3 kW is two
        // quarters, and equal prices take the latest of them.
        assertEquals(OffsetDateTime.parse("2026-10-05T03:30:00+01:00").toInstant(), plan!!.start.toInstant())
        assertEquals(OffsetDateTime.parse("2026-10-05T04:00:00+01:00").toInstant(), plan.end.toInstant())
        assertEquals(0, plan.unpricedSlots)
        // All-in: nothing is added to an Agile price, so the cost is the pence Octopus states.
        val pencePerKwh = -0.01435 * 0.8712 * 100
        assertEquals(2 * ChargingPlanner.powerKw(10, 1) * 0.25 * pencePerKwh / 100, plan.cost, 1e-9)
    }

    @Test
    fun aPlanMayStartInTheSecondQuarterOfAHalfHour() {
        val result = londonDay(listOf("GB-C_2026-10-04.json", "GB-C_2026-10-05.json"))
        // 21:05 rounds up to 21:15, the second quarter of the 21:00 half-hour.
        val now = OffsetDateTime.parse("2026-10-04T21:05:00+01:00")
        val plan = ChargingPlanner.calculate(result, LocalPlanningInputs.of(settings(departureHour = 21, kwh = 1).copy(departureMinute = 45)), now)
        assertNotNull(plan)
        assertEquals(OffsetDateTime.parse("2026-10-04T21:15:00+01:00").toInstant(), plan!!.start.toInstant())
        assertEquals(OffsetDateTime.parse("2026-10-04T21:45:00+01:00").toInstant(), plan.end.toInstant())
    }

    @Test
    fun theLondonEveningWaitsForTheParisPublicationOfTheDayBefore() {
        // Before publication only the file of the 4th is held: today stops at 23:00 London.
        // The index lists both days; only the file of the 4th answers.
        val result = londonDay(listOf("GB-C_2026-10-04.json"))
        assertTrue(result.tomorrow.isEmpty())
        val now = OffsetDateTime.parse("2026-10-04T12:00:00+01:00")
        val waiting = ChargingPlanner.outcome(result, LocalPlanningInputs.of(settings(departureHour = 7, kwh = 2)), now).waiting
        assertNotNull(waiting)
        // The missing hour (23:00 London) is the Paris day of the 5th, expected at Agile's own 16:00 UK
        // time on the 4th plus the margin -- not the day before the London date.
        assertEquals(OffsetDateTime.parse("2026-10-04T16:45:00+01:00").toInstant(), waiting!!.expectedAt.toInstant())
        assertEquals("16:45", waiting.expectedAt.toLocalTime().toString())

        // A list that states no publication time falls back to 13:00 Brussels.
        PriceMarkets.replace(
            RelayV2Fixtures.areas().map { if (it.id == "GB-C") it.copy(publication = AreaPublication.DEFAULT) else it },
            RelayContractVersion.V2
        )
        val fallback = ChargingPlanner.outcome(result, LocalPlanningInputs.of(settings(departureHour = 7, kwh = 2)), now).waiting
        assertEquals(OffsetDateTime.parse("2026-10-04T13:45:00+02:00").toInstant(), fallback!!.expectedAt.toInstant())
    }

    @Test
    fun theTableAndTheNowLineReadHalfHoursAtBothPresentations() {
        val result = londonDay(listOf("GB-C_2026-10-04.json", "GB-C_2026-10-05.json"))
        val now = OffsetDateTime.parse("2026-10-04T20:20:00+01:00")
        val quarter = ChartMarket("GB-C", 15, FiscalInput.OFF, FiscalInput.OFF, FiscalInput.OFF)
        val hour = quarter.copy(intervalMinutes = 60)

        val quarterTable = PriceTableModels.create(result, quarter, now)
        assertEquals(96, quarterTable.rows.size)
        // The current row at 20:20 is 20:15, the second quarter of the 20:00 half-hour, at its price.
        val current = quarterTable.rows[quarterTable.currentIndex]
        assertEquals("20:15", current.position.time.toString())
        assertEquals(quarterTable.rows[quarterTable.currentIndex - 1].today!!.price, current.today!!.price, 0.0)
        assertEquals(24, PriceTableModels.create(result, hour, now).rows.size)

        assertEquals((20 * 60 + 15).toFloat(), ChartNow.currentMarkMinute(quarter, result, now))
        assertEquals((20 * 60 + 30).toFloat(), ChartNow.currentMarkMinute(hour, result, now))
    }
}
