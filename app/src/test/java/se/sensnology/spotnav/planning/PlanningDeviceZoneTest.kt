package se.sensnology.spotnav.planning

import org.junit.Assert.fail
import org.junit.Test
import se.sensnology.spotnav.prices.PriceMarkets
import se.sensnology.spotnav.testing.RelayFixtures
import java.lang.reflect.InvocationTargetException
import java.util.TimeZone

/**
 * A phone in another zone than its price area plans what one at home does: every planner and
 * price-wait scenario runs under several device zones, with the area known to the catalogue and
 * with an empty one (where the planner falls back to the prices' own offset, never the device's).
 */
class PlanningDeviceZoneTest {
    private val zones = listOf("UTC", "America/New_York", "Asia/Tokyo", "Europe/Stockholm")
    private val suites = listOf(ChargingPlannerTest::class.java, PriceWaitTest::class.java, PlanningDecimalEnergyTest::class.java)

    @Test fun everyPlanningScenarioHoldsInEveryDeviceZone() {
        val savedZone = TimeZone.getDefault()
        val savedCatalogue = PriceMarkets.all
        val savedVersion = PriceMarkets.version
        val failures = mutableListOf<String>()
        try {
            for (catalogue in listOf(emptyList(), listOf(RelayFixtures.se4))) {
                PriceMarkets.replace(catalogue)
                for (zone in zones) {
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    for (suite in suites) {
                        suite.methods.filter { it.isAnnotationPresent(Test::class.java) }.forEach { method ->
                            try {
                                method.invoke(suite.getDeclaredConstructor().newInstance())
                            } catch (e: InvocationTargetException) {
                                val where = if (catalogue.isEmpty()) "no catalogue" else "SE4 known"
                                failures += "${suite.simpleName}.${method.name} in $zone ($where): ${e.targetException}"
                            }
                        }
                    }
                }
            }
        } finally {
            TimeZone.setDefault(savedZone)
            PriceMarkets.replace(savedCatalogue, savedVersion)
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
