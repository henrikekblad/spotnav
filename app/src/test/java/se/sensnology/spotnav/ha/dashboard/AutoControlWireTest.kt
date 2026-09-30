package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.testing.DashboardFixtures

/** The dashboard's `control`, read from the dashboard document Home Assistant writes. */
class AutoControlWireTest {
    /** The whole fixture: charging, Auto, no pause -- immediate `stop`, automatic `pause`. */
    private fun dashboardJson(): JSONObject = DashboardFixtures.json("stop_charging.json")

    /** One `control` value as the integration writes it, with every member named. */
    private fun controlJson(
        immediate: Any = "start",
        automatic: Any = "pause",
        choices: Any = org.json.JSONArray(emptyList<String>()),
        immediateReason: Any = JSONObject.NULL,
        automaticReason: Any = JSONObject.NULL
    ): JSONObject = JSONObject()
        .put("immediate_action", immediate)
        .put("immediate_action_reason", immediateReason)
        .put("automatic_action", automatic)
        .put("automatic_action_reason", automaticReason)
        .put("pause_choices", choices)

    /**
     * The decision a status carrying [control] produces -- the whole path from the bytes, so a case
     * is one line of wire text rather than an object built to match the reader's expectations.
     */
    private fun controlFrom(control: JSONObject): AutoControl? =
        Dashboard.parse(withControl(control)).control

    // ---- 1.

    @Test
    fun everyDecisionTheIntegrationWritesArrivesWholeWithBothAxes() {
        // The fixture itself, read from the bytes.
        val charging = Dashboard.parse(dashboardJson()).control
        assertEquals(
            AutoControl(
                AutoControl.ACTION_STOP,
                AutoControl.ACTION_PAUSE,
                listOf(AutoControl.PAUSE_NEXT_PERIOD, AutoControl.PAUSE_UNTIL_TOMORROW, AutoControl.PAUSE_UNTIL_RESUMED)
            ),
            charging
        )
        assertEquals(ChargerAction.STOP, charging!!.chargerCommand())
        assertEquals(PlannerControl.PAUSE, charging.plannerControl())
        assertTrue(charging.untilResumedOffered)

        val idle = controlFrom(
            controlJson(
                immediate = AutoControl.ACTION_START,
                automatic = AutoControl.ACTION_PAUSE,
                choices = org.json.JSONArray(listOf(AutoControl.PAUSE_UNTIL_RESUMED))
            )
        )
        assertEquals(
            AutoControl(
                AutoControl.ACTION_START,
                AutoControl.ACTION_PAUSE,
                listOf(AutoControl.PAUSE_UNTIL_RESUMED)
            ),
            idle
        )
        assertEquals(ChargerAction.START, idle!!.chargerCommand())
        assertEquals(PlannerControl.PAUSE, idle.plannerControl())

        val paused = controlFrom(
            controlJson(immediate = AutoControl.ACTION_START, automatic = AutoControl.ACTION_RESUME)
        )
        assertEquals(AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_RESUME, emptyList()), paused)
        assertEquals(ChargerAction.START, paused!!.chargerCommand())
        assertEquals(PlannerControl.RESUME, paused.plannerControl())

        // A failed pause-clear: the immediate command remains readable, no automatic control yet.
        val unsettled = controlFrom(
            controlJson(
                immediate = AutoControl.ACTION_STOP,
                automatic = AutoControl.ACTION_NONE,
                automaticReason = "pause_clear_failed"
            )
        )
        assertEquals(AutoControl(AutoControl.ACTION_STOP, AutoControl.ACTION_NONE, emptyList()), unsettled)
        assertEquals(ChargerAction.STOP, unsettled!!.chargerCommand())
        assertNull("the failure offers no invented automatic control", unsettled.plannerControl())

        // A Start awaiting the charger's acknowledgement.
        val pending = controlFrom(
            controlJson(
                immediate = AutoControl.ACTION_NONE,
                immediateReason = "action_pending",
                automatic = AutoControl.ACTION_NONE,
                automaticReason = "action_pending"
            )
        )
        assertEquals(AutoControl(AutoControl.ACTION_NONE, AutoControl.ACTION_NONE, emptyList()), pending)
        assertNull(pending!!.chargerCommand())
        assertNull(pending.plannerControl())
    }

    // ---- 2.

    @Test
    fun aControlThatIsNotTheContractIsNoControlAtAll() {
        // The value itself, in shapes that are not the members.
        val values = listOf(
            "an action name" to "stop",
            "a number" to 7,
            "a list" to org.json.JSONArray(listOf("until_resumed")),
            "JSON null" to JSONObject.NULL
        )
        for ((why, value) in values) {
            val parsed = Dashboard.parse(withControl(value))
            assertNull("$why is not a decision", parsed.control)
            assertTheRestOfTheDashboardSurvives(parsed)
        }

        // And the object itself: a required member missing, or the fold this contract never had.
        fun without(key: String): JSONObject = JSONObject(controlJson().toString()).apply { remove(key) }
        val members = listOf(
            without("immediate_action") to "no `immediate_action`",
            without("immediate_action_reason") to "no `immediate_action_reason`",
            without("automatic_action") to "no `automatic_action`",
            without("automatic_action_reason") to "no `automatic_action_reason`",
            without("pause_choices") to "no `pause_choices`",
            JSONObject() to "an empty object",
            JSONObject()
                .put("primary_action", "stop")
                .put("pause_choices", org.json.JSONArray(emptyList<String>())) to "a folded action"
        )
        for ((control, why) in members) {
            val parsed = Dashboard.parse(withControl(control))
            assertNull("$why is not a decision", parsed.control)
            assertTheRestOfTheDashboardSurvives(parsed)
        }

        // More members are how the contract grows: the five that matter are still the decision.
        assertEquals(
            AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_PAUSE, emptyList()),
            controlFrom(controlJson().put("can_act", true).put("execution_error", JSONObject.NULL))
        )
    }

    // ---- 3.

    @Test
    fun oneUnreadableHalfDoesNotEraseTheOther() {
        // A member whose JSON type is not what it must be.
        val brokenImmediate = listOf(
            "a number" to 7,
            "a boolean" to true,
            "an object" to JSONObject("""{"action": "stop"}"""),
            "a list" to org.json.JSONArray(listOf("stop")),
            "JSON null" to JSONObject.NULL,
            "an unknown action" to "resume_later"
        )
        for ((why, member) in brokenImmediate) {
            val parsed = Dashboard.parse(
                withControl(
                    controlJson(
                        immediate = member,
                        automatic = AutoControl.ACTION_PAUSE,
                        choices = org.json.JSONArray(listOf(AutoControl.PAUSE_UNTIL_RESUMED))
                    )
                )
            )
            assertNull("an immediate member that is $why: no command", parsed.control?.immediateAction)
            assertNull(parsed.control?.chargerCommand())
            assertEquals(
                "and the automatic axis is still read",
                AutoControl.ACTION_PAUSE,
                parsed.control?.automaticAction
            )
            assertEquals(PlannerControl.PAUSE, parsed.control?.plannerControl())
            assertTheRestOfTheDashboardSurvives(parsed)
        }

        // The mirror image.
        val brokenAutomatic = listOf(
            "a number" to 7,
            "a boolean" to false,
            "an object" to JSONObject("""{"action": "pause"}"""),
            "JSON null" to JSONObject.NULL,
            "an unknown action" to "hibernate",
            "an action from the other axis" to AutoControl.ACTION_START
        )
        for ((why, member) in brokenAutomatic) {
            val parsed = Dashboard.parse(
                withControl(controlJson(immediate = AutoControl.ACTION_STOP, automatic = member))
            )
            assertEquals(
                "an automatic member that is $why: the immediate axis is still read",
                AutoControl.ACTION_STOP,
                parsed.control?.immediateAction
            )
            assertEquals(ChargerAction.STOP, parsed.control?.chargerCommand())
            assertNull("and no automatic control is offered", parsed.control?.automaticAction)
            assertNull(parsed.control?.plannerControl())
            assertTheRestOfTheDashboardSurvives(parsed)
        }

        // each is that half not being a decision, and only that half.
        for ((why, reason) in listOf(
            "a number" to 7,
            "an unknown code" to "because",
            "a planning-only code on the immediate axis" to "pause_unsettled"
        )) {
            val parsed = Dashboard.parse(
                withControl(
                    controlJson(
                        immediate = AutoControl.ACTION_NONE,
                        immediateReason = reason,
                        automatic = AutoControl.ACTION_PAUSE
                    )
                )
            )
            assertNull("$why: no command", parsed.control?.immediateAction)
            assertEquals(AutoControl.ACTION_PAUSE, parsed.control?.automaticAction)
        }
        assertNull(
            "a reason beside an action that needs none",
            Dashboard.parse(
                withControl(controlJson(immediate = AutoControl.ACTION_STOP, immediateReason = "action_pending"))
            ).control?.immediateAction
        )
        assertNull(
            "`none` without a reason",
            Dashboard.parse(
                withControl(controlJson(immediate = AutoControl.ACTION_NONE))
            ).control?.immediateAction
        )

        // Both halves unreadable is no decision at all -- and the rest of the dashboard is
        // untouched.
        val both = Dashboard.parse(
            withControl(controlJson(immediate = 7, automatic = "hibernate"))
        )
        assertNull(both.control)
        assertTheRestOfTheDashboardSurvives(both)
    }

    // ---- 4.

    @Test
    fun theVocabularyIsExactlyTheIntegrationsOwn() {
        // Stated on this side too, so a rename on either side fails here rather than silently
        // dropping a decision the backend would have made.
        assertEquals(setOf("next_period", "until_tomorrow", "until_resumed"), AutoControl.PAUSE_CHOICES)
        assertEquals(setOf("start", "stop", "none"), AutoControl.IMMEDIATE_ACTIONS)
        assertEquals(setOf("pause", "resume", "none"), AutoControl.AUTOMATIC_ACTIONS)

        // Each choice decodes beside the automatic pause.
        for (choice in AutoControl.PAUSE_CHOICES) {
            assertEquals(
                "a pause offering $choice",
                AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_PAUSE, listOf(choice)),
                controlFrom(controlJson(choices = org.json.JSONArray(listOf(choice))))
            )
        }

        // And nothing near either vocabulary is in it.
        val notImmediate = listOf("Start", " start", "start ", "stopped", "pause", "resume", "", "none ")
        for (action in notImmediate) {
            assertNull(
                "`$action` is not an immediate action",
                controlFrom(controlJson(immediate = action))?.immediateAction
            )
        }
        val notAutomatic = listOf("Pause", " pause", "resume ", "resumed", "stop", "start", "pause ", "")
        for (action in notAutomatic) {
            assertNull(
                "`$action` is not an automatic action",
                controlFrom(controlJson(automatic = action))?.automaticAction
            )
        }
        val notChoices =
            listOf("PAUSE", "Until_Resumed", "until_resumed ", "until-resumed", "untilresumed", "nextPeriod", "", "null")
        for (choice in notChoices) {
            assertNull(
                "`$choice` is not a choice",
                controlFrom(controlJson(choices = org.json.JSONArray(listOf(choice))))?.automaticAction
            )
        }
    }

    // ---- 5.

    @Test
    fun aDecisionBesideAnUnreadableNeighbourIsStillRead() {
        // The counterpart of 3, and the reason 3's strictness stops at one half.
        val json = dashboardJson().put("chargers", 7).put("strategy_options", "no").put("phase_detection", true)
            .put("charge_progress", "yes")
        val parsed = Dashboard.parse(json)
        assertEquals(
            AutoControl(
                AutoControl.ACTION_STOP,
                AutoControl.ACTION_PAUSE,
                listOf(AutoControl.PAUSE_NEXT_PERIOD, AutoControl.PAUSE_UNTIL_TOMORROW, AutoControl.PAUSE_UNTIL_RESUMED)
            ),
            parsed.control
        )
        assertTrue(parsed.chargers.isEmpty())
        assertEquals(setOf(HaSettingsStrategy.CHEAPEST), parsed.strategyOptions)
        assertNull(parsed.phaseDetectionSource)
        assertNull(parsed.chargeProgress)
    }

    // ---- 6.

    @Test
    fun aDashboardWithoutControlOffersNoControl() {
        // `null` is "nothing offered", which is neither an error nor a default.
        val json = dashboardJson().apply { remove("control") }
        assertFalse("the fixture has to actually lack the key", json.has("control"))
        val parsed = Dashboard.parse(json)
        assertNull(parsed.control)
        assertTheRestOfTheDashboardSurvives(parsed)
    }

    // ---- 7.

    @Test
    fun aRepresentativeDashboardRoundTripsToTheAndroidValueWithBothAxes() {
        val parsed = Dashboard.parse(dashboardJson())

        // The two things the app does with it.
        assertEquals(ChargerAction.STOP, parsed.control!!.chargerCommand())
        assertEquals(PlannerControl.PAUSE, parsed.control!!.plannerControl())
        assertTrue(parsed.control!!.untilResumedOffered)
        assertEquals(
            "the one request that control is",
            AutoControl.PAUSE_UNTIL_RESUMED,
            PlannerCommands.pause().choice
        )
        assertTheRestOfTheDashboardSurvives(parsed)
    }

    /** The fixture with its `control` set to [raw] -- one dashboard differing in one value. */
    private fun withControl(raw: Any): JSONObject = dashboardJson().put("control", raw)

    /** Everything about the fixture that is not the control, which nothing here may move. */
    private fun assertTheRestOfTheDashboardSurvives(parsed: Dashboard) {
        assertTrue("a malformed decision is not a malformed dashboard", parsed.chargingEnabled)
        assertTrue(parsed.live.scheduleActive)
        assertEquals(1, parsed.chargers.size)
        assertEquals(parsed.chargerId, parsed.chargers.single().id)
        assertEquals(true, parsed.capabilities.refreshVehicle)
        assertEquals("unknown", parsed.phaseDetectionSource)
        assertTrue(parsed.vehicles.isEmpty())
        assertEquals(4, parsed.settings!!.revision)
        assertEquals("SE4", parsed.settings!!.areaId)
    }
}
