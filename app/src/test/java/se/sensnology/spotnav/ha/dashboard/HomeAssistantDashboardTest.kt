package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.chargers.ChargerCapabilities
import se.sensnology.spotnav.ha.authority.RemotePlan
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.ha.settings.HaSettingsDriver
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.testing.HaFixtures

class HomeAssistantDashboardTest {
    private fun v1(name: String) = HaFixtures.json("dashboard/$name.json")

    @Test fun everyVendoredFixtureDecodes() {
        val files = HaFixtures.files("dashboard")
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val decoded = Dashboard.parse(JSONObject(file.readText()))
            assertTrue("${file.name} has status lines", decoded.status.lines.isNotEmpty())
        }
    }

    @Test fun theWebhookDashboardFixtureDecodesAndItsEnvelopeIsIgnored() {
        val decoded = Dashboard.parse(HaFixtures.json("webhook/dashboard.json"))
        assertTrue(decoded.status.lines.isNotEmpty())
        assertNotNull(decoded.currentRange)
    }

    @Test fun theUnsupportedVersionRefusalIsNotADashboard() {
        assertThrows(DashboardDecodeException::class.java) {
            Dashboard.parse(HaFixtures.json("webhook/dashboard_unsupported_version.json"))
        }
    }

    @Test fun readsTheFiguresThisSliceUses() {
        val d = Dashboard.parse(v1("target_soc_estimated"))
        assertEquals(StatusTone.NORMAL, d.status.tone)
        assertEquals(listOf("auto_planned", "plan_energy", "plan_cost", "plan_distance"), d.status.lines.map { it.code })
        assertEquals(8292.0, d.status.lines[2].params["amount_minor"])
        assertTrue(d.capabilities.refreshVehicle)
        assertTrue(d.capabilities.setChargeLimit)
        assertEquals(6, d.currentRange.minA)
        assertEquals(32, d.currentRange.maxA)
        assertEquals("Europe/Stockholm", d.market.timezone)
        val proposal = d.plan.proposal!!
        assertEquals(34.5, proposal.plannedKwh!!, 1e-9)
        assertEquals("SEK", proposal.costCurrency)
        assertFalse(proposal.unpriced)
        assertEquals(60, proposal.pricedSlots)
        assertEquals(1, proposal.periods.size.coerceAtLeast(1))
        val soc = d.soc!!
        assertEquals(75.1, soc.value!!, 1e-9)
        assertTrue(soc.estimated)
        assertEquals(0.9, soc.efficiency!!, 1e-9)
        assertEquals(77.0, d.vehicles.single().capacityKwh!!, 1e-9)
        assertNull(d.site)
    }

    @Test fun twoVehiclesAreOfferedForTheSocChoice() {
        val d = Dashboard.parse(v1("target_soc_two_vehicles"))
        assertEquals(2, d.soc!!.vehicles.size)
        assertEquals(d.targetVehicleId, d.soc!!.vehicleId)
    }

    @Test fun siteWritabilityIsReadAsStated() {
        assertTrue(Dashboard.parse(v1("cheapest_direct_site_admin")).site!!.writable)
        assertFalse(Dashboard.parse(v1("cheapest_direct_site_read_only")).site!!.writable)
        assertNull(Dashboard.parse(v1("cheapest_no_site")).site)
    }

    @Test fun aWaitingAnswerWithNoPlanOrVehicleStillDecodes() {
        val d = Dashboard.parse(v1("waiting_for_publication"))
        assertNull(d.plan.proposal)
        assertNull(d.plan.installed)
        assertNull(d.soc)
    }

    @Test fun unknownKeysAreIgnoredAtEveryLevel() {
        val json = v1("target_soc_estimated")
        json.put("a_future_section", JSONObject().put("x", 1))
        json.getJSONObject("status").put("future", true)
        json.getJSONObject("charger").getJSONObject("capabilities").put("teleport", true)
        json.getJSONObject("soc").put("future_fact", "y")
        json.getJSONArray("vehicles").getJSONObject(0).put("colour", "red")
        val d = Dashboard.parse(json)
        assertEquals(4, d.status.lines.size)
    }

    @Test fun anUnknownStatusCodeAndToneDecodeAndAreWordedNeutrally() {
        val json = v1("target_soc_estimated")
        json.getJSONObject("status").put(
            "lines",
            JSONArray().put(JSONObject().put("code", "from_the_future").put("params", JSONObject().put("n", 3)))
        )
        json.getJSONObject("status").put("tone", "shouting")
        val d = Dashboard.parse(json)
        assertEquals("from_the_future", d.status.lines.single().code)
        assertEquals(StatusTone.NORMAL, d.status.tone)
        for (language in HaStatusWording.LANGUAGES) {
            val text = HaStatusText.render(d.status, StatusFormat(language, null, null, null), java.time.Instant.now())
            assertEquals(HaStatusWording.text(language, HaStatusWording.UNKNOWN_CODE), text)
        }
    }

    @Test fun aWrongVersionOrAMissingPartRefusesTheAnswer() {
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(v1("target_soc_estimated").put("api_version", 2)) }
        for (part in listOf("status", "current_range", "charger", "market", "plan", "vehicles")) {
            val json = v1("target_soc_estimated")
            json.remove(part)
            assertThrows("without $part", DashboardDecodeException::class.java) { Dashboard.parse(json) }
        }
    }

    @Test fun aPartOfTheWrongKindRefusesTheAnswer() {
        val badStatus = v1("target_soc_estimated").put("status", "fine")
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(badStatus) }
        val badRange = v1("target_soc_estimated")
        badRange.getJSONObject("current_range").put("max_a", "32")
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(badRange) }
        val badSoc = v1("target_soc_estimated")
        badSoc.getJSONObject("soc").put("value", "high")
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(badSoc) }
    }

    @Test fun theRequestAsksForVersionOneOnly() {
        val body = JSONObject(HomeAssistantClient.payload(HomeAssistantCommand("dashboard")))
        assertEquals("dashboard", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertFalse(body.has("settings_api_version"))
    }

    @Test fun theChargerIsIdentifiedByItsOwnBlockAndTheInstanceListsItsChargers() {
        val d = Dashboard.parse(v1("cheapest_direct_site_admin"))
        assertEquals("cheapest_direct_admin", d.chargerId)
        assertEquals("cheapest_direct_admin", d.chargerName)
        assertEquals(
            listOf(StatusCharger("cheapest_no_site", "cheapest_no_site"), StatusCharger("cheapest_direct_admin", "cheapest_direct_admin")),
            d.chargers
        )
    }

    @Test fun aChargerRowWithoutAnIdIsSkippedAndAMissingNameFallsBackToTheId() {
        val json = v1("cheapest_no_site")
        json.put(
            "chargers",
            JSONArray()
                .put(JSONObject().put("id", "one"))
                .put(JSONObject().put("name", "nameless"))
                .put(JSONObject().put("id", 7).put("name", "seven"))
                .put(JSONObject().put("id", "two").put("name", "Two"))
                .put("not an object")
        )

        assertEquals(listOf(StatusCharger("one", "one"), StatusCharger("two", "Two")), Dashboard.parse(json).chargers)
    }

    @Test fun capabilitiesAreReadStrictlyAndOnlyARealTrueOffersAControl() {
        fun capabilities(edit: JSONObject.() -> Unit): ChargerCapabilities {
            val json = v1("cheapest_direct_site_admin")
            json.getJSONObject("charger").getJSONObject("capabilities").apply(edit)
            return Dashboard.parse(json).capabilities
        }

        assertEquals(ChargerCapabilities(refreshVehicle = true, setChargeLimit = true), capabilities { })
        assertEquals(ChargerCapabilities(refreshVehicle = false, setChargeLimit = true), capabilities { put("refresh_vehicle", false) })
        // Not a boolean at all is "cannot".
        assertEquals(ChargerCapabilities(refreshVehicle = false, setChargeLimit = false), capabilities {
            put("refresh_vehicle", "true")
            remove("set_charge_limit")
        })
    }

    @Test fun phaseDetectionIsReadAndAnOutOfRangeCountIsNotDetected() {
        val json = v1("cheapest_no_site")
        assertNull(Dashboard.parse(json).detectedPhases)
        assertEquals("unknown", Dashboard.parse(json).phaseDetectionSource)
        assertEquals("none", Dashboard.parse(json).phaseDetectionConfidence)

        for (phases in 1..3) {
            assertEquals(phases, Dashboard.parse(v1("cheapest_no_site").put("detected_phases", phases)).detectedPhases)
        }
        for (odd in listOf(0, 4, -1)) {
            assertNull(Dashboard.parse(v1("cheapest_no_site").put("detected_phases", odd)).detectedPhases)
        }
    }

    @Test fun strategyOptionsAreReadTolerantlyAndFallBackToCheapestOnly() {
        fun options(value: Any?): Set<HaSettingsStrategy> = Dashboard.parse(
            v1("cheapest_no_site").apply { if (value == null) remove("strategy_options") else put("strategy_options", value) }
        ).strategyOptions

        assertEquals(setOf(HaSettingsStrategy.CHEAPEST), options(null))
        assertEquals(
            setOf(HaSettingsStrategy.CHEAPEST, HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID),
            options(JSONArray(listOf("cheapest", "solar", "hybrid")))
        )
        assertEquals(setOf(HaSettingsStrategy.CHEAPEST), options(JSONArray(listOf("cheapest"))))
        // An unrecognised word is dropped, never refused.
        assertEquals(setOf(HaSettingsStrategy.SOLAR), options(JSONArray(listOf("solar", "wind"))))
        // Nothing recognisable, an empty list, or not a list at all is exactly as permissive as
        // absent.
        assertEquals(setOf(HaSettingsStrategy.CHEAPEST), options(JSONArray(listOf("wind"))))
        assertEquals(setOf(HaSettingsStrategy.CHEAPEST), options(JSONArray()))
        assertEquals(setOf(HaSettingsStrategy.CHEAPEST), options("solar"))
        assertEquals(setOf(HaSettingsStrategy.CHEAPEST), options(42))
        // The fixtures state what the site allows.
        assertEquals(
            setOf(HaSettingsStrategy.CHEAPEST, HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID),
            Dashboard.parse(v1("solar_derived_site")).strategyOptions
        )
    }

    @Test fun strategiesHeldBackForTheTotalGridPowerAreRead() {
        assertEquals(
            setOf(HaSettingsStrategy.SOLAR, HaSettingsStrategy.HYBRID),
            Dashboard.parse(v1("cheapest_direct_site_read_only")).strategiesNeedingTotalGridPower
        )
        assertEquals(emptySet<HaSettingsStrategy>(), Dashboard.parse(v1("solar_derived_site")).strategiesNeedingTotalGridPower)
        assertEquals(
            emptySet<HaSettingsStrategy>(),
            Dashboard.parse(v1("cheapest_no_site").apply { put("strategy", "broken") }).strategiesNeedingTotalGridPower
        )
    }

    @Test fun theControlDecisionIsReadAsTheTwoAxesTheBlockStates() {
        val idle = Dashboard.parse(v1("start_idle")).control!!
        assertEquals(ChargerAction.START, idle.chargerCommand())
        assertEquals(PlannerControl.PAUSE, idle.plannerControl())
        assertEquals(
            listOf("next_period", "until_tomorrow", "until_resumed"),
            idle.pauseChoices
        )

        assertEquals(ChargerAction.STOP, Dashboard.parse(v1("stop_charging")).control!!.chargerCommand())
        assertEquals(PlannerControl.RESUME, Dashboard.parse(v1("resume_active")).control!!.plannerControl())

        // A decision that names nothing to do offers nothing.
        val pending = Dashboard.parse(v1("action_pending")).control!!
        assertNull(pending.chargerCommand())
        assertNull(pending.plannerControl())
    }

    @Test fun aControlBlockThatCannotBeReadIsNoControlAndCostsNothingElse() {
        for (edit in listOf<JSONObject.() -> Unit>(
            { remove("immediate_action") },
            { remove("pause_choices") },
            { put("pause_choices", "next_period") },
            { put("pause_choices", JSONArray().put(7)) }
        )) {
            val json = v1("start_idle")
            json.getJSONObject("control").apply(edit)
            val d = Dashboard.parse(json)
            assertNull(d.control)
            assertEquals(4, d.status.lines.size)
        }
        // A member that is not text is an axis this build cannot read, not a value nobody wrote.
        val odd = v1("start_idle")
        odd.getJSONObject("control").put("immediate_action", 7)
        assertNull(Dashboard.parse(odd).control!!.chargerCommand())
        assertEquals(PlannerControl.PAUSE, Dashboard.parse(odd).control!!.plannerControl())
    }

    @Test fun chargeProgressIsTheObservationOrNothing() {
        assertEquals("unknown", Dashboard.parse(v1("stop_charging")).chargeProgress!!.state)
        assertEquals("start_pending", Dashboard.parse(v1("action_pending")).chargeProgress!!.reason)
        val broken = v1("stop_charging")
        broken.getJSONObject("charge_progress").put("state", "on fire")
        val d = Dashboard.parse(broken)
        assertNull(d.chargeProgress)
        assertTrue(d.status.lines.isNotEmpty())
    }

    @Test fun theLiveFactsAndTheInstalledScheduleAreWhatTheStatusWebhookUsedToReport() {
        val charging = Dashboard.parse(v1("stop_charging"))
        assertTrue(charging.chargingEnabled)
        assertTrue(charging.live.scheduleActive)

        val plan = RemotePlan.of(Dashboard.parse(v1("cheapest_direct_site_admin")))
        assertTrue(plan.installed)
        assertEquals(10, plan.amps)
        assertEquals(1, plan.periods.size)
        assertTrue(plan.hasInstalledPeriods)
        assertFalse(plan.chargingEnabled)
    }

    @Test fun theSettingsRecordIsTheCanonicalOneAndAChargerWithoutOneIsStatedAsNone() {
        val d = Dashboard.parse(v1("cheapest_direct_site_admin"))
        val record = d.settings!!
        assertEquals(1, record.revision)
        assertEquals("SE4", record.areaId)
        assertEquals(HaSettingsStrategy.CHEAPEST, record.strategy)
        assertEquals(HaSettingsDriver.MANUAL_KWH, record.driver)

        // Home Assistant's settings store not being there is `null`, never a default record.
        val none = Dashboard.parse(v1("no_settings"))
        assertNull(none.settings)
        assertNull(none.control!!.chargerCommand())
        assertTrue(none.status.lines.isNotEmpty())
    }

    @Test fun aSettingsRecordThisAppCannotReadRefusesTheAnswer() {
        val badRevision = v1("cheapest_direct_site_admin")
        badRevision.getJSONObject("settings").put("revision", "1")
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(badRevision) }

        val missing = v1("cheapest_direct_site_admin")
        missing.getJSONObject("settings").remove("amps")
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(missing) }

        val badDate = v1("cheapest_direct_site_admin")
        badDate.getJSONObject("settings").put("departure_date", "tomorrow")
        assertThrows(DashboardDecodeException::class.java) { Dashboard.parse(badDate) }
    }

    @Test fun aFieldANewerHomeAssistantAddsIsIgnoredInSettingsAndInEveryOtherBlock() {
        val plain = Dashboard.parse(v1("cheapest_direct_site_admin"))
        val grown = v1("cheapest_direct_site_admin")
        grown.getJSONObject("settings").put("added_by_a_newer_home_assistant", 1)
        grown.getJSONObject("settings").getJSONObject("target").put("added", true)
        grown.put("added_block", JSONObject().put("x", 1))
        for (block in listOf("charger", "control", "charge_progress", "market", "site", "status", "summary", "soc")) {
            grown.optJSONObject(block)?.put("added_by_a_newer_home_assistant", "x")
        }
        grown.optJSONArray("vehicles")?.let { vehicles ->
            for (i in 0 until vehicles.length()) vehicles.getJSONObject(i).put("added", 1)
        }
        val decoded = Dashboard.parse(grown)
        assertEquals(plain.settings, decoded.settings)
        assertEquals(plain.control, decoded.control)
        assertEquals(plain.site, decoded.site)
        assertEquals(plain.chargeProgress, decoded.chargeProgress)
        assertEquals(plain.vehicles, decoded.vehicles)
    }

    @Test fun aDashboardAnsweredWithTheDepartureDateReadsIt() {
        val dated = v1("cheapest_direct_site_admin")
        dated.getJSONObject("settings").put("departure_date", "2026-09-27")
        assertEquals(java.time.LocalDate.of(2026, 9, 27), Dashboard.parse(dated).settings!!.departureDate)
        assertNull(Dashboard.parse(v1("cheapest_direct_site_admin")).settings!!.departureDate)
    }

    @Test fun theWaitingForHistoryDashboardIsQuietAndWordedByItsStatusLine() {
        val waiting = Dashboard.parse(v1("waiting_for_history"))
        assertTrue(waiting.status.lines.any { it.code == "waiting_for_history" })
        assertNotEquals(StatusTone.BLOCKING, waiting.status.tone)
    }

    @Test fun unpricedIsWhatTheProposalAndThePricesCallAPlanWithoutPublishedPrices() {
        val d = Dashboard.parse(v1("charging_without_prices"))
        assertTrue(d.plan.proposal!!.unpriced)
        assertEquals(35, d.plan.proposal!!.unpricedSlots)
        assertEquals(0, d.plan.proposal!!.pricedSlots)
        assertTrue(d.status.lines.any { it.code == "charging_without_prices" })
    }

    @Test fun everyVehicleWithAReadingIsReportedToTheVehicleCard() {
        val both = Dashboard.parse(v1("target_soc_two_vehicles")).readVehicles
        assertEquals(listOf(40.0, 55.0), both.map { it.socPercent })
        assertEquals(listOf("EV6", "Niro"), both.map { it.name })

        // A vehicle whose own reading is null is left out, the other stays.
        val half = v1("target_soc_two_vehicles")
        half.getJSONArray("vehicles").getJSONObject(1).put("soc_percent", JSONObject.NULL)
        assertEquals(listOf("EV6"), Dashboard.parse(half).readVehicles.map { it.name })

        // The dashboard's row carries the reading, with its own figures.
        val estimated = Dashboard.parse(v1("target_soc_estimated")).readVehicles.single()
        assertEquals("<id>", estimated.id)
        assertEquals("EV6", estimated.name)
        assertEquals(75.1, estimated.socPercent, 1e-9)
        assertEquals(77.0, estimated.batteryCapacityKwh!!, 1e-9)
        assertEquals("stored", estimated.capacitySource)

        // A vehicle the dashboard lists without a reading is not a reported one.
        assertTrue(Dashboard.parse(v1("cheapest_no_site")).readVehicles.isEmpty())
        val unread = v1("target_soc_estimated")
        unread.getJSONObject("soc").put("value", JSONObject.NULL)
        unread.getJSONArray("vehicles").getJSONObject(0).put("soc_percent", JSONObject.NULL)
        assertTrue(Dashboard.parse(unread).readVehicles.isEmpty())
    }
}
