package se.sensnology.spotnav.ui.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.SiteFacts
import se.sensnology.spotnav.ha.client.SiteUpdate
import se.sensnology.spotnav.ha.dashboard.DashboardSummary
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.testing.DashboardFixtures

/** What the paired settings overview states, per card, from the dashboard fixtures. */
class PairedOverviewTest {
    private val twoVehicles = DashboardFixtures.dashboard("target_soc_two_vehicles.json")

    @Test fun eachVehicleHasItsOwnCardWithItsFigures() {
        val cards = PairedOverview.vehicles(twoVehicles)
        assertEquals(listOf("EV6", "Niro"), cards.map { it.name })
        val ev6 = cards[0]
        assertEquals(77.0, ev6.capacityKwh!!, 0.0)
        assertEquals(2.0, ev6.consumptionKwhPer10km!!, 0.0)
        assertEquals(PairedOverview.ChargeLevel.Reading(40), ev6.chargeLevel)
        assertFalse(ev6.capacityReported)
        assertEquals(64.8, cards[1].capacityKwh!!, 0.0)
        assertEquals(1.7, cards[1].consumptionKwhPer10km!!, 0.0)
    }

    @Test fun onlyTheVehicleTheChargerPlansForIsMarked() {
        assertEquals(listOf(true, false), PairedOverview.vehicles(twoVehicles).map { it.planned })
    }

    @Test fun aRowAWriteAdoptedStandsInUntilTheNextDashboard() {
        val adopted = DashboardVehicle("vehicle_niro", "Niro", 70.0, "stored", 1.5, null, "sensor.niro_battery", socPercent = 55.0)
        val cards = PairedOverview.vehicles(twoVehicles, mapOf("vehicle_niro" to adopted))
        assertEquals(70.0, cards[1].capacityKwh!!, 0.0)
        assertEquals(1.5, cards[1].consumptionKwhPer10km!!, 0.0)
        assertEquals(77.0, cards[0].capacityKwh!!, 0.0)
    }

    @Test fun aCapacityTheCarReportsIsMarkedReported() {
        val dashboard = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONArray("vehicles").getJSONObject(0).put("capacity_source", "reported")
        }
        assertTrue(PairedOverview.vehicles(dashboard)[0].capacityReported)
    }

    @Test fun theChargeLevelRowSaysNoSensorOrNoReadingInWordsNotEntityIds() {
        val none = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONArray("vehicles").getJSONObject(1).put("soc_entity_id", JSONObject.NULL).put("soc_percent", JSONObject.NULL)
            remove("summary")
        }
        assertEquals(PairedOverview.ChargeLevel.NoSensor, PairedOverview.vehicles(none)[1].chargeLevel)
        val silent = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            getJSONArray("vehicles").getJSONObject(1).put("soc_percent", JSONObject.NULL)
        }
        assertEquals(PairedOverview.ChargeLevel.NoReading, PairedOverview.vehicles(silent)[1].chargeLevel)
        // No card carries an entity id for the screen to print.
        assertFalse(PairedOverview.vehicles(twoVehicles).toString().contains("sensor."))
    }

    @Test fun noVehicleMeansNoCards() {
        assertTrue(PairedOverview.vehicles(DashboardFixtures.dashboard("cheapest_no_site.json")).isEmpty())
    }

    @Test fun theChargerStatesWhoSetsTheCurrentAndItsRange() {
        val kept = PairedOverview.charger(DashboardFixtures.dashboard("cheapest_direct_site_admin.json"))
        assertEquals(PairedOverview.ChargerCard(PairedOverview.CurrentPath.KEPT_BY_CHARGER, 6, 32), kept.copy(summary = null))
        val set = PairedOverview.charger(DashboardFixtures.dashboard("cheapest_direct_site_admin.json") {
            getJSONObject("charger").getJSONObject("capabilities").put("set_current", true)
            getJSONObject("current_range").put("max_a", 16)
        })
        assertEquals(PairedOverview.ChargerCard(PairedOverview.CurrentPath.SET_BY_SPOTNAV, 6, 16), set.copy(summary = null))
    }

    @Test fun theSiteStatesItsNameChargersAndLoadBalancingWithoutACode() {
        val site = PairedOverview.site(DashboardFixtures.dashboard("cheapest_direct_site_admin.json").site!!)
        assertEquals("Site", site.name)
        assertEquals(1, site.chargers)
        assertFalse(site.activeControlOn)
        assertEquals(SiteFacts.Reason.MEASUREMENT, site.reason)
        assertTrue(site.writable)
    }

    @Test fun aSiteWithoutANameHasNoneAndAnUnknownReasonIsTheGenericOne() {
        val dashboard = DashboardFixtures.dashboard("cheapest_direct_site_admin.json") {
            getJSONObject("site").put("name", "  ")
            getJSONObject("site").getJSONObject("active_control").put("reason", "some_new_code")
        }
        val site = PairedOverview.site(dashboard.site!!)
        assertNull(site.name)
        assertEquals(SiteFacts.Reason.UNKNOWN, site.reason)
    }

    @Test fun aChargerWithNoSiteHasNoSiteOrSolarCard() {
        assertNull(DashboardFixtures.dashboard("cheapest_no_site.json").site)
    }

    @Test fun solarStatesPriorityAndTheSelectedSourcesByTitle() {
        val withForecast = PairedOverview.solar(DashboardFixtures.dashboard("hybrid_derived_site_with_forecast.json").site!!)
        assertEquals(SiteUpdate.CAR_FIRST, withForecast.priority)
        assertEquals(listOf("Roof (hybrid)"), withForecast.forecastTitles)
        assertTrue(withForecast.hasForecastChoices)
        assertTrue(withForecast.editable)
        val none = PairedOverview.solar(DashboardFixtures.dashboard("cheapest_direct_site_admin.json").site!!)
        assertEquals(emptyList<String>(), none.forecastTitles)
        assertFalse(none.hasForecastChoices)
    }

    @Test fun aSelectedSourceWithNoTitleIsLeftOutNeverShownAsACode() {
        val dashboard = DashboardFixtures.dashboard("hybrid_derived_site_with_forecast.json") {
            getJSONObject("site").getJSONObject("solar_forecast").put("selected", JSONArray().put("roof_hybrid").put("raw_code"))
        }
        assertEquals(listOf("Roof (hybrid)"), PairedOverview.solar(dashboard.site!!).forecastTitles)
    }

    @Test fun solarIsReadOnlyWhereTheDashboardSaysTheConnectionMayNotWrite() {
        val readOnly = DashboardFixtures.dashboard("cheapest_direct_site_read_only.json").site!!
        assertFalse(PairedOverview.solar(readOnly).editable)
        assertFalse(PairedOverview.site(readOnly).writable)
    }

    // --- The summary rows ---------------------------------------------------------------------------

    private fun chargerCard(edit: JSONObject.() -> Unit) =
        PairedOverview.charger(DashboardFixtures.dashboard("cheapest_direct_site_admin.json") {
            edit(getJSONObject("summary").getJSONObject("charger"))
        })

    @Test fun eachCurrentPathIsCarriedToTheChargerCardWithItsName() {
        val ocpp = chargerCard { put("current_path", "change_configuration") }
        assertEquals(DashboardSummary.CurrentPath.CHANGE_CONFIGURATION, ocpp.summaryPath)
        val number = chargerCard { put("current_path", "number").put("current_entity_name", "Current limit") }
        assertEquals(DashboardSummary.CurrentPath.NUMBER, number.summaryPath)
        assertEquals("Current limit", number.summary!!.currentEntityName)
        assertEquals(DashboardSummary.CurrentPath.EASEE_DYNAMIC_LIMIT, chargerCard { put("current_path", "easee_dynamic_limit") }.summaryPath)
        assertEquals(DashboardSummary.CurrentPath.NONE, chargerCard { }.summaryPath)
        assertEquals("cheapest direct admin", chargerCard { }.summary!!.startStopName)
        assertTrue(chargerCard { }.showsStartStop)
    }

    @Test fun theEnergyMeterIsANameFoundAutomaticallyOrNotSpecified() {
        assertEquals(PairedOverview.EnergyMeter.Named("Meter"), chargerCard { put("energy_name", "Meter").put("energy_automatic", false) }.energy)
        assertEquals(PairedOverview.EnergyMeter.Automatic, chargerCard { }.energy)
        assertEquals(PairedOverview.EnergyMeter.NotSpecified, chargerCard { put("energy_automatic", false) }.energy)
        assertNull(chargerCard { remove("energy_automatic") }.energy)
    }

    @Test fun withoutASummaryTheChargerCardIsWhatItWasBefore() {
        val card = PairedOverview.charger(DashboardFixtures.dashboard("cheapest_direct_site_admin.json") { remove("summary") })
        assertEquals(PairedOverview.ChargerCard(PairedOverview.CurrentPath.KEPT_BY_CHARGER, 6, 32), card)
        assertFalse(card.showsStartStop)
        assertNull(card.summaryPath)
        assertNull(card.energy)
    }

    @Test fun anUnknownCurrentPathFallsBackToTheTwoWordAnswer() {
        assertNull(chargerCard { put("current_path", "teleport") }.summaryPath)
    }

    @Test fun theSiteCardCarriesFuseMeasurementAndBattery() {
        val direct = DashboardFixtures.dashboard("cheapest_direct_site_admin.json")
        val card = PairedOverview.site(direct.site!!, direct.summary!!.site)
        assertEquals(25.0, card.setup!!.mainFuseA!!, 0.0)
        assertEquals(DashboardSummary.MeasurementMode.DIRECT, card.setup!!.measurementMode)
        assertNull(card.setup!!.batteryName)
        val derived = DashboardFixtures.dashboard("solar_derived_site.json") {
            getJSONObject("summary").getJSONObject("site").put("battery_name", "Home battery")
        }
        val derivedCard = PairedOverview.site(derived.site!!, derived.summary!!.site)
        assertEquals(DashboardSummary.MeasurementMode.DERIVED, derivedCard.setup!!.measurementMode)
        assertEquals("Home battery", derivedCard.setup!!.batteryName)
        assertNull(PairedOverview.site(direct.site!!).setup)
    }

    @Test fun theVehicleCardCarriesTheSensorNameAndIsNotNoSensorThen() {
        val cards = PairedOverview.vehicles(twoVehicles)
        assertEquals("ev6 battery", cards[0].sensorName)
        val unread = DashboardFixtures.dashboard("target_soc_two_vehicles.json") {
            val rows = getJSONArray("vehicles")
            for (i in 0 until rows.length()) rows.getJSONObject(i).put("soc_entity_id", JSONObject.NULL).put("soc_percent", JSONObject.NULL)
            remove("soc")
        }
        val card = PairedOverview.vehicles(unread)[0]
        assertEquals("ev6 battery", card.sensorName)
        assertEquals(PairedOverview.ChargeLevel.NoReading, card.chargeLevel)
    }

    @Test fun aChargeLevelTheSocBlockCarriedForwardIsMarkedEstimatedAndAMeasurementIsNot() {
        val estimated = DashboardFixtures.dashboard("target_soc_estimated.json")
        assertEquals(PairedOverview.ChargeLevel.Reading(75, estimated = true), PairedOverview.vehicles(estimated)[0].chargeLevel)
        val measured = DashboardFixtures.dashboard("target_soc_estimated.json") {
            getJSONObject("soc").put("estimated", false)
        }
        assertEquals(PairedOverview.ChargeLevel.Reading(75, estimated = false), PairedOverview.vehicles(measured)[0].chargeLevel)
        // The other vehicle's card is not marked by this vehicle's estimate.
        assertFalse((PairedOverview.vehicles(twoVehicles)[1].chargeLevel as PairedOverview.ChargeLevel.Reading).estimated)
    }

    @Test fun noEntityIdIsEverInTheSummaryRows() {
        for (name in listOf("cheapest_direct_site_admin.json", "target_soc_two_vehicles.json", "solar_derived_site.json")) {
            val dashboard = DashboardFixtures.dashboard(name)
            val shown = listOf(
                PairedOverview.charger(dashboard).toString(),
                dashboard.site?.let { PairedOverview.site(it, dashboard.summary?.site).toString() }.orEmpty(),
                PairedOverview.vehicles(dashboard).toString()
            ).joinToString()
            assertFalse(name, Regex("\\b(sensor|switch|number|select|binary_sensor)\\.[a-z0-9_]+").containsMatchIn(shown))
        }
    }

    @Test fun severalCarsAreTabsOfOneCardWithThePlannedCarFirstChosen() {
        val cards = PairedOverview.vehicles(twoVehicles)
        val planned = cards.firstOrNull { it.planned }?.id ?: cards[0].id
        assertEquals(planned, PairedOverview.selectedTab(cards, null))
        // A tab once chosen stays chosen while the car is there.
        assertEquals("vehicle_niro", PairedOverview.selectedTab(cards, "vehicle_niro"))
        assertEquals(planned, PairedOverview.selectedTab(cards, "vehicle_gone"))
        // One car is no tabs.
        assertNull(PairedOverview.selectedTab(cards.take(1), null))
        assertNull(PairedOverview.selectedTab(emptyList(), null))
    }

    @Test fun aSiteOrSolarCardSaysWhichChargersItAppliesToOnlyWhenThereAreSeveral() {
        assertFalse(PairedOverview.saysChargers(1))
        assertTrue(PairedOverview.saysChargers(2))
    }
}
