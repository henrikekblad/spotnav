package se.sensnology.spotnav.ha.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.testing.HaFixtures
import java.time.Instant

/** The webhook's `plan_preview`, `dismiss_setup_item` and `fix_own_mode`, as `docs/api.md` states them. */
class HaWebhookActionsTest {
    private val ready = """
        {"api_version": 1, "ok": true, "error": null, "action": "plan_preview", "preview": {
          "state": "proposal_ready", "reason": "ready",
          "plan": {"periods": [{"start": "2026-09-22T13:00:00+02:00", "end": "2026-09-22T15:15:00+02:00"}],
                   "planned_kwh": 15.5, "requested_kwh": 15.5, "power_kw": 6.9,
                   "cost": {"value": 18.4, "currency": "SEK"}, "finish": "2026-09-22T15:15:00+02:00",
                   "unpriced": false, "short_of_deadline": false},
          "status": {"tone": "normal", "lines": []},
          "strip": {"start": "2026-09-22T06:00:00+00:00", "end": "2026-09-22T16:00:00+00:00",
                    "quarters": [{"start": "2026-09-22T06:00:00+00:00", "price": 98.2, "planned": false},
                                 {"start": "2026-09-22T11:00:00+00:00", "price": 70.0, "planned": true}]}}}
    """

    @Test fun aPreviewRequestCarriesTheSettingsBodyOfASaveAndTheReads() {
        val dashboard = Dashboard.parse(org.json.JSONObject(HaFixtures.root.resolve("webhook/dashboard.json").readText()))
        val body = PlanPreview.payload(dashboard.settings!!)
        assertEquals("plan_preview", body.getString("action"))
        assertEquals(1, body.getInt("version"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals(WebhookReads.FIELDS.size, body.getJSONArray("reads").length())
        assertEquals(HaSettingsCodec.encodeBody(dashboard.settings!!).toString(), body.getJSONObject("settings").toString())
        assertNull("a preview names no revision", body.getJSONObject("settings").opt("expected_revision"))
    }

    @Test fun aReadyPreviewGivesThePlanAndTheStrip() {
        val outcome = PlanPreview.answer(200, ready) as PlanPreview.Outcome.Previewed
        val plan = outcome.preview.plan!!
        assertEquals(1, plan.periods.size)
        assertEquals(15.5, plan.plannedKwh, 0.0)
        assertEquals(18.4, plan.cost!!, 0.0)
        assertEquals("SEK", plan.currency)
        assertEquals(Instant.parse("2026-09-22T13:15:00Z"), plan.finish)
        val strip = outcome.preview.strip!!
        assertEquals(listOf(false, true), strip.quarters.map { it.planned })
    }

    @Test fun aPreviewWithNoPlanSaysWhyThroughItsStatusLines() {
        val none = """{"api_version": 1, "ok": true, "error": null, "preview": {"state": "waiting", "reason": "r", "plan": null,
            "status": {"tone": "notice", "lines": [{"code": "waiting_for_publication", "params": {}}]}, "strip": null}}"""
        val outcome = PlanPreview.answer(200, none) as PlanPreview.Outcome.Previewed
        assertNull(outcome.preview.plan)
        assertNull(outcome.preview.strip)
        assertEquals(listOf("waiting_for_publication"), outcome.preview.status.lines.map { it.code })
    }

    @Test fun aRefusedBodyAndARateLimitAreToldApart() {
        val refused = """{"api_version": 1, "ok": false, "error": "invalid_amps", "preview": null, "action": "plan_preview"}"""
        assertEquals(PlanPreview.Outcome.Refused("invalid_amps"), PlanPreview.answer(400, refused))
        val limited = """{"ok": false, "error": "spotnav_rate_limited", "retry_after_s": 1, "action": "plan_preview"}"""
        assertEquals(PlanPreview.Outcome.RateLimited, PlanPreview.answer(429, limited))
        assertEquals(PlanPreview.Outcome.Failed(null), PlanPreview.answer(null, null))
        assertEquals(PlanPreview.Outcome.Failed(null), PlanPreview.answer(200, "{\"ok\": true}"))
        assertTrue(PlanPreview.answer(400, """{"ok": false, "error": "spotnav_body_too_large"}""") is PlanPreview.Outcome.Failed)
    }

    @Test fun dismissingARowSendsItsIdAndTellsAnUndismissibleOne() {
        val body = SetupDismiss.payload("site")
        assertEquals("dismiss_setup_item", body.getString("action"))
        assertEquals("site", body.getString("item"))
        assertEquals(SetupDismiss.Outcome.Dismissed, SetupDismiss.answer(200, """{"ok": true, "action": "dismiss_setup_item"}"""))
        assertEquals(
            SetupDismiss.Outcome.NotDismissible,
            SetupDismiss.answer(400, """{"ok": false, "error": "spotnav_setup_item_not_dismissible", "action": "dismiss_setup_item"}""")
        )
        assertEquals(SetupDismiss.Outcome.Failed(null), SetupDismiss.answer(null, null))
    }

    @Test fun settingTheChargersOwnModeBackSendsTheEntityAndTheValueAndNothingElse() {
        val body = OwnModeFix.payload("select.p_mode", "default")
        assertEquals("fix_own_mode", body.getString("action"))
        assertEquals("select.p_mode", body.getString("entity_id"))
        assertEquals("default", body.getString("value"))
        assertEquals(1, body.getInt("api_version"))
        assertEquals(OwnModeFix.Outcome.Written, OwnModeFix.answer(200, """{"api_version": 1, "ok": true, "error": null, "action": "fix_own_mode"}"""))
        assertEquals(OwnModeFix.Outcome.NoConflict, OwnModeFix.answer(400, """{"ok": false, "error": "spotnav_no_conflict"}"""))
        assertEquals(OwnModeFix.Outcome.Refused, OwnModeFix.answer(502, """{"ok": false, "error": "spotnav_own_mode_failed"}"""))
        assertNotNull(OwnModeFix.answer(500, "x"))
    }
}
