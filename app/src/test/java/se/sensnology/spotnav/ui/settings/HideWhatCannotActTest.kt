package se.sensnology.spotnav.ui.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.heat.Heat
import se.sensnology.spotnav.ha.heat.HeatRead
import se.sensnology.spotnav.ha.house.House
import se.sensnology.spotnav.ha.house.HouseRead
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ha.site.BatteryControlRead
import se.sensnology.spotnav.ha.site.HeatSettings
import se.sensnology.spotnav.ha.site.HeatSettingsRead
import se.sensnology.spotnav.testing.CardStringsXml
import se.sensnology.spotnav.testing.DashboardFixtures
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.ui.charging.ChargerBinding
import se.sensnology.spotnav.ui.charging.ChargingStrategy
import se.sensnology.spotnav.ui.charging.ChargingStrategyPresentation
import se.sensnology.spotnav.ui.site.HeatNowModel
import se.sensnology.spotnav.ui.site.HouseBox
import se.sensnology.spotnav.ui.site.HouseBoxes

/**
 * What the app leaves out where it could not act, by the Home Assistant card's rules (the card's
 * `hide-controls.test.ts`): the battery card without a battery, the heat card without heating, the comfort rows
 * without a path SpotNav steers, the read-only dial, the import target without a meter, car or battery first, the
 * Solar card, Sol and Hybrid without solar, and "Pris" without a battery. An older Home Assistant without the new
 * fields keeps everything as before.
 */
class HideWhatCannotActTest {
    private fun battery(path: String, edit: (JSONObject) -> Unit = {}): BatteryControlRead.Outcome {
        val json = HaFixtures.json(path)
        edit(json)
        return BatteryControlRead.answer(200, json.toString())
    }

    private fun heatSettings(path: String, edit: (JSONObject) -> Unit = {}): HeatSettings {
        val json = HaFixtures.json(path)
        edit(json)
        return (HeatSettingsRead.decode(json) as SiteRead.Read).value
    }

    private fun heat(name: String, edit: (JSONObject) -> Unit = {}): Heat {
        val json = HaFixtures.json("heat_card/$name")
        edit(json)
        return (HeatRead.decode(json) as SiteRead.Read).value
    }

    /** A heat answer with nothing heat-related: no pump, meter, zones, sensors, path or meter entity. */
    private val nothing: (JSONObject) -> Unit = { json ->
        json.put("pump", JSONObject.NULL).put("meter", JSONObject.NULL).put("zones", org.json.JSONArray())
        json.put("choices", JSONObject.NULL)
        json.getJSONObject("config").put("control_path", "none").put("indoor_sensors", org.json.JSONArray())
            .put("heat_meter_entity", JSONObject.NULL)
    }

    // ---- 2: the battery card

    @Test fun theBatteryCardIsLeftOutWhereTheSiteHasNoBattery() {
        val none = battery("webhook/battery_control_success.json")
        assertFalse((none as BatteryControlRead.Outcome.Status).status.batteryFound)
        assertFalse(SiteSettingsShown.batteryCard(none))
        val found = battery("battery_control/live.json")
        assertTrue((found as BatteryControlRead.Outcome.Status).status.batteryFound)
        assertTrue(SiteSettingsShown.batteryCard(found))
        // A failed read keeps the card, with its line.
        assertTrue(SiteSettingsShown.batteryCard(BatteryControlRead.Outcome.Failed(null)))
    }

    @Test fun anOlderHomeAssistantWithoutBatteryFoundKeepsTheBatteryCard() {
        val older = battery("webhook/battery_control_success.json") { it.getJSONObject("status").remove("battery_found") }
        assertTrue((older as BatteryControlRead.Outcome.Status).status.batteryFound)
        assertTrue(SiteSettingsShown.batteryCard(older))
        // A mistyped field is no status.
        val bad = battery("webhook/battery_control_success.json") { it.getJSONObject("status").put("battery_found", "no") }
        assertTrue(bad is BatteryControlRead.Outcome.Failed)
    }

    // ---- 3: the heat settings card

    @Test fun theHeatCardIsOneRowWhereNoHeatingIsFound() {
        assertTrue(SiteSettingsShown.heatRows(heatSettings("webhook/get_heat_success.json")))
        assertTrue(SiteSettingsShown.heatRows(heatSettings("heat_card/villa.json")))
        assertFalse(SiteSettingsShown.heatRows(heatSettings("webhook/get_heat_success.json", nothing)))
        // Each part alone is something found, as in the card's heatFound().
        val parts = listOf<(JSONObject) -> Unit>(
            { it.put("pump", JSONObject().put("name", "NIBE")) },
            { it.put("meter", JSONObject().put("entity_id", "sensor.meter").put("name", "Meter").put("status", "found")) },
            { it.put("zones", org.json.JSONArray().put(JSONObject().put("entity_id", "climate.a").put("name", "A").put("kind", "floor"))) },
            { it.put("choices", JSONObject().put("zones", org.json.JSONArray().put(JSONObject().put("entity_id", "climate.a")))) },
            { it.getJSONObject("config").put("control_path", "relay") },
            { it.getJSONObject("config").put("indoor_sensors", org.json.JSONArray().put("sensor.kok")) },
            { it.getJSONObject("config").put("heat_meter_entity", "sensor.meter") }
        )
        for (part in parts) {
            assertTrue(SiteSettingsShown.heatRows(heatSettings("webhook/get_heat_success.json") { nothing(it); part(it) }))
        }
    }

    @Test fun theNothingFoundRowHasTheCardsWordsInEveryLanguage() {
        assertEquals("Inget hittat", CardStringsXml.words("sv").say("heat.none.value"))
        assertEquals("Nothing found", CardStringsXml.words("en").say("heat.none.value"))
        for (language in CardStringsXml.LANGUAGES) {
            for (key in listOf("heat.none.value", "heat.readOnly", "heat.readOnly.admin", "house.tab.priceOnly")) {
                assertTrue("$language $key", CardStringsXml.words(language).say(key).isNotBlank())
            }
        }
    }

    // ---- 5: night, away and offsets

    @Test fun theComfortRowsNeedAPathSpotNavSteers() {
        val plain = heatSettings("webhook/get_heat_success.json")
        assertEquals("none", plain.controlPath)
        assertFalse(SiteSettingsShown.heatComfort(plain))
        assertTrue(SiteSettingsShown.heatComfort(heatSettings("heat_card/villa.json")))
        for (path in listOf("relay", "sg_ready", "sg_ready_api", "offset")) {
            assertTrue(path, SiteSettingsShown.heatComfort(plain.copy(controlPath = path)))
        }
        assertFalse(SiteSettingsShown.heatComfort(plain.copy(controlPath = "setpoint")))
        // Control already on keeps them, whatever the path.
        assertTrue(SiteSettingsShown.heatComfort(plain.copy(controlEnabled = true)))
    }

    // ---- 6: the read-only dial

    @Test fun onHeatingSpotNavCanOnlyReadTheDialIsReadOnlyWithOneHint() {
        val pump = heat("network_pump.json")
        assertTrue(HeatNowModel.readOnly(pump))
        assertFalse(HeatNowModel.readOnly(heat("villa.json")))
        assertFalse(HeatNowModel.readOnly(heat("sg_api_pump.json")))
        // Control on keeps the dial, even on a path SpotNav does not steer.
        assertFalse(HeatNowModel.readOnly(heat("network_pump.json") { it.getJSONObject("config").put("control_enabled", true) }))
        val sv = CardStringsXml.words("sv")
        assertEquals("SpotNav kan bara läsa av värmen här.", HeatNowModel.readOnlyLine(sv, pump))
        val admin = heat("network_pump.json") { it.put("writable", true) }
        assertEquals(
            "SpotNav kan bara läsa av värmen här – välj styrning under Inställningar → Värme → Styrning.",
            HeatNowModel.readOnlyLine(sv, admin)
        )
        assertEquals("SpotNav can only read the heating here.", HeatNowModel.readOnlyLine(CardStringsXml.words("en"), pump))
    }

    @Test fun anOlderHeatAnswerWithoutControlEnabledOrWritableStillReads() {
        val older = heat("controlled.json") { it.getJSONObject("config").remove("control_enabled"); it.remove("writable") }
        assertFalse(older.config.controlEnabled)
        assertFalse(older.writable)
        // The relay path is steered: the dial stays.
        assertFalse(HeatNowModel.readOnly(older))
        assertTrue(heat("controlled.json").config.controlEnabled)
    }

    // ---- 4: the import target

    @Test fun theImportTargetIsLeftOutWhereTheSiteCannotMeasureItAndNoneIsSet() {
        val pilot = DashboardGridTariff.parse(HaFixtures.json("grid_tariff/pilot_demand.json"))!!.demand!!
        assertTrue(SiteSettingsShown.importTarget(pilot.copy(available = true, reason = null)))
        val unmeasured = pilot.copy(available = false, reason = "needs_total_grid_power", importTargetKw = null)
        assertFalse(SiteSettingsShown.importTarget(unmeasured))
        // A set target stays, so it can be cleared; another reason keeps the row too.
        assertTrue(SiteSettingsShown.importTarget(unmeasured.copy(importTargetKw = 5.0)))
        assertTrue(SiteSettingsShown.importTarget(unmeasured.copy(reason = "no_site")))
    }

    @Test fun theSiteSettingsViewSaysTheSameAsTheDashboard() {
        // The integration's own answer: the view's import_target is the dashboard's demand availability.
        val view = HaFixtures.json("webhook/get_site_settings_view_success.json").getJSONObject("import_target")
        assertTrue(view.getBoolean("available"))
        assertTrue(view.isNull("reason"))
    }

    // ---- 8: the Solar card

    @Test fun theSolarCardIsLeftOutWithNoForecastToChoose() {
        val site = DashboardFixtures.dashboard("hybrid_derived_site_with_forecast.json").site!!
        assertTrue(SiteSettingsShown.solarCard(site))
        assertFalse(SiteSettingsShown.solarCard(site.copy(solarForecastChoices = emptyList(), solarForecastSelected = emptyList())))
        assertTrue(SiteSettingsShown.solarCard(site.copy(solarForecastSelected = emptyList())))
    }

    // ---- 9: Sol and Hybrid without solar

    @Test fun solAndHybridAreLeftOutWithoutSolarButKeptForTotalGridPower() {
        val noSolar = ChargingStrategyPresentation.of(ChargerBinding.UNPAIRED, null, null, null)
        assertEquals(listOf(ChargingStrategy.CHEAPEST), noSolar.choices.filterNot { it.hidden }.map { it.strategy })
        val needsMeter = ChargingStrategyPresentation.of(
            ChargerBinding.UNPAIRED, null, null, null,
            needingTotalGridPower = setOf(HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID)
        )
        assertEquals(ChargingStrategy.entries, needsMeter.choices.filterNot { it.hidden }.map { it.strategy })
        val offered = ChargingStrategyPresentation.of(
            ChargerBinding.UNPAIRED, null, null, null,
            strategyOptions = setOf(HaSettingsStrategy.CHEAPEST, HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID)
        )
        assertEquals(ChargingStrategy.entries, offered.choices.filterNot { it.hidden }.map { it.strategy })
        // A strategy in force stays, even where it is no longer offered.
        val held = noSolar.choices.first { it.strategy == ChargingStrategy.SOLAR }.copy(chosen = true)
        assertFalse(held.hidden)
    }

    // ---- 10: "Pris" without a battery

    @Test fun thePriceBoxIsCalledPriceWithoutABattery() {
        val house: House = (HouseRead.decode(HaFixtures.json("house/villa_battery_sun.json")) as SiteRead.Read).value
        assertEquals("house.tab.price", HouseBoxes.titleKey(HouseBox.PRICE, house))
        assertEquals("house.tab.priceOnly", HouseBoxes.titleKey(HouseBox.PRICE, house.copy(battery = null)))
        assertEquals("house.tab.graph", HouseBoxes.titleKey(HouseBox.GRAPH, house.copy(battery = null)))
        assertEquals("Pris", CardStringsXml.words("sv").say(HouseBoxes.titleKey(HouseBox.PRICE, house.copy(battery = null))))
        assertEquals("Pris och batteri", CardStringsXml.words("sv").say(HouseBoxes.titleKey(HouseBox.PRICE, house)))
        assertEquals("Price", CardStringsXml.words("en").say("house.tab.priceOnly"))
    }
}
