package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardForecastChoice
import se.sensnology.spotnav.ha.dashboard.DashboardSite
import se.sensnology.spotnav.ha.dashboard.DashboardVehicle
import se.sensnology.spotnav.testing.HaFixtures
import java.util.Locale

/** The three bounded webhook writes, against the integration's own vendored fixtures. */
class HaWebhookWritesTest {
    private fun fixture(name: String) = HaFixtures.root.resolve("webhook/$name.json").readText()

    // update_vehicle

    @Test fun updateVehicleRequestNamesTheFieldTheValueAndTheValueShown() {
        val body = VehicleUpdate.payload("vehicle_niro", VehicleField.CAPACITY, 64.8, 60.0)
        assertEquals("update_vehicle", body.getString("action"))
        assertEquals(1, body.getInt("version"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals("vehicle_niro", body.getString("vehicle_id"))
        assertEquals(64.8, body.getJSONObject("changes").getDouble("capacity_kwh"), 0.0)
        assertEquals(60.0, body.getJSONObject("expected").getDouble("capacity_kwh"), 0.0)
        // One field only: the other is neither changed nor expected, and no charger id travels.
        assertEquals(setOf("capacity_kwh"), body.getJSONObject("changes").keys().asSequence().toSet())
        assertFalse(body.has("charger_id"))
    }

    @Test fun aFieldThatShowedNothingIsExpectedAsNull() {
        val body = VehicleUpdate.payload("v", VehicleField.CONSUMPTION, 1.7, null)
        assertTrue(body.getJSONObject("expected").isNull("consumption_kwh_per_10km"))
        assertTrue(body.getJSONObject("expected").has("consumption_kwh_per_10km"))
    }

    @Test fun typedValuesAreCheckedAgainstTheCardsRangesAndRoundedToOneDecimal() {
        assertEquals(VehicleUpdate.Check.Valid(64.8), VehicleUpdate.check(VehicleField.CAPACITY, " 64,8 "))
        assertEquals(VehicleUpdate.Check.Valid(64.8), VehicleUpdate.check(VehicleField.CAPACITY, "64.84"))
        assertEquals(VehicleUpdate.Check.Valid(1.0), VehicleUpdate.check(VehicleField.CAPACITY, "1"))
        assertEquals(VehicleUpdate.Check.Valid(500.0), VehicleUpdate.check(VehicleField.CAPACITY, "500"))
        assertEquals(VehicleUpdate.Check.Invalid(VehicleFieldIssue.OUT_OF_RANGE), VehicleUpdate.check(VehicleField.CAPACITY, "0.9"))
        assertEquals(VehicleUpdate.Check.Invalid(VehicleFieldIssue.OUT_OF_RANGE), VehicleUpdate.check(VehicleField.CAPACITY, "500.1"))
        assertEquals(VehicleUpdate.Check.Valid(0.1), VehicleUpdate.check(VehicleField.CONSUMPTION, "0.1"))
        assertEquals(VehicleUpdate.Check.Valid(50.0), VehicleUpdate.check(VehicleField.CONSUMPTION, "50"))
        assertEquals(VehicleUpdate.Check.Invalid(VehicleFieldIssue.OUT_OF_RANGE), VehicleUpdate.check(VehicleField.CONSUMPTION, "0"))
        assertEquals(VehicleUpdate.Check.Invalid(VehicleFieldIssue.NOT_A_NUMBER), VehicleUpdate.check(VehicleField.CONSUMPTION, "abc"))
        assertEquals(VehicleUpdate.Check.Invalid(VehicleFieldIssue.NOT_A_NUMBER), VehicleUpdate.check(VehicleField.CAPACITY, ""))
        assertEquals(VehicleUpdate.Check.Invalid(VehicleFieldIssue.NOT_A_NUMBER), VehicleUpdate.check(VehicleField.CAPACITY, "NaN"))
    }

    @Test fun aCapacityTheVehicleReportsIsNotEditable() {
        fun row(source: String?, kwh: Double?) = DashboardVehicle("v", "V", kwh, source, 1.7, null, null)
        assertFalse(VehicleUpdate.capacityEditable(row("reported", 77.0)))
        assertTrue(VehicleUpdate.capacityEditable(row("stored", 64.8)))
        assertTrue(VehicleUpdate.capacityEditable(row(null, null)))
    }

    @Test fun theOnboardChargerTravelsAsAWholeNumberAndIsExpectedAsShown() {
        val body = VehicleUpdate.payload("vehicle_niro", VehicleField.ONBOARD_PHASES, 1.0, 3.0)
        assertEquals("1", body.getJSONObject("changes").get("onboard_phases").toString())
        assertEquals("3", body.getJSONObject("expected").get("onboard_phases").toString())
        assertEquals(setOf("onboard_phases"), body.getJSONObject("changes").keys().asSequence().toSet())
    }

    @Test fun theDialogWritesTheOnboardChargerOnlyWhenItDiffers() {
        val row = DashboardVehicle("v", "V", 64.8, "stored", 1.7, null, null, onboardPhases = 3)
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(row, "64.8", "1.7", 3))
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(row, "64.8", "1.7", null))
        val write = VehicleUpdate.draft(row, "64.8", "1.7", 1) as VehicleUpdate.Draft.Write
        assertEquals(listOf(VehicleUpdate.FieldChange(VehicleField.ONBOARD_PHASES, 1.0, 3.0)), write.changes)
        assertEquals(
            VehicleUpdate.Draft.Invalid(mapOf(VehicleField.ONBOARD_PHASES to VehicleFieldIssue.OUT_OF_RANGE)),
            VehicleUpdate.draft(row, "64.8", "1.7", 2)
        )
    }

    @Test fun anInvalidOnboardChargerIsRefusedByFieldAndTheRowReadsItsPhases() {
        val refused = JSONObject(fixture("update_vehicle_refused")).apply {
            put("field_errors", org.json.JSONArray().put(JSONObject().put("field", "onboard_phases").put("code", "invalid_onboard_phases")))
        }
        val outcome = VehicleUpdate.answer(400, refused.toString()) as VehicleUpdate.Outcome.Refused
        assertEquals(mapOf(VehicleField.ONBOARD_PHASES to VehicleFieldIssue.OUT_OF_RANGE), outcome.issues)
        val updated = VehicleUpdate.answer(200, fixture("update_vehicle_success")) as VehicleUpdate.Outcome.Updated
        assertEquals(3, updated.row.onboardPhases)
    }

    @Test fun updateVehicleSuccessAdoptsTheReturnedRow() {
        val outcome = VehicleUpdate.answer(200, fixture("update_vehicle_success"))
        val row = (outcome as VehicleUpdate.Outcome.Updated).row
        assertEquals("vehicle_niro", row.id)
        assertEquals(64.8, row.capacityKwh!!, 0.0)
        assertEquals("stored", row.capacitySource)
        assertEquals(1.7, row.consumptionKwhPer10km!!, 0.0)
        assertEquals("sensor.niro_battery", row.socEntityId)
    }

    @Test fun updateVehicleConflictIsSwitchedOnTheCodeNotTheStatus() {
        // 409 in the real world, but the decision is the body's `spotnav_conflict`.
        for (status in listOf(409, 400, 200)) {
            val outcome = VehicleUpdate.answer(status, fixture("update_vehicle_conflict"))
            assertEquals("status $status", 64.8, (outcome as VehicleUpdate.Outcome.Conflict).row!!.capacityKwh!!, 0.0)
        }
        val other = JSONObject(fixture("update_vehicle_conflict")).put("error", "spotnav_something_new").toString()
        assertTrue(VehicleUpdate.answer(409, other) is VehicleUpdate.Outcome.Failed)
    }

    @Test fun updateVehicleRefusalMarksEachFieldByItsCode() {
        val refused = VehicleUpdate.answer(400, fixture("update_vehicle_refused")) as VehicleUpdate.Outcome.Refused
        assertEquals(
            mapOf(
                VehicleField.CAPACITY to VehicleFieldIssue.OUT_OF_RANGE,
                VehicleField.CONSUMPTION to VehicleFieldIssue.OUT_OF_RANGE
            ),
            refused.issues
        )
        assertFalse(refused.unknownVehicle)
        assertNotNull(refused.row)
    }

    @Test fun updateVehicleForAVehicleThatIsGoneSaysSoAndCarriesNoRow() {
        val refused = VehicleUpdate.answer(400, fixture("update_vehicle_unknown_vehicle")) as VehicleUpdate.Outcome.Refused
        assertTrue(refused.unknownVehicle)
        assertTrue(refused.issues.isEmpty())
        assertNull(refused.row)
    }

    @Test fun anUnsupportedVersionAnAnswerlessCallAndGarbageAreNotAdoptedAsAnything() {
        val unsupported = JSONObject(fixture("update_vehicle_refused"))
            .put("error", "spotnav_unsupported_api_version").toString()
        assertEquals(VehicleUpdate.Outcome.NotSupported, VehicleUpdate.answer(400, unsupported))
        assertEquals(VehicleUpdate.Outcome.Failed(null), VehicleUpdate.answer(null, null))
        assertEquals(VehicleUpdate.Outcome.Failed(null), VehicleUpdate.answer(200, "not json"))
        assertEquals(VehicleUpdate.Outcome.Failed(null), VehicleUpdate.answer(200, "{\"ok\":\"yes\"}"))
        // A success with no row is not a success this app can show.
        assertTrue(VehicleUpdate.answer(200, """{"ok":true,"error":null,"field_errors":[]}""") is VehicleUpdate.Outcome.Failed)
    }

    @Test fun additiveKeysInAnAnswerAreIgnored() {
        val extended = JSONObject(fixture("update_vehicle_success")).put("added_later", listOf(1, 2)).toString()
        assertTrue(VehicleUpdate.answer(200, extended) is VehicleUpdate.Outcome.Updated)
    }

    @Test fun displayUsesTheGivenLocale() {
        assertEquals("64,8", VehicleUpdate.display(64.8, Locale.forLanguageTag("sv")))
        assertEquals("64.8", VehicleUpdate.display(64.8, Locale.ROOT))
    }

    // update_site_settings

    @Test fun solarPriorityRequestIsExpectedAndChangedAndNamesNoOtherField() {
        val body = SiteUpdate.priorityRequest("car_first", "battery_first").payload()
        assertEquals("update_site_settings", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals("car_first", body.getJSONObject("expected").getString("solar_priority"))
        assertEquals("battery_first", body.getJSONObject("changes").getString("solar_priority"))
        assertEquals(setOf("solar_priority"), body.getJSONObject("changes").keys().asSequence().toSet())
        assertFalse(body.toString().contains("active_control"))
        assertFalse(body.has("charger_id"))
    }

    @Test fun forecastRequestCarriesTheListsAndNeverActiveControl() {
        val body = SiteUpdate.forecastRequest(listOf("roof_wh"), listOf("roof_wh", "east")).payload()
        assertEquals("roof_wh", body.getJSONObject("expected").getJSONArray("solar_forecast").getString(0))
        assertEquals(2, body.getJSONObject("changes").getJSONArray("solar_forecast").length())
        assertEquals(setOf("solar_forecast"), body.getJSONObject("changes").keys().asSequence().toSet())
        assertFalse(body.toString().contains("active_control"))
    }

    @Test fun aSiteWriteNamesAtLeastOneField() {
        assertThrows(IllegalArgumentException::class.java) { SiteUpdate.Request() }
        val both = SiteUpdate.Request(
            expectedPriority = "car_first", priority = "battery_first",
            expectedForecast = listOf("a"), forecast = listOf("a", "b")
        ).payload()
        assertEquals(setOf("solar_priority", "solar_forecast"), both.getJSONObject("changes").keys().asSequence().toSet())
        assertEquals(setOf("solar_priority", "solar_forecast"), both.getJSONObject("expected").keys().asSequence().toSet())
    }

    private fun site(priority: String? = "car_first", selected: List<String> = listOf("b")) = DashboardSite(
        name = "Home", writable = true, chargerCount = 1, solarPriority = priority, solarForecastSelected = selected,
        solarForecastChoices = listOf(DashboardForecastChoice("a", "A"), DashboardForecastChoice("b", "B"), DashboardForecastChoice("c", "C")),
        activeControlEnabled = false, activeControlAvailable = false, activeControlWritable = false, activeControlReason = null
    )

    @Test fun theSolarDialogSendsBothChangedFieldsInOneRequestInTheChoicesOrder() {
        val body = SiteUpdate.solarRequest(site(), SiteUpdate.BATTERY_FIRST, setOf("c", "a"))!!.payload()
        assertEquals("update_site_settings", body.getString("action"))
        val changes = body.getJSONObject("changes")
        assertEquals("battery_first", changes.getString("solar_priority"))
        assertEquals(listOf("a", "c"), (0 until 2).map { changes.getJSONArray("solar_forecast").getString(it) })
        val expected = body.getJSONObject("expected")
        assertEquals("car_first", expected.getString("solar_priority"))
        assertEquals("b", expected.getJSONArray("solar_forecast").getString(0))
        assertFalse(body.toString().contains("active_control"))
    }

    @Test fun theSolarDialogSendsOnlyWhatDiffersAndNothingWhenNothingDoes() {
        val priorityOnly = SiteUpdate.solarRequest(site(), SiteUpdate.BATTERY_FIRST, setOf("b"))!!.payload()
        assertEquals(setOf("solar_priority"), priorityOnly.getJSONObject("changes").keys().asSequence().toSet())
        val forecastOnly = SiteUpdate.solarRequest(site(), SiteUpdate.CAR_FIRST, emptySet())!!.payload()
        assertEquals(setOf("solar_forecast"), forecastOnly.getJSONObject("changes").keys().asSequence().toSet())
        assertEquals(0, forecastOnly.getJSONObject("changes").getJSONArray("solar_forecast").length())
        assertNull(SiteUpdate.solarRequest(site(), SiteUpdate.CAR_FIRST, setOf("b")))
    }

    @Test fun aSelectedSourceTheChoicesDoNotNameSurvivesTheSolarDialog() {
        val kept = SiteUpdate.solarRequest(site(selected = listOf("gone")), SiteUpdate.BATTERY_FIRST, setOf("gone", "a"))!!.payload()
        assertEquals(listOf("a", "gone"), (0 until 2).map { kept.getJSONObject("changes").getJSONArray("solar_forecast").getString(it) })
    }

    // The vehicle dialog's Save

    private fun row(capacity: Double? = 77.0, source: String? = "stored", consumption: Double? = 2.0) =
        DashboardVehicle("ev6", "EV6", capacity, source, consumption, null, null)

    @Test fun theVehicleDialogSendsBothChangedFieldsInOneRequest() {
        val draft = VehicleUpdate.draft(row(), "80,5", "1.9") as VehicleUpdate.Draft.Write
        val body = VehicleUpdate.payload("ev6", draft.changes)
        assertEquals("update_vehicle", body.getString("action"))
        assertEquals("ev6", body.getString("vehicle_id"))
        assertEquals(80.5, body.getJSONObject("changes").getDouble("capacity_kwh"), 0.0)
        assertEquals(1.9, body.getJSONObject("changes").getDouble("consumption_kwh_per_10km"), 0.0)
        assertEquals(77.0, body.getJSONObject("expected").getDouble("capacity_kwh"), 0.0)
        assertEquals(2.0, body.getJSONObject("expected").getDouble("consumption_kwh_per_10km"), 0.0)
    }

    @Test fun theVehicleDialogSendsOnlyTheFieldThatDiffers() {
        val draft = VehicleUpdate.draft(row(), "77.0", "1.7") as VehicleUpdate.Draft.Write
        assertEquals(listOf(VehicleField.CONSUMPTION), draft.changes.map { it.field })
        assertEquals(setOf("consumption_kwh_per_10km"), VehicleUpdate.payload("ev6", draft.changes).getJSONObject("changes").keys().asSequence().toSet())
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(row(), "77", "2"))
    }

    @Test fun theVehicleDialogRefusesEachBadFieldAndWritesNothing() {
        val draft = VehicleUpdate.draft(row(), "0", "abc") as VehicleUpdate.Draft.Invalid
        assertEquals(VehicleFieldIssue.OUT_OF_RANGE, draft.issues[VehicleField.CAPACITY])
        assertEquals(VehicleFieldIssue.NOT_A_NUMBER, draft.issues[VehicleField.CONSUMPTION])
        // Clearing a figure the row shows is not something the write can do.
        assertTrue(VehicleUpdate.draft(row(), "", "2.0") is VehicleUpdate.Draft.Invalid)
    }

    @Test fun aBlankFieldTheRowShowsNothingForIsLeftAlone() {
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(row(capacity = null, consumption = null), " ", ""))
        val draft = VehicleUpdate.draft(row(capacity = null, consumption = null), "60", "") as VehicleUpdate.Draft.Write
        assertEquals(listOf(VehicleField.CAPACITY), draft.changes.map { it.field })
        assertTrue(VehicleUpdate.payload("ev6", draft.changes).getJSONObject("expected").isNull("capacity_kwh"))
    }

    @Test fun aCapacityTheCarReportsIsReadOnlyInTheVehicleDialog() {
        val reported = row(capacity = 64.8, source = "reported")
        assertEquals(VehicleUpdate.Draft.Unchanged, VehicleUpdate.draft(reported, "999", "2.0"))
        val draft = VehicleUpdate.draft(reported, "999", "1.5") as VehicleUpdate.Draft.Write
        assertEquals(listOf(VehicleField.CONSUMPTION), draft.changes.map { it.field })
    }

    @Test fun selectionsKeepTheChoicesOrderAndWhatTheAppDidNotTouch() {
        val choices = listOf(DashboardForecastChoice("a", "A"), DashboardForecastChoice("b", "B"), DashboardForecastChoice("c", "C"))
        assertEquals(listOf("a", "c"), SiteUpdate.toggled(choices, listOf("c"), "a", true))
        assertEquals(listOf("c"), SiteUpdate.toggled(choices, listOf("a", "c"), "a", false))
        assertEquals(listOf("b", "gone"), SiteUpdate.toggled(choices, listOf("gone"), "b", true))
        assertEquals(emptyList<String>(), SiteUpdate.toggled(choices, listOf("a"), "a", false))
    }

    @Test fun siteAnswersFromTheFixturesAreSwitchedOnTheirCodes() {
        val updated = SiteUpdate.answer(200, fixture("update_site_settings_success")) as SiteUpdate.Outcome.Updated
        assertEquals("battery_first", updated.site.solarPriority)
        assertEquals(listOf("roof_wh"), updated.site.solarForecastSelected)
        assertEquals(listOf(DashboardForecastChoice("roof_wh", "Roof")), updated.site.solarForecastChoices)
        assertFalse(updated.site.activeControlWritable)

        assertTrue(SiteUpdate.answer(409, fixture("update_site_settings_conflict")) is SiteUpdate.Outcome.Conflict)
        assertNotNull((SiteUpdate.answer(409, fixture("update_site_settings_conflict")) as SiteUpdate.Outcome.Conflict).site)
        assertTrue(SiteUpdate.answer(400, fixture("update_site_settings_invalid_value")) is SiteUpdate.Outcome.Refused)
        val notPermitted = SiteUpdate.answer(403, fixture("update_site_settings_not_permitted"))
        assertTrue(notPermitted is SiteUpdate.Outcome.NotPermitted)
        // The code decides, not the status: a "403" carrying a conflict is a conflict.
        assertTrue(SiteUpdate.answer(403, fixture("update_site_settings_conflict")) is SiteUpdate.Outcome.Conflict)
        assertEquals(SiteUpdate.Outcome.Failed(null), SiteUpdate.answer(null, null))
        assertEquals(SiteUpdate.Outcome.Failed(null), SiteUpdate.answer(500, "<html>"))
    }

    @Test fun noSiteAndUnavailableAndUnsupportedAreTheirOwnOutcomes() {
        fun body(code: String) = """{"api_version":1,"ok":false,"error":"$code","site":null}"""
        assertEquals(SiteUpdate.Outcome.Unavailable, SiteUpdate.answer(400, body("spotnav_no_site")))
        assertEquals(SiteUpdate.Outcome.Unavailable, SiteUpdate.answer(400, body("spotnav_site_unavailable")))
        assertEquals(SiteUpdate.Outcome.NotSupported, SiteUpdate.answer(400, body("spotnav_unsupported_api_version")))
        assertEquals(SiteUpdate.Outcome.Failed("spotnav_new_thing"), SiteUpdate.answer(400, body("spotnav_new_thing")))
    }

    @Test fun activeLoadBalancingReasonsFollowTheCardsVocabulary() {
        assertEquals(SiteFacts.Reason.NONE, SiteFacts.reason(null))
        assertEquals(SiteFacts.Reason.DUPLICATE_MEMBERSHIP, SiteFacts.reason("legacy_duplicate_membership"))
        assertEquals(SiteFacts.Reason.NO_COMMANDABLE_CHARGER, SiteFacts.reason("no_commandable_charger"))
        assertEquals(SiteFacts.Reason.MEASUREMENT, SiteFacts.reason("site_measurement_configured_missing"))
        assertEquals(SiteFacts.Reason.UNKNOWN, SiteFacts.reason("something_new"))
    }

    @Test fun solarIsEditableOnlyWhereTheDashboardSaysTheConnectionMayWrite() {
        val admin = Dashboard.parse(HaFixtures.json("dashboard/cheapest_direct_site_admin.json")).site!!
        val readOnly = Dashboard.parse(HaFixtures.json("dashboard/cheapest_direct_site_read_only.json")).site!!
        assertTrue(SiteFacts.solarEditable(admin))
        assertFalse(SiteFacts.solarEditable(readOnly))
        // Over the webhook the active-control switch is never writable, and this app never builds a
        // request for it.
        val webhook = Dashboard.parse(HaFixtures.json("webhook/dashboard.json")).site!!
        assertTrue(webhook.writable)
        assertFalse(webhook.activeControlWritable)
    }

    @Test fun everyWriteAsksForTheWithheldDepartureFields() {
        val reads = listOf(
            VehicleUpdate.payload("v", VehicleField.CAPACITY, 60.0, null),
            SiteUpdate.priorityRequest("car_first", "battery_first").payload()
        ).map { body -> List(body.getJSONArray("reads").length()) { body.getJSONArray("reads").getString(it) } }
        reads.forEach { assertEquals(listOf("departure_date", "departure_weekdays", "fiscal_included", "notifications", "fill_to_limit", "vehicle_ids", "identify_mode", "identify_camera", "identification_status", "solar_no_car_status"), it) }
    }
}
