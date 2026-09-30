package se.sensnology.spotnav.vehicles

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.planning.ChargeNeed
import se.sensnology.spotnav.testing.HaFixtures

/**
 * The paired target editor's rules: the energy formula pinned against the backend's own `need_kwh`
 * in the vendored fixtures (the same tolerance rule the card's `target-need.test.ts` uses), and the
 * words and choices the dashboard decides.
 */
class PairedTargetTest {
    private fun dashboard(name: String) = Dashboard.parse(HaFixtures.json("dashboard/$name.json"))

    private fun need(soc: Double?, capacity: Double?, target: Double, max: Double? = null, efficiency: Double? = 0.9) =
        TargetNeed.needKwh(soc, capacity, target, max, efficiency)

    // the formula

    @Test fun needIsMinTargetAndCeilingMinusSocTimesCapacityOverEfficiency() {
        assertEquals(0.4 * (77 / 0.9), need(40.0, 77.0, 80.0)!!, 1e-10)
        assertEquals(0.2 * (77 / 0.9), need(40.0, 77.0, 80.0, max = 60.0)!!, 1e-10)
    }

    @Test fun needIsZeroAtOrBelowTheCurrentLevelAndUnknownWithoutFacts() {
        assertEquals(0.0, need(40.0, 77.0, 40.0)!!, 0.0)
        assertEquals(0.0, need(40.0, 77.0, 10.0)!!, 0.0)
        assertEquals(0.0, need(40.0, 77.0, 80.0, max = 30.0)!!, 0.0)
        assertNull(need(40.0, null, 80.0))
        assertNull(need(40.0, 0.0, 80.0))
        assertNull(need(null, 77.0, 80.0))
        assertNull(need(40.0, 77.0, 80.0, efficiency = null))
        assertNull(need(40.0, 77.0, 80.0, efficiency = 0.0))
    }

    @Test fun theTargetIsWholeTheWayPythonRoundsItAndTheLimitWholeDown() {
        assertEquals(
            listOf(80, 82, 80, 81, 0, 2),
            listOf(80.5, 81.5, 80.4, 80.6, 0.5, 1.5).map(TargetNeed::pythonRound)
        )
        assertEquals(80, TargetNeed.effectiveTarget(80.5, null))
        assertEquals(80, TargetNeed.chargeCeiling(80.9))
        assertEquals(100, TargetNeed.chargeCeiling(null))
        assertEquals(100, TargetNeed.chargeCeiling(140.0))
        assertEquals(0, TargetNeed.chargeCeiling(-3.0))
    }

    // against the backend's own figure

    private val carrying = HaFixtures.files("dashboard").map { it.name }.filter { name ->
        val soc = JSONObject(HaFixtures.files("dashboard").first { it.name == name }.readText()).opt("soc")
        soc is JSONObject && !soc.isNull("need_kwh")
    }

    @Test fun theFixturesThatCarryANeedAreTheThreeTargetSocOnes() {
        assertEquals(
            listOf("target_soc_estimated.json", "target_soc_stopped_on_estimate.json", "target_soc_two_vehicles.json"),
            carrying
        )
    }

    @Test fun theAppsFigureMatchesTheBackendsNeedKwhForEveryFixtureThatCarriesOne() {
        for (name in carrying) {
            val soc = Dashboard.parse(HaFixtures.json("dashboard/$name")).soc
            assertNotNull(name, soc)
            soc!!
            val computed = TargetNeed.needKwh(
                soc.value, soc.capacityKwh, soc.targetPercent!!, soc.vehicleMaxPercent, soc.efficiency
            )!!
            // The block states its reading to one decimal while the backend computed from the
            // unrounded one, so the figure can be off by at most half a tenth of a percent of the
            // battery, over efficiency.
            val rounding = 0.05 / 100 * soc.capacityKwh!! / soc.efficiency!!
            assertTrue(
                "$name: computed $computed vs stated ${soc.needKwh}",
                Math.abs(computed - soc.needKwh!!) <= rounding + 0.005
            )
        }
    }

    @Test fun isExactlyTheBackendsFigureToItsTwoDecimalsWhenTheReadingHasNoRoundingToLose() {
        val soc = dashboard("target_soc_two_vehicles").soc!!
        val computed = TargetNeed.needKwh(
            soc.value, soc.capacityKwh, soc.targetPercent!!, soc.vehicleMaxPercent, soc.efficiency
        )!!
        assertEquals(soc.needKwh!!, Math.round(computed * 100) / 100.0, 0.0)
    }

    // what the dashboard decides

    @Test fun targetModeIsAvailableExactlyWhenTheDashboardResolvesACharge() {
        assertTrue(PairedTarget.available(dashboard("target_soc_two_vehicles")))
        assertFalse(PairedTarget.available(dashboard("cheapest_no_site")))
    }

    @Test fun theSliderSpansTheWholeScaleWithNoFloorAtTheCurrentCharge() {
        assertEquals(0, PairedTarget.RANGE.currentPercent)
        assertEquals(100, PairedTarget.RANGE.limitPercent)
        assertEquals(63, PairedTarget.RANGE.clamp(63))
        // The local rule, kept for an unpaired charger, floors at the charge now.
        assertEquals(40, ChargeNeed.targetRange(40.0, 80.0).clamp(10))
    }

    @Test fun theVerdictSaysNoNeedAtOrBelowTheChargeNowAndToLimitAboveTheLimit() {
        val d = dashboard("target_soc_two_vehicles")
        val facts = PairedTarget.facts(d.soc!!, d.vehicles, null)
        assertEquals(40.0, facts.now!!, 0.0)
        assertEquals(80.0, facts.limit!!, 0.0)
        assertEquals(TargetVerdict.NO_NEED, PairedTarget.verdict(facts, 40.0))
        assertEquals(TargetVerdict.NO_NEED, PairedTarget.verdict(facts, 0.0))
        assertEquals(TargetVerdict.NONE, PairedTarget.verdict(facts, 80.0))
        assertEquals(TargetVerdict.TO_LIMIT, PairedTarget.verdict(facts, 90.0))
    }

    @Test fun theStatedNeedIsUsedExactlyForTheSavedTargetAndTheFormulaForAnyOther() {
        val d = dashboard("target_soc_two_vehicles")
        val facts = PairedTarget.facts(d.soc!!, d.vehicles, null)
        assertEquals(34.22, PairedTarget.needKwh(facts, 80.0)!!, 0.0)
        assertEquals(0.0, PairedTarget.needKwh(facts, 30.0)!!, 0.0)
        // Above the vehicle's own limit the charge is capped at it, so 100 needs what 80 needs.
        assertEquals(PairedTarget.needKwh(facts, 80.0)!!, PairedTarget.needKwh(facts, 100.0)!!, 0.01)
        assertEquals(0.3 * (77 / 0.9), PairedTarget.needKwh(facts, 70.0)!!, 1e-9)
    }

    @Test fun aVehiclePickedButNotSavedHasNoReadingSoItsNeedIsUnknownNeverTheOtherVehiclesNumber() {
        val d = dashboard("target_soc_two_vehicles")
        val facts = PairedTarget.facts(d.soc!!, d.vehicles, "vehicle_niro")
        assertTrue(facts.other)
        assertNull(facts.now)
        assertNull(facts.limit) // the Niro states no limit of its own
        assertEquals(64.8, facts.capacityKwh!!, 0.0)
        assertNull(PairedTarget.needKwh(facts, 80.0))
        // Picking the resolved vehicle is not "another one".
        assertFalse(PairedTarget.facts(d.soc!!, d.vehicles, "vehicle_ev6").other)
    }

    @Test fun thePickerOffersVehiclesOnlyWhenThereIsAChoiceAndShowsTheRecordsOwnElseTheResolvedOne() {
        val two = dashboard("target_soc_two_vehicles").soc!!
        assertEquals(listOf("vehicle_ev6", "vehicle_niro"), PairedTarget.choices(two).map { it.id })
        assertEquals("EV6", PairedTarget.vehicleName(two))
        val one = dashboard("target_soc_estimated").soc!!
        assertTrue(PairedTarget.choices(one).isEmpty())
        assertNotNull(PairedTarget.vehicleName(one))
        assertTrue(PairedTarget.choices(null).isEmpty())
    }

    @Test fun theAmpsRangeIsTheChargersOwn() {
        assertEquals(6..32, PairedTarget.ampsRange(dashboard("target_soc_two_vehicles")))
        assertEquals(6..16, PairedTarget.ampsRange(dashboard("charger_states_its_maximum")))
    }
}
