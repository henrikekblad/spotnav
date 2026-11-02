package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.sensnology.spotnav.testing.HaFixtures

class OwnModeActionTest {
    private fun dashboard(line: JSONObject?, fixOwnMode: Boolean = true): Dashboard {
        val json = JSONObject(HaFixtures.root.resolve("dashboard/start_idle.json").readText())
        json.getJSONObject("charger").getJSONObject("capabilities").put("fix_own_mode", fixOwnMode)
        val lines = JSONArray().apply { line?.let(::put) }
        json.getJSONObject("status").put("lines", lines)
        return Dashboard.parse(json)
    }

    private fun own(vararg extra: Pair<String, Any?>) = JSONObject().put("code", "charger_own_mode").put(
        "params",
        JSONObject(mapOf("entity" to "select.p_mode", "label" to "smart charging", "state" to "pure", "value" to "default") + extra)
    )

    @Test fun anOptionIsSetBackToTheValueNamedInHomeAssistantsWords() {
        val action = OwnModeAction.of(dashboard(own("value_name" to "Default")))!!
        assertEquals("select.p_mode", action.entityId)
        assertEquals("default", action.value)
        assertEquals("Set to Default", action.label("en"))
        assertEquals("Ställ in Default", action.label("sv"))
        // Without a name the raw value is shown.
        assertEquals("Set to default", OwnModeAction.of(dashboard(own()))!!.label("en"))
    }

    @Test fun aSwitchIsTurnedOffAndAnEnableSwitchTurnedOn() {
        val off = own("entity" to "switch.p_solar", "value" to "off")
        assertEquals("Turn off", OwnModeAction.of(dashboard(off))!!.label("en"))
        val disabled = JSONObject().put("code", "charger_disabled").put("params", JSONObject(mapOf("entity" to "switch.p_enable")))
        val action = OwnModeAction.of(dashboard(disabled))!!
        assertEquals("on", action.value)
        assertEquals("Turn on", action.label("en"))
    }

    @Test fun noButtonWithoutTheCapabilityASafeValueOrAnEntity() {
        assertNull(OwnModeAction.of(dashboard(own(), fixOwnMode = false)))
        assertNull(OwnModeAction.of(dashboard(own("value" to JSONObject.NULL))))
        assertNull(OwnModeAction.of(dashboard(own("entity" to ""))))
        assertNull(OwnModeAction.of(dashboard(null)))
        assertNull(OwnModeAction.of(null))
    }
}
