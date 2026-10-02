package se.sensnology.spotnav.ha.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand
import se.sensnology.spotnav.testing.HaFixtures
import se.sensnology.spotnav.testing.SettingsFixtures

class SettingsUpdateTest {
    private val record = SettingsFixtures.parsed(revision = 4, amps = 16, requestedKwh = 21.5)

    private fun envelope(
        ok: Boolean,
        code: String? = null,
        settings: Any? = null,
        apiVersion: Any? = 1
    ): String = SettingsFixtures.envelope(ok = ok, code = code, settings = settings, apiVersion = apiVersion).toString()

    @Test fun theRequestCarriesTheRevisionBesideACompleteBodyAndNoChargerId() {
        val payload = JSONObject(
            HomeAssistantClient.payload(
                HomeAssistantCommand(action = "settings", expectedRevision = 3, settingsReplacement = record)
            )
        )

        assertEquals(
            setOf("version", "reads", "action", "expected_revision", "settings"),
            payload.keys().asSequence().toSet()
        )
        assertEquals(1, payload.getInt("version"))
        assertEquals("settings", payload.getString("action"))
        assertEquals(3, payload.getInt("expected_revision"))
        val body = payload.getJSONObject("settings")
        assertEquals(HaSettingsCodec.BODY_KEYS, body.keys().asSequence().toSet())
        assertFalse(body.has("revision"))
        assertFalse(body.has("charger_id"))
        // A body never states the revision: the record's own is the store's business.
        assertEquals(record.copy(revision = 0), HaSettingsCodec.parseBody(body))
        // A different action never carries either field, whatever a caller set.
        val start = JSONObject(
            HomeAssistantClient.payload(
                HomeAssistantCommand("start", expectedRevision = 3, settingsReplacement = record)
            )
        )
        assertFalse(start.has("expected_revision"))
        assertFalse(start.has("settings"))
    }

    /**
     * Home Assistant's own answers, byte for byte as its integration's fixtures state them, `pause`
     * included.
     */
    @Test fun readsHomeAssistantsRealAnswers() {
        val success = HaFixtures.json("settings/v1/success.json")
        val outcome = SettingsUpdate.answer(200, success.toString())

        assertTrue("$outcome", outcome is SettingsUpdate.Outcome.Updated)
        val updated = (outcome as SettingsUpdate.Outcome.Updated).settings
        assertEquals(HaSettingsStrategy.SOLAR, updated.strategy)
        assertEquals(2, updated.revision)

        val conflict = SettingsUpdate.answer(409, HaFixtures.json("settings/v1/revision_conflict.json").toString())
        assertEquals(3, (conflict as SettingsUpdate.Outcome.Conflict).current.revision)

        val refused = SettingsUpdate.answer(400, HaFixtures.json("settings/v1/refusal_unknown_field.json").toString())
        assertEquals("unknown_field", (refused as SettingsUpdate.Outcome.Invalid).code)
        assertEquals(5, refused.current?.revision)

        // The webhook's own routing key beside the envelope is the same answer.
        val routed = JSONObject(success.toString()).put("action", "settings").toString()
        assertTrue(SettingsUpdate.answer(200, routed) is SettingsUpdate.Outcome.Updated)

        // A key a newer Home Assistant adds beside the envelope's five is ignored.
        val stray = JSONObject(success.toString()).apply { put("surprise", true) }.toString()
        assertTrue(SettingsUpdate.answer(200, stray) is SettingsUpdate.Outcome.Updated)

        // The dated answers, with and without a departure date.
        val dated = SettingsUpdate.answer(200, HaFixtures.json("settings/v1/success_dated.json").toString())
        assertEquals(java.time.LocalDate.of(2026, 9, 27), (dated as SettingsUpdate.Outcome.Updated).settings.departureDate)
        val invalid = SettingsUpdate.answer(400, HaFixtures.json("settings/v1/refusal_invalid_departure_date.json").toString())
        assertEquals("invalid_departure", (invalid as SettingsUpdate.Outcome.Invalid).code)
    }

    @Test fun readsEveryDocumentedOutcome() {
        val current = SettingsFixtures.response(revision = 5, amps = 10)
        val committed = SettingsFixtures.response(revision = 6, amps = 20)

        assertEquals(
            SettingsUpdate.Outcome.Updated(record),
            SettingsUpdate.answer(200, envelope(ok = true, settings = HaSettingsCodec.encode(record)))
        )
        assertEquals(
            SettingsUpdate.Outcome.Invalid("invalid_amps", SettingsFixtures.parsed(revision = 5, amps = 10)),
            SettingsUpdate.answer(400, envelope(ok = false, code = "invalid_amps", settings = current))
        )
        assertEquals(
            SettingsUpdate.Outcome.Invalid("spotnav_settings_unavailable", null),
            SettingsUpdate.answer(400, envelope(ok = false, code = "spotnav_settings_unavailable"))
        )
        assertEquals(
            SettingsUpdate.Outcome.Conflict(SettingsFixtures.parsed(revision = 5, amps = 10)),
            SettingsUpdate.answer(409, envelope(ok = false, code = "revision_conflict", settings = current))
        )
        assertEquals(
            SettingsUpdate.Outcome.NotCommitted(SettingsFixtures.parsed(revision = 5, amps = 10)),
            SettingsUpdate.answer(500, envelope(ok = false, code = SettingsUpdate.NOT_COMMITTED, settings = current))
        )
        assertEquals(
            SettingsUpdate.Outcome.CommittedButReconcileFailed(SettingsFixtures.parsed(revision = 6, amps = 20)),
            SettingsUpdate.answer(500, envelope(ok = false, code = SettingsUpdate.RECONCILE_FAILED, settings = committed))
        )
        // The two 500s are different facts.
        assertFalse(
            SettingsUpdate.answer(500, envelope(ok = false, code = SettingsUpdate.NOT_COMMITTED, settings = current)) ==
                SettingsUpdate.answer(500, envelope(ok = false, code = SettingsUpdate.RECONCILE_FAILED, settings = committed))
        )
    }

    @Test fun anInconsistentStatusAndBodyIsMalformed() {
        val valid = SettingsFixtures.response(revision = 4)
        val arrayInsteadOfAnObject = SettingsFixtures.envelope(ok = false, code = SettingsUpdate.REVISION_CONFLICT, settings = valid)
            .put("settings", JSONArray()).toString()
        val cases = listOf(
            // A success that says it failed, or carries no record.
            200 to envelope(ok = false, code = "invalid_amps", settings = valid),
            200 to envelope(ok = true),
            200 to envelope(ok = true, settings = JSONObject.NULL),
            200 to envelope(ok = true, code = "invalid_amps", settings = valid),
            // A refusal that claims success, or gives no code.
            400 to envelope(ok = true, settings = valid),
            400 to envelope(ok = false, settings = valid),
            // A conflict with a code that is not the conflict, or nothing to retry against.
            409 to envelope(ok = false, code = "invalid_amps", settings = valid),
            409 to envelope(ok = false, code = SettingsUpdate.REVISION_CONFLICT),
            // A server-side failure whose code this contract does not define, or one that claims a
            // record it does not send.
            500 to envelope(ok = false, code = "spotnav_settings_unknown", settings = valid),
            500 to envelope(ok = false, code = SettingsUpdate.NOT_COMMITTED),
            500 to envelope(ok = false, code = SettingsUpdate.RECONCILE_FAILED),
            500 to envelope(ok = true, code = SettingsUpdate.NOT_COMMITTED, settings = valid),
            // A record this app cannot read is not a record.
            400 to envelope(ok = false, code = "invalid_amps", settings = JSONObject("{\"mode\":5}")),
            409 to arrayInsteadOfAnObject
        )

        cases.forEach { (status, body) ->
            assertEquals("$status ${body.take(60)}", SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(status, body))
        }
        // Not JSON at all, or nothing at all, is the same answer.
        assertEquals(SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(200, "<html>"))
        assertEquals(SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(200, ""))
        assertEquals(SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(400, null))
    }

    @Test fun anEnvelopeOfAnotherVersionIsNotThisContract() {
        val cases = listOf(
            envelope(ok = true, settings = SettingsFixtures.response(), apiVersion = 2),
            envelope(ok = false, code = SettingsUpdate.REVISION_CONFLICT, settings = SettingsFixtures.response(), apiVersion = 2),
            envelope(ok = true, settings = SettingsFixtures.response(), apiVersion = 99)
        )

        cases.forEach { body -> assertEquals(SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(200, body)) }
        // A version this app cannot read is malformed, never "some version".
        assertEquals(SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(200, envelope(ok = true, apiVersion = "1")))
        assertEquals(SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(200, envelope(ok = true, apiVersion = null)))
        assertEquals(
            SettingsUpdate.Outcome.Malformed,
            SettingsUpdate.answer(200, SettingsFixtures.envelope(ok = true).apply { remove("api_version") }.toString())
        )
    }

    @Test fun noAnswerOrAnUndefinedStatusIsUnavailable() {
        assertEquals(SettingsUpdate.Outcome.Unavailable, SettingsUpdate.answer(null, null))
        listOf(404, 429, 502, 302, 401, 204).forEach { status ->
            assertEquals(
                SettingsUpdate.Outcome.Unavailable,
                SettingsUpdate.answer(
                    status,
                    envelope(ok = false, code = SettingsUpdate.REVISION_CONFLICT, settings = SettingsFixtures.response())
                )
            )
        }
        // A server that answers a shape this contract does not define.
        assertEquals(
            SettingsUpdate.Outcome.Malformed,
            SettingsUpdate.answer(
                400,
                JSONObject().apply {
                    put("ok", false)
                    put("error", "Unsupported action")
                }.toString()
            )
        )
    }

    @Test fun onlyTheOutcomesThatCarryARecordReportOne() {
        assertTrue(SettingsUpdate.Outcome.Malformed.record == null)
        assertTrue(SettingsUpdate.Outcome.Unavailable.record == null)
        assertTrue(SettingsUpdate.Outcome.Invalid("invalid_amps", null).record == null)
        assertEquals(record, SettingsUpdate.Outcome.Updated(record).record)
        assertEquals(record, SettingsUpdate.Outcome.Conflict(record).record)
        assertEquals(record, SettingsUpdate.Outcome.NotCommitted(record).record)
        assertEquals(record, SettingsUpdate.Outcome.CommittedButReconcileFailed(record).record)
    }

    @Test fun theEnvelopeNeedsItsFiveKeysAndIgnoresAnAddedOne() {
        val body = SettingsFixtures.envelope(
            ok = true,
            settings = HaSettingsCodec.encode(SettingsFixtures.parsed(revision = 4))
        ).put("charger_id", "x")
        assertTrue(SettingsUpdate.answer(200, body.toString()) is SettingsUpdate.Outcome.Updated)
    }

    @Test fun theEnvelopeShapeIsCompleteAndItsActionIsCheckedWhenPresent() {
        fun good() = SettingsFixtures.envelope(
            ok = true,
            settings = HaSettingsCodec.encode(SettingsFixtures.parsed(revision = 4))
        )

        val valid = SettingsFixtures.response(revision = 4)
        val encoded = HaSettingsCodec.encode(SettingsFixtures.parsed(revision = 4))
        val cases = listOf(
            "a missing pause" to good().apply { remove("pause") },
            "a missing error" to good().apply { remove("error") },
            "a missing settings" to good().apply { remove("settings") },
            "a missing ok" to good().apply { remove("ok") },
            "a missing api_version" to good().apply { remove("api_version") },
            "a wrong action" to SettingsFixtures.envelope(ok = true, settings = encoded, action = "status"),
            "a null action" to SettingsFixtures.envelope(ok = true, settings = encoded, action = null),
            "an error of the wrong type" to SettingsFixtures.envelope(ok = false, settings = valid)
                .apply { put("error", 7) },
            "a blank error" to SettingsFixtures.envelope(ok = false, code = " ", settings = valid),
            "settings that are an array" to good().apply { put("settings", JSONArray()) }
        )
        cases.forEach { (what, body) ->
            assertEquals(what, SettingsUpdate.Outcome.Malformed, SettingsUpdate.answer(200, body.toString()))
        }

        // `settings` may be JSON null in exactly the documented case.
        assertEquals(
            SettingsUpdate.Outcome.Invalid("spotnav_settings_unavailable", null),
            SettingsUpdate.answer(
                400,
                SettingsFixtures.envelope(ok = false, code = "spotnav_settings_unavailable").toString()
            )
        )
        assertEquals(
            SettingsUpdate.Outcome.Malformed,
            SettingsUpdate.answer(
                409,
                SettingsFixtures.envelope(ok = false, code = SettingsUpdate.REVISION_CONFLICT).toString()
            )
        )
        assertEquals(
            SettingsUpdate.Outcome.Malformed,
            SettingsUpdate.answer(200, SettingsFixtures.envelope(ok = true).toString())
        )
        assertEquals(
            SettingsUpdate.Outcome.Malformed,
            SettingsUpdate.answer(
                500,
                SettingsFixtures.envelope(ok = false, code = SettingsUpdate.NOT_COMMITTED).toString()
            )
        )
        assertEquals(
            SettingsUpdate.Outcome.Malformed,
            SettingsUpdate.answer(
                500,
                SettingsFixtures.envelope(ok = false, code = SettingsUpdate.RECONCILE_FAILED).toString()
            )
        )
    }
}
