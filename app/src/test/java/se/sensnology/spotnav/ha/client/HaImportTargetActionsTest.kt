package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.dashboard.DashboardGridTariff
import se.sensnology.spotnav.ha.dashboard.DemandComponent
import se.sensnology.spotnav.ha.dashboard.HighLoadWindow
import se.sensnology.spotnav.testing.HaFixtures
import java.time.LocalDate
import java.time.YearMonth

/** The webhook's `release_import_target`, `get_power_month` and `update_grid_tariff`, as `docs/api.md` states them. */
class HaImportTargetActionsTest {
    private val pilot get() = DashboardGridTariff.parse(HaFixtures.json("grid_tariff/pilot_demand.json"))!!

    @Test fun theWebhookStatesBothNewCapabilities() {
        val dashboard = Dashboard.parse(HaFixtures.json("webhook/dashboard.json"))
        assertTrue(dashboard.capabilities.releaseImportTarget)
        assertTrue(dashboard.capabilities.getPowerMonth)
    }

    @Test fun aReleaseAsksWithItsVersionAndTheReads() {
        val body = ReleaseImportTarget.payload()
        assertEquals("release_import_target", body.getString("action"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals(WebhookReads.FIELDS.size, body.getJSONArray("reads").length())
    }

    @Test fun aReleaseIsReleasedNothingToReleaseOrAFailure() {
        assertEquals(ReleaseImportTarget.Outcome.Released, ReleaseImportTarget.answer(200, """{"api_version": 1, "ok": true, "error": null, "action": "release_import_target"}"""))
        assertEquals(
            ReleaseImportTarget.Outcome.NothingToRelease("spotnav_no_departure"),
            ReleaseImportTarget.answer(400, """{"api_version": 1, "ok": false, "error": "spotnav_no_departure", "action": "release_import_target"}""")
        )
        assertEquals(ReleaseImportTarget.Outcome.Failed("Unsupported action"), ReleaseImportTarget.answer(400, """{"ok": false, "error": "Unsupported action"}"""))
        assertEquals(ReleaseImportTarget.Outcome.Failed(null), ReleaseImportTarget.answer(null, null))
    }

    @Test fun thePilotsTariffHasTwoPartsTheirMonthAndTheImportTarget() {
        val demand = pilot.demand!!
        assertEquals(2, demand.components.size)
        assertEquals(HighLoadWindow("all", 1, 12, 6, 23), demand.components[0].window)
        assertEquals(45.0, demand.components[0].winter!!, 0.0)
        assertEquals(6.975, demand.perComponent[0].barKw!!, 1e-9)
        assertNull(demand.perComponent[1].barKw)
        assertEquals(6.0, demand.importTargetKw!!, 0.0)
        assertEquals(3.1, demand.hour!!.kwSoFar, 0.0)
        assertEquals("kr/kW", demand.priceUnit)
        assertTrue(pilot.writable)
    }

    @Test fun aTariffWithAnUnknownKeyIsNoTariffAndOneWithoutDemandHasNone() {
        val json = HaFixtures.json("grid_tariff/pilot_demand.json")
        assertNull(DashboardGridTariff.parse(JSONObject(json.toString()).put("extra", 1)))
        json.remove("demand")
        val older = DashboardGridTariff.parse(json)!!
        assertNull(older.demand)
    }

    @Test fun aPartIsWrittenWithTheFeesAsReadAndTheOtherPartsKept() {
        val added = DemandComponent(null, 3, 30.0, null)
        val body = pilot.bodyWith(2, added)
        assertEquals(setOf("transfer_percent", "transfer_high", "high_load_window", "demand_components"), body.keys().asSequence().toSet())
        assertEquals(false, body.getJSONObject("transfer_percent").getBoolean("enabled"))
        assertTrue(body.getJSONObject("transfer_percent").isNull("value"))
        assertTrue(body.isNull("high_load_window"))
        val parts = body.getJSONArray("demand_components")
        assertEquals(3, parts.length())
        assertEquals(added, DemandComponent.parse(parts.get(2)))
        assertEquals(pilot.demand!!.components[0], DemandComponent.parse(parts.get(0)))
        // Removing the first leaves the night part alone.
        val removed = pilot.bodyWith(0, null).getJSONArray("demand_components")
        assertEquals(1, removed.length())
        assertEquals(pilot.demand!!.components[1], DemandComponent.parse(removed.get(0)))
        val write = GridTariffUpdate.payload(0, body)
        assertEquals("update_grid_tariff", write.getString("action"))
        assertEquals(0, write.getInt("expected_write_revision"))
    }

    @Test fun aTariffWriteAnswersTheBlockAgainOrAConflict() {
        val block = HaFixtures.json("grid_tariff/pilot_demand.json").toString()
        val updated = GridTariffUpdate.answer(200, """{"ok": true, "error": null, "field_errors": [], "grid_tariff": $block, "action": "update_grid_tariff"}""")
        assertEquals(2, (updated as GridTariffUpdate.Outcome.Updated).block!!.demand!!.components.size)
        val conflict = GridTariffUpdate.answer(409, """{"ok": false, "error": "revision_conflict", "field_errors": [], "grid_tariff": $block, "action": "update_grid_tariff"}""")
        assertTrue(conflict is GridTariffUpdate.Outcome.Conflict)
        assertEquals(GridTariffUpdate.Outcome.Refused, GridTariffUpdate.answer(400, """{"ok": false, "error": "invalid_fiscal", "field_errors": [{"field": "demand_components", "code": "invalid_value"}], "grid_tariff": null}"""))
    }

    @Test fun aPowerMonthIsAskedByItsMonthOrForTheRunningOne() {
        assertEquals("2026-08", PowerMonthRead.payload(YearMonth.of(2026, 8)).getString("month"))
        assertFalse(PowerMonthRead.payload(null).has("month"))
        assertEquals("get_power_month", PowerMonthRead.payload(null).getString("action"))
    }

    @Test fun theWebhooksPowerMonthKeepsItsDaysOnly() {
        val read = PowerMonthRead.answer(200, HaFixtures.root.resolve("webhook/get_power_month_success.json").readText())
        val month = (read as PowerMonthRead.Outcome.Read).month
        assertEquals(YearMonth.of(2026, 8), month.month)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8)), month.availableMonths)
        assertNull(month.hours)
        assertEquals(31, month.days.size)
        assertEquals(LocalDate.of(2026, 8, 1), month.days[0].date)
        assertTrue(month.days[0].held)
        assertEquals(2, month.bars.size)
    }

    @Test fun theRunningMonthHasItsHoursAndCountedPeaks() {
        val json = HaFixtures.json("power_month/pilot_month.json").put("ok", true)
        val month = (PowerMonthRead.answer(200, json.toString()) as PowerMonthRead.Outcome.Read).month
        assertEquals(512, month.hours!!.size)
        assertTrue(month.days.any { it.counted.isNotEmpty() })
        assertEquals(6.0, month.importTargetKw!!, 0.0)
    }

    @Test fun aRefusalOrAnotherVersionIsNoMonth() {
        val refused = PowerMonthRead.answer(400, HaFixtures.root.resolve("webhook/get_power_month_no_site.json").readText())
        assertEquals(PowerMonthRead.Outcome.Failed("spotnav_no_site"), refused)
        val json = HaFixtures.json("power_month/pilot_month.json").put("ok", true).put("api_version", 2)
        assertEquals(PowerMonthRead.Outcome.Unsupported, PowerMonthRead.answer(200, json.toString()))
        val broken = HaFixtures.json("power_month/pilot_month.json").put("ok", true).put("unit", "W")
        assertEquals(PowerMonthRead.Outcome.Failed(null), PowerMonthRead.answer(200, broken.toString()))
    }
}
