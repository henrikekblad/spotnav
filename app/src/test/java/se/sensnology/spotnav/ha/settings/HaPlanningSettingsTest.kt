package se.sensnology.spotnav.ha.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.testing.SettingsFixtures.fiscal
import se.sensnology.spotnav.testing.SettingsFixtures.override
import se.sensnology.spotnav.testing.SettingsFixtures.parsed
import se.sensnology.spotnav.testing.SettingsFixtures.response
import se.sensnology.spotnav.testing.SettingsFixtures.target

class HaPlanningSettingsTest {
    private fun refusal(code: String, block: () -> Unit) {
        val refusal = assertThrows(HaSettingsFormatException::class.java) { block() }
        assertEquals(code, refusal.code)
    }

    @Test fun parsesAndEncodesTheCompleteValueRoundTrip() {
        val record = response(
            revision = 7,
            areaId = SettingsFixtures.OTHER_AREA,
            overrides = JSONArray().put(
                override(
                    areaId = SettingsFixtures.OTHER_AREA,
                    vat = fiscal(enabled = true, value = 25.0),
                    tax = fiscal(enabled = true),
                    transfer = fiscal(enabled = false)
                )
            ),
            phases = 1,
            amps = 6,
            requestedKwh = 12.25,
            maxPeriods = 8,
            departureEnabled = false,
            departureTime = "00:00",
            driver = "target_soc",
            target = target(vehicleId = "vehicle-1", targetPercent = 80.0)
        )

        val parsedRecord = HaSettingsCodec.parseResponse(record)

        assertFalse(HaSettingsCodec.encode(parsedRecord).has("mode"))
        assertEquals(7, parsedRecord.revision)
        assertEquals(12.25, parsedRecord.requestedKwh, 0.0)
        assertEquals(HaSettingsStrategy.CHEAPEST, parsedRecord.strategy)
        assertEquals(HaSettingsDriver.TARGET_SOC, parsedRecord.driver)
        assertEquals("00:00", parsedRecord.departureTime)
        assertEquals(80.0, parsedRecord.target.targetPercent!!, 0.0)
        // The encoder reproduces the same value, so a parse of it is equal -- and the complete
        // value keeps the revision while the body drops it.
        assertEquals(parsedRecord, HaSettingsCodec.parseResponse(HaSettingsCodec.encode(parsedRecord)))
        val body = HaSettingsCodec.encodeBody(parsedRecord)
        assertTrue(!body.has("revision"))
        assertEquals(HaSettingsCodec.BODY_KEYS, body.keys().asSequence().toSet())
    }

    /** A valid record with one numeric field replaced by raw JSON text, to reach the finite check. */
    private fun overflow(key: String, value: String): JSONObject {
        val encoded = HaSettingsCodec.encode(SettingsFixtures.parsed()).toString()
        val field = Regex("\"$key\":[0-9.]+")
        check(field.containsMatchIn(encoded)) { "the fixture no longer writes $key as a number" }
        return JSONObject(field.replace(encoded, "\"$key\":$value"))
    }

    @Test fun keepsExplicitNullsAndAllThreeFiscalStatesApart() {
        val absent = parsed(areaId = null, phases = null, amps = null)
        assertNull(absent.areaId)
        assertNull(absent.phases)
        assertNull(absent.amps)
        assertEquals(absent, HaSettingsCodec.parseResponse(HaSettingsCodec.encode(absent)))

        val states = listOf(
            fiscal(enabled = false, value = null),
            fiscal(enabled = true, value = 0.0),
            fiscal(enabled = true, value = null)
        ).map { item ->
            HaSettingsCodec.parseResponse(
                response(overrides = JSONArray().put(override(vat = item)))
            ).overrides.single().vat
        }
        assertEquals(
            listOf(HaFiscalValue(false, null), HaFiscalValue(true, 0.0), HaFiscalValue(true, null)),
            states
        )
        assertNotEquals(HaFiscalValue(true, 0.0), HaFiscalValue(true, null))
        assertNotEquals(HaFiscalValue(false, null), HaFiscalValue(true, null))

        // A zero figure is a figure; a zero amount of energy is not an amount.
        assertEquals(
            0.0,
            HaSettingsCodec.parseResponse(response(target = target(targetPercent = 0.0))).target.targetPercent!!,
            0.0
        )
        refusal("invalid_energy", { HaSettingsCodec.parseResponse(response(requestedKwh = 0.0)) })
        refusal("invalid_periods", { HaSettingsCodec.parseResponse(response(maxPeriods = 0)) })
    }

    @Test fun refusesMissingAndUnknownKeysAtEveryLevel() {
        refusal("missing_field", { HaSettingsCodec.parseResponse(response().apply { remove("amps") }) })
        refusal("unknown_field", { HaSettingsCodec.parseResponse(response().put("charger_id", "x")) })
        refusal("missing_field", { HaSettingsCodec.parseResponse(response().apply { remove("revision") }) })
        // A body never states the record's revision.
        refusal(
            "unknown_field",
            { HaSettingsCodec.parseBody(HaSettingsCodec.encode(HaSettingsCodec.parseResponse(response()))) }
        )
        refusal(
            "missing_field",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override().apply { remove("tax") }))) }
        )
        refusal(
            "unknown_field",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override().put("fee", 1)))) }
        )
        refusal(
            "missing_field",
            { HaSettingsCodec.parseResponse(response(target = target().apply { remove("target_percent") })) }
        )
        refusal("unknown_field", { HaSettingsCodec.parseResponse(response(target = target().put("soc", 50))) })
        refusal("missing_field", { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(fiscal()))) })
        refusal("missing_field", { HaSettingsCodec.parseResponse(JSONObject("{\"mode\":null}")) })
    }

    @Test fun refusesWrongTypesRatherThanCoercingThem() {
        // A JSON number is not a boolean, and a string is not a number.
        refusal("invalid_departure", { HaSettingsCodec.parseResponse(response(departureEnabled = 1)) })
        refusal("invalid_departure", { HaSettingsCodec.parseResponse(response(departureEnabled = "true")) })
        refusal("unknown_field", { HaSettingsCodec.parseResponse(response().put("execution_paused", false)) })
        // The retired keys -- the estimated-prices flag, and the vehicle's own figures, which live
        // on the vehicle and are written with `update_vehicle` -- are not part of the record any
        // more.
        refusal("unknown_field", { HaSettingsCodec.parseResponse(response().put("allow_estimated_prices", false)) })
        refusal("unknown_field", { HaSettingsCodec.parseResponse(response().put("consumption_kwh_per_10km", 2.0)) })
        refusal(
            "unknown_field",
            { HaSettingsCodec.parseResponse(response(target = target().put("remembered_capacity_kwh", 60.0))) }
        )
        refusal("invalid_phases", { HaSettingsCodec.parseResponse(response(phases = "3")) })
        refusal("invalid_amps", { HaSettingsCodec.parseResponse(response(amps = "16")) })
        refusal("invalid_amps", { HaSettingsCodec.parseResponse(response(amps = 16.5)) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = "1")) })
        refusal("invalid_energy", { HaSettingsCodec.parseResponse(response(requestedKwh = "20")) })
        refusal(
            "invalid_fiscal",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override(vat = fiscal().apply { put("enabled", 1) })))) }
        )
        refusal(
            "invalid_fiscal",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override(tax = "on")))) }
        )
        refusal("invalid_area", { HaSettingsCodec.parseResponse(response(areaId = 4)) })
        refusal("invalid_area", { HaSettingsCodec.parseResponse(response(areaId = "")) })
        refusal("invalid_target", { HaSettingsCodec.parseResponse(response(target = target(vehicleId = ""))) })
        refusal("invalid_target", { HaSettingsCodec.parseResponse(response(target = "none")) })
        refusal("invalid_area", { HaSettingsCodec.parseResponse(response(overrides = "SE4")) })
    }

    @Test fun refusesNonFiniteAndOutOfRangeNumbers() {
        // org.json refuses to *write* a non-finite number, so one is injected into the JSON text.
        refusal("invalid_energy", { HaSettingsCodec.parseResponse(overflow("requested_kwh", "1e999")) })
        refusal("invalid_energy", { HaSettingsCodec.parseResponse(overflow("requested_kwh", "-1e999")) })
        refusal("invalid_amps", { HaSettingsCodec.parseResponse(response(amps = 0)) })
        refusal("invalid_amps", { HaSettingsCodec.parseResponse(response(amps = 81)) })
        refusal("invalid_amps", { HaSettingsCodec.parseResponse(response(amps = -6)) })
        refusal("invalid_phases", { HaSettingsCodec.parseResponse(response(phases = 2)) })
        refusal("invalid_phases", { HaSettingsCodec.parseResponse(response(phases = 0)) })
        refusal("invalid_periods", { HaSettingsCodec.parseResponse(response(maxPeriods = 9)) })
        refusal("invalid_periods", { HaSettingsCodec.parseResponse(response(maxPeriods = 1.5)) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = -1)) })
        refusal(
            "invalid_fiscal",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override(vat = fiscal(enabled = true, value = -0.5))))) }
        )
        refusal("invalid_target", { HaSettingsCodec.parseResponse(response(target = target(targetPercent = 100.5))) })
        refusal("invalid_target", { HaSettingsCodec.parseResponse(response(target = target(targetPercent = -1.0))) })
    }

    @Test fun refusesUnknownEnumsAndWallTimes() {
        for (legacyMode in listOf("external", "auto_price", 1)) {
            refusal("unknown_field", { HaSettingsCodec.parseResponse(response().put("mode", legacyMode)) })
        }
        assertEquals(HaSettingsStrategy.SOLAR, HaSettingsCodec.parseResponse(response(strategy = "solar")).strategy)
        assertEquals(HaSettingsStrategy.HYBRID, HaSettingsCodec.parseResponse(response(strategy = "hybrid")).strategy)
        // A spelling outside the enum, or a value that is not a string at all, is refused.
        refusal("invalid_strategy", { HaSettingsCodec.parseResponse(response(strategy = "wind")) })
        refusal("invalid_strategy", { HaSettingsCodec.parseResponse(response(strategy = true)) })
        refusal("invalid_driver", { HaSettingsCodec.parseResponse(response(driver = "soc")) })
        refusal("invalid_departure", { HaSettingsCodec.parseResponse(response(departureTime = "7:30")) })
        refusal("invalid_departure", { HaSettingsCodec.parseResponse(response(departureTime = "24:00")) })
        refusal("invalid_departure", { HaSettingsCodec.parseResponse(response(departureTime = "07:60")) })
        refusal("invalid_departure", { HaSettingsCodec.parseResponse(response(departureTime = "07.30")) })
        refusal("invalid_departure", { HaSettingsCodec.parseResponse(response(departureTime = 730)) })
        assertEquals("23:59", HaSettingsCodec.parseResponse(response(departureTime = "23:59")).departureTime)
    }

    @Test fun refusesDuplicateAndIncompleteAreaOverrides() {
        refusal(
            "invalid_area",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override()).put(override()))) }
        )
        refusal(
            "invalid_area",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override(areaId = null)))) }
        )
        refusal(
            "invalid_area",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override(areaId = "")))) }
        )
        refusal(
            "missing_field",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override().apply { remove("vat") }))) }
        )
        refusal(
            "missing_field",
            { HaSettingsCodec.parseResponse(response(overrides = JSONArray().put(override(vat = JSONObject("{}"))))) }
        )
        // Two areas are two records.
        val asSent = HaSettingsCodec.parseResponse(
            response(overrides = JSONArray().put(override()).put(override(areaId = SettingsFixtures.OTHER_AREA)))
        )
        assertEquals(
            listOf(SettingsFixtures.AREA, SettingsFixtures.OTHER_AREA),
            asSent.overrides.map { it.areaId }
        )
        val canonical = HaSettingsCodec.parseBody(HaSettingsCodec.encodeBody(asSent))
        assertEquals(
            listOf(SettingsFixtures.OTHER_AREA, SettingsFixtures.AREA),
            canonical.overrides.map { it.areaId }
        )
        assertEquals(canonical, HaSettingsCodec.parseBody(HaSettingsCodec.encodeBody(canonical)))
    }

    @Test fun nothingInTheModelOrItsTextCarriesASecret() {
        val record = parsed(target = target(vehicleId = "vehicle-1", targetPercent = 50.0))

        val text = listOf(record.toString(), record.target.toString(), HaSettingsCodec.encode(record).toString())
            .joinToString(" ")
        listOf("http", "webhook", "token", "password", "secret", "bearer", "sensor.", "switch.", "number.", "base_url")
            .forEach { banned -> assertTrue("model text leaks $banned", !text.contains(banned)) }
    }

    @Test fun aResponseRevisionMustBeARealWholeNumberWhileABodyHasNoneAtAll() {
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = null)) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = "4")) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = true)) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = 1.5)) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = -1)) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(response(revision = 2147483648L)) })
        refusal("invalid_number", { HaSettingsCodec.parseResponse(overflow("revision", "1e999")) })
        refusal("missing_field", { HaSettingsCodec.parseResponse(response().apply { remove("revision") }) })

        // A stated zero is a revision like any other, and so is the largest one.
        assertEquals(0, HaSettingsCodec.parseResponse(response(revision = 0)).revision)
        assertEquals(Int.MAX_VALUE, HaSettingsCodec.parseResponse(response(revision = Int.MAX_VALUE)).revision)

        // A body carries none, and the model's own revision is 0 there.
        val body = HaSettingsCodec.encodeBody(parsed(revision = 7))
        assertTrue(!body.has("revision"))
        assertEquals(0, HaSettingsCodec.parseBody(body).revision)
        refusal(
            "unknown_field",
            { HaSettingsCodec.parseBody(HaSettingsCodec.encode(HaSettingsCodec.parseResponse(response(revision = 7)))) }
        )
    }
}
