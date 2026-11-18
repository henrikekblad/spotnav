package se.sensnology.spotnav.ha.site

import se.sensnology.spotnav.ha.heat.HeatWrite
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.testing.HaFixtures

/** The heat settings document and the writes of the Settings screen against the integration's own fixtures. */
class HeatSettingsTest {
    private fun read(path: String): HeatSettings = (HeatSettingsRead.decode(HaFixtures.json(path)) as SiteRead.Read).value

    private val villa get() = read("heat_card/controlled.json")

    @Test fun theWebhooksAnswerDecodes() {
        val plain = read("webhook/get_heat_success.json")
        assertEquals(listOf("sensor.kok", "sensor.ovanvaning"), plain.indoorSensors)
        assertEquals("ovanvaning", plain.nameOf("sensor.ovanvaning"))
        assertEquals(HeatNight(false, 1.0, "23:00", "06:00"), plain.night)
        assertTrue(plain.zones.isEmpty())
        assertEquals("none", plain.controlPath)
        assertFalse(plain.controlEnabled)
    }

    @Test fun aVillaWithZonesOffsetsAndAMeter() {
        val villa = villa
        assertTrue(villa.controlEnabled)
        assertEquals("relay", villa.controlPath)
        assertEquals("switch.larm_utgang_1", villa.controlEntity)
        assertEquals("sensor.varme_effekt", villa.meterEntity)
        assertEquals("Elmätare värme (Nibe + panna) Effekt", villa.nameOf("sensor.varme_effekt"))
        assertEquals(listOf(0.0, 0.0, -1.0, -0.5), villa.zones.map { it.offsetC })
        assertEquals("air", villa.zoneKinds["climate.ovanvaning"])
        assertEquals(-1.0, villa.indoorOffsetOf("sensor.ovanvaning_temperatur"), 0.0)
        assertEquals(0.0, villa.indoorOffsetOf("sensor.inomhus_kok_temperatur"), 0.0)
    }

    @Test fun aRequiredKeyThatIsMissingOrMistypedIsNoDocumentAnUnknownKeyIsNotLookedAt() {
        val json = HaFixtures.json("heat_card/controlled.json")
        assertTrue(HeatSettingsRead.decode(JSONObject(json.toString()).put("brand_new", listOf(1))) is SiteRead.Read)
        for (key in listOf("config", "indoor", "zones")) {
            assertTrue(key, HeatSettingsRead.decode(JSONObject(json.toString()).apply { remove(key) }) is SiteRead.Failed)
        }
        for (key in listOf("night", "zones", "indoor_sensors", "indoor_offsets", "control_path", "control_enabled")) {
            val broken = JSONObject(json.toString()).apply { getJSONObject("config").remove(key) }
            assertTrue(key, HeatSettingsRead.decode(broken) is SiteRead.Failed)
        }
        val badTime = JSONObject(json.toString()).apply { getJSONObject("config").getJSONObject("night").put("start", "25:00") }
        assertTrue(HeatSettingsRead.decode(badTime) is SiteRead.Failed)
        assertEquals(SiteRead.Unsupported, HeatSettingsRead.decode(JSONObject(json.toString()).put("api_version", 2)))
    }

    @Test fun theNightIsWrittenWithTheOldNightExpected() {
        val request = HeatWrite.night(villa, HeatNight(true, 1.5, "22:30", "05:30")).payload()
        assertEquals("update_heat", request.getString("action"))
        assertEquals(1, request.getInt("api_version"))
        val night = request.getJSONObject("changes").getJSONObject("night")
        assertEquals(1.5, night.getDouble("delta_c"), 0.0)
        assertEquals("22:30", night.getString("start"))
        assertEquals("05:30", night.getString("end"))
        assertTrue(night.getBoolean("on"))
        val before = request.getJSONObject("expected").getJSONObject("night")
        assertEquals(1.0, before.getDouble("delta_c"), 0.0)
        assertEquals("23:00", before.getString("start"))
        assertEquals(listOf("night"), request.getJSONObject("changes").keys().asSequence().toList())
    }

    @Test fun aZoneOffsetKeepsTheStoredZonesInTheirStoredOrder() {
        val request = HeatWrite.zoneOffset(villa, "climate.golvvarme_tvattstuga", 0.5).payload()
        val zones = request.getJSONObject("changes").getJSONArray("zones")
        assertEquals(
            villa.zones.map { it.entityId },
            (0 until zones.length()).map { zones.getJSONObject(it).getString("entity_id") }
        )
        assertEquals(listOf(0.0, 0.0, 0.5, -0.5), (0 until zones.length()).map { zones.getJSONObject(it).getDouble("offset_c") })
        val stored = request.getJSONObject("expected").getJSONArray("zones")
        assertEquals(-1.0, stored.getJSONObject(2).getDouble("offset_c"), 0.0)
    }

    @Test fun anIndoorOffsetStaysSparseAndNamesStoredSensorsOnly() {
        val kitchen = "sensor.inomhus_kok_temperatur"
        val up = "sensor.ovanvaning_temperatur"
        fun ids(request: HeatWrite.Request, key: String): List<Pair<String, Double>> {
            val array = request.changes.getJSONArray(key)
            return (0 until array.length()).map { array.getJSONObject(it).let { item -> item.getString("entity_id") to item.getDouble("offset_c") } }
        }
        assertEquals(listOf(kitchen to 0.5, up to -1.0), ids(HeatWrite.indoorOffset(villa, kitchen, 0.5), "indoor_offsets"))
        // Back to zero: the sensor is left out, as stored.
        assertEquals(emptyList<Pair<String, Double>>(), ids(HeatWrite.indoorOffset(villa, up, 0.0), "indoor_offsets"))
        val expected = HeatWrite.indoorOffset(villa, kitchen, 0.5).expected.getJSONArray("indoor_offsets")
        assertEquals(1, expected.length())
        assertEquals(up, expected.getJSONObject(0).getString("entity_id"))
    }

    @Test fun halfDegreesAreTheOnlySteps() {
        assertTrue(HeatWrite.isHalfStep(1.5))
        assertTrue(HeatWrite.isHalfStep(-3.0))
        assertFalse(HeatWrite.isHalfStep(1.3))
    }

    @Test fun everyWriteAnswerHasItsOutcome() {
        fun answer(name: String, status: Int = 200) = HeatWrite.answer(status, HaFixtures.json("webhook/$name.json").toString())
        for (name in listOf("update_heat_success", "update_heat_night", "update_heat_zone_offsets", "update_heat_indoor_offsets", "update_heat_away")) {
            assertEquals(name, HeatWrite.Outcome.Written, answer(name))
        }
        assertEquals(HeatWrite.Outcome.Conflict, answer("update_heat_conflict", 409))
        assertEquals(HeatWrite.Outcome.Refused(listOf("out_of_range")), answer("update_heat_invalid_value", 400))
        for (name in listOf("update_heat_not_permitted", "update_heat_indoor_offsets_not_permitted", "update_heat_zones_not_permitted")) {
            assertEquals(name, HeatWrite.Outcome.NotPermitted, answer(name, 403))
        }
        assertTrue(HeatWrite.answer(null, null) is HeatWrite.Outcome.Failed)
        assertEquals(
            HeatWrite.Outcome.NoSite,
            HeatWrite.answer(400, """{"ok":false,"error":"spotnav_no_site","action":"update_heat"}""")
        )
    }
}
