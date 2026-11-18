package se.sensnology.spotnav.ui.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseHistory
import se.sensnology.spotnav.ha.house.HouseHistoryRead
import se.sensnology.spotnav.ha.house.HouseRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant
import java.util.Locale

/** The Graf and Pris tabs' figures and words on the integration's fixtures, as the card's tests have them. */
class HouseGraphModelTest {
    private fun text(language: String = "sv") = SiteText(CardStringsXml.words(language), Locale.forLanguageTag(language))

    private fun house(name: String = "villa_battery_sun.json"): House =
        (HouseRead.decode(HaFixtures.json("house/$name")) as SiteRead.Read).value

    private fun history(name: String): HouseHistory =
        (HouseHistoryRead.decode(HaFixtures.json("house_history/$name")) as SiteRead.Read).value

    @Test fun anHoursBarIsTheMeasuredLoadInThePastAndTheForecastAfter() {
        val h = house().hours
        val past = h.first { it.houseKw != null }
        assertEquals(past.houseKw to true, HouseGraph.houseShown(past))
        val ahead = h.last()
        assertEquals(ahead.houseForecastKw to false, HouseGraph.houseShown(ahead))
        // The heat is never above the house's own figure.
        val hour = past.copy(houseKw = 1.0, heatKw = 3.0)
        assertEquals(1.0, HouseGraph.heatShown(hour)!!, 0.0)
        assertNull(HouseGraph.heatShown(past.copy(heatKw = null)))
    }

    @Test fun theCarStandsOnTheHousesBarAndTheScaleReachesTheSum() {
        val hour = house().hours.first { it.houseKw != null }.copy(houseKw = 2.0, carKw = 7.0)
        assertEquals(2.0, HouseGraph.carBase(hour) { true }, 0.0)
        // Without the house's bar it stands on the heat's, else the axis.
        assertEquals(0.0, HouseGraph.carBase(hour) { it != Series.HOUSE && it != Series.HEAT }, 0.0)
        val hours = listOf(hour)
        assertEquals(9.0, HouseGraph.energyTop(hours, Long.MAX_VALUE, emptySet()), 1e-9)
        // The car hidden by default: the scale is the house's.
        assertEquals(2.0, HouseGraph.energyTop(hours, Long.MAX_VALUE, Series.hiddenByDefault(ChartView.ENERGY)), 1e-9)
    }

    @Test fun theAxisIsWholeNiceSteps() {
        assertEquals(1.0, HouseGraph.axisTop(0.9, 3).first, 1e-9)
        val (top, step) = HouseGraph.axisTop(3.1, 3)
        assertEquals(0.0, top % step, 1e-9)
        assertTrue(top >= 3.1)
    }

    @Test fun anHoursReadoutNamesItsFiguresAndHonoursTheLegend() {
        val t = text()
        val hour = house().hours.first { it.houseKw != null && it.houseForecastKw != null }
        val said = HouseGraph.hourWords(t, hour, house().zone, Long.MAX_VALUE, ChartView.ENERGY, Series.hiddenByDefault(ChartView.ENERGY))
        assertTrue(said, said.contains("(prognos"))
        val without = HouseGraph.hourWords(t, hour, house().zone, Long.MAX_VALUE, ChartView.ENERGY, setOf(Series.FORECAST, Series.CAR))
        assertFalse(without, without.contains("prognos"))
        // The price view says buy and sell, not the load.
        val priced = hour.copy(buy = 150.0, sell = 60.0)
        val price = HouseGraph.hourWords(t, priced, house().zone, Long.MAX_VALUE, ChartView.PRICE, emptySet())
        assertTrue(price, price.contains("150") && price.contains("60"))
        assertFalse(price, price.contains("hus "))
    }

    @Test fun theBatterysFlowIsSaidAsTheCardSaysIt() {
        val month = history("villa_month.json")
        val bucket = month.buckets.first { it.key == "2026-11-16" }
        val said = HouseGraph.flowWords(text(), bucket.batteryFlow!!, month.money)!!
        // 5 kWh given back, worth 15 kr, minus 11 kr it cost, = 3,8 kr; and what was charged.
        assertTrue(said, said.startsWith("urladdat 5,0 kWh, värt 15 kr, minus 11 kr"))
        assertTrue(said, said.contains("= 3,8 kr"))
        assertTrue(said, said.contains("laddade 2,5 kWh för 2,0 kr"))
        assertNull(HouseGraph.flowWords(text(), HouseHistory.BatteryFlow(0.0, 0.0, 0.0, 0.0, 0.0), month.money))
    }

    @Test fun aMonthsBarsStackTheHeatAndTheCarAndTheSunIsALine() {
        val month = history("villa_month.json")
        val energy = HouseGraph.energyBars(text(), Locale("sv"), month)
        assertEquals(30, energy.bars.size)
        assertTrue(energy.series.heat && energy.series.sun)
        val bar = energy.bars.first { it.key == "2026-11-16" }
        assertEquals(listOf(Series.HOUSE, Series.HEAT) + if (energy.series.car) listOf(Series.CAR) else emptyList(), bar.lanes.single().map { it.series })
        assertEquals(5.6, bar.lanes.single().first().value, 0.0)
        assertTrue(bar.words, bar.words.contains("Huset 5,6 kWh") && bar.words.contains("Sol 6,0 kWh"))
        // A day nothing was measured on says so, and has no sun on the line.
        val none = energy.bars.indexOfFirst { it.words.contains("inget uppmätt") }
        if (none >= 0) assertNull(energy.sun[none])
        // The axis names the first, every fifth and the last day.
        assertEquals("1", energy.bars.first().axis)
        assertEquals("30", energy.bars.last().axis)
        assertEquals("5", energy.bars[4].axis)
    }

    @Test fun aYearsBarsAreMonthsAndAnEmptyYearHasNoEnergy() {
        val year = history("villa_year.json")
        assertEquals(12, HouseGraph.energyBars(text(), Locale("sv"), year).bars.size)
        assertEquals("J", HouseGraph.energyBars(text(), Locale("sv"), year).bars[0].axis.uppercase())
        val alone = history("house_alone_year.json")
        assertTrue(HouseGraph.hasEnergy(alone))
        assertFalse(HouseGraph.energyBars(text(), Locale("sv"), alone).series.sun)
        assertFalse(HouseGraph.hasEnergy(history("villa_year.json").copy(buckets = emptyList())))
    }

    @Test fun aPastDaysHoursGetThePricesAndEarningsOfTheHistoryByStart() {
        val day = history("villa_day.json")
        val hours = day.buckets.map { house().hours[0].copy(start = java.time.OffsetDateTime.parse(it.key).toInstant(), buy = null, sell = null) }
        val priced = HouseGraph.withPrices(hours, day)
        assertTrue(priced.any { it.buy != null })
        val earned = HouseGraph.earnedOnHours(hours, day)
        assertEquals(hours.size, earned.size)
        assertEquals(day.buckets.map { it.arbitrage }, earned)
        // An hour with prices of its own is left as it is.
        val own = hours.map { it.copy(buy = 1.0) }
        assertEquals(own, HouseGraph.withPrices(own, day))
        assertEquals(hours.map { null }, HouseGraph.earnedOnHours(hours, history("villa_month.json")))
    }

    @Test fun theCardsWordsAreInAllNineLanguages() {
        for (language in CardStringsXml.LANGUAGES) {
            val t = text(language)
            val month = history("villa_month.json")
            assertNotNull(language, HouseGraph.energyBars(t, Locale.forLanguageTag(language), month).bars.first().words)
            assertNotNull(language, HouseGraph.flowWords(t, month.buckets.first { it.batteryFlow != null }.batteryFlow!!, month.money))
            val hour = house().hours.first { it.houseKw != null }
            assertTrue(language, HouseGraph.hourWords(t, hour, house().zone, Instant.parse("2026-11-14T00:00:00Z").toEpochMilli(), ChartView.PRICE, emptySet()).isNotEmpty())
            for (key in listOf("house.hint", "house.hint.energy", "hist.loading", "hist.failed", "hist.unsupported", "hist.house.noEnergy", "house.value.hour", "house.todayHint", "house.chart.label", "house.chart.priceLabel")) {
                assertTrue("$language $key", t.words.say(key, "amount" to "1").isNotEmpty())
            }
        }
    }
}
