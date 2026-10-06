package se.sensnology.spotnav.ha.dashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.client.HomeAssistantClient
import se.sensnology.spotnav.ha.client.HomeAssistantCommand

/** Home Assistant's control decision, as this app reads it and acts on it. */
class AutoControlTest {
    /** The decision a charging, unpaused automatic charger states: */
    private val chargingAuto = AutoControl(
        AutoControl.ACTION_STOP,
        AutoControl.ACTION_PAUSE,
        listOf(AutoControl.PAUSE_NEXT_PERIOD, AutoControl.PAUSE_UNTIL_RESUMED)
    )

    private val idleAuto = AutoControl(
        AutoControl.ACTION_START,
        AutoControl.ACTION_PAUSE,
        listOf(AutoControl.PAUSE_UNTIL_RESUMED)
    )

    // ---- 9 and 10.

    @Test
    fun pauseIsExactlyOneTypedPauseRequestAndResumeIsExactlyOneResume() {
        val pause = PlannerCommands.pause()
        assertEquals("the action is a stop", "stop", pause.action)
        assertEquals(
            "carrying the one choice a single button can mean",
            AutoControl.PAUSE_UNTIL_RESUMED, pause.choice
        )
        // Nothing else is in the command: no schedule, no amps, no revision.
        assertNull(pause.amps); assertNull(pause.phases)
        assertNull(pause.vehicleId)
        assertNull(pause.expectedRevision); assertNull(pause.settingsReplacement)

        // A named choice is the one that travels, and only it.
        assertEquals(AutoControl.PAUSE_NEXT_PERIOD, PlannerCommands.pause(AutoControl.PAUSE_NEXT_PERIOD).choice)
        assertEquals(AutoControl.PAUSE_UNTIL_TOMORROW, PlannerCommands.pause(AutoControl.PAUSE_UNTIL_TOMORROW).choice)

        val resume = PlannerCommands.resume()
        assertEquals("resume", resume.action)
        assertNull("a resume carries no choice: it is not a stop", resume.choice)
        assertNull(resume.amps); assertNull(resume.expectedRevision)

        // And the wire payloads are the three fields a pause needs and the two a resume needs --
        // which is what makes "one typed request" a fact about the bytes rather than about the
        // object.
        assertEquals(setOf("version", "reads", "action", "choice"), keys(HomeAssistantClient.payload(pause)))
        assertEquals(setOf("version", "reads", "action"), keys(HomeAssistantClient.payload(resume)))
        assertEquals(
            AutoControl.PAUSE_UNTIL_RESUMED,
            JSONObject(HomeAssistantClient.payload(pause)).getString("choice")
        )
    }

    @Test
    fun aChoiceNeverTravelsBesideAnActionThatHasNoChoiceToMake() {
        // The transport's own rule, asserted where it is enforced.
        val smuggled = HomeAssistantCommand(action = "resume", choice = AutoControl.PAUSE_UNTIL_RESUMED)
        assertEquals(setOf("version", "reads", "action"), keys(HomeAssistantClient.payload(smuggled)))
        val started = HomeAssistantCommand(action = "start", choice = AutoControl.PAUSE_UNTIL_RESUMED)
        assertEquals(setOf("version", "reads", "action"), keys(HomeAssistantClient.payload(started)))
    }

    // ---- 1 and 2.

    @Test
    fun theAutomaticAxisOffersPauseOrResumeAndNothingElse() {
        assertEquals(PlannerControl.PAUSE, chargingAuto.plannerControl())
        assertEquals(
            "an idle Auto charger is pausable: physical charging is not a precondition",
            PlannerControl.PAUSE,
            idleAuto.plannerControl()
        )
        assertEquals(
            PlannerControl.RESUME,
            AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_RESUME, emptyList()).plannerControl()
        )
        // `none`, an axis this build could not read, and both of the *immediate* axis's own actions
        // are not automatic controls, so the card offers none of them as one.
        for (action in listOf(AutoControl.ACTION_NONE, null)) {
            assertNull(
                "$action offers no automatic control",
                AutoControl(AutoControl.ACTION_START, action, emptyList()).plannerControl()
            )
        }
        assertNull(AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_NONE, emptyList()).plannerControl())
        assertNull(AutoControl(AutoControl.ACTION_STOP, AutoControl.ACTION_NONE, emptyList()).plannerControl())
        // A pause is offered whenever the charger names a choice to pause with, timed ones
        // included.
        assertEquals(
            PlannerControl.PAUSE,
            AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_PAUSE, listOf("next_period")).plannerControl()
        )
        assertNull(AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_PAUSE, emptyList()).plannerControl())
        assertTrue(chargingAuto.untilResumedOffered)
        assertFalse(AutoControl(AutoControl.ACTION_START, AutoControl.ACTION_PAUSE, emptyList()).untilResumedOffered)
    }

    @Test
    fun theImmediateAxisOffersStartNowOrStopAndKnowsNoPauseAtAll() {
        assertEquals(ChargerAction.START, idleAuto.chargerCommand())
        assertEquals(ChargerAction.STOP, chargingAuto.chargerCommand())
        // `none` (a Start awaiting acknowledgement, or a charger with no record) and an axis this
        // build could not read both offer no command -- and neither a pause nor a resume is ever
        // read as one.
        for (action in listOf(AutoControl.ACTION_NONE, null)) {
            assertNull(
                "$action offers no charger command",
                AutoControl(action, AutoControl.ACTION_PAUSE, emptyList()).chargerCommand()
            )
        }
        // The automatic axis is never read as a command, whatever it says.
        for (automatic in listOf(AutoControl.ACTION_PAUSE, AutoControl.ACTION_RESUME)) {
            val decision = AutoControl(AutoControl.ACTION_NONE, automatic, emptyList())
            assertNull("$automatic is not a command to the charger", decision.chargerCommand())
            assertEquals("and it is still the automatic axis's own answer", automatic, decision.automaticAction)
        }
        // And the two axes are read separately, from the one decision.
        assertEquals(ChargerAction.START, idleAuto.chargerCommand())
        assertEquals(PlannerControl.PAUSE, idleAuto.plannerControl())
    }

    // ---- 3.

    @Test
    fun eachAxisIsReadStrictlyAndOneUnreadableHalfDoesNotEraseTheOther() {
        // The whole decision, as the transport hands it over.
        assertEquals(
            chargingAuto,
            AutoControl.of(
                AutoControl.ACTION_STOP,
                null,
                AutoControl.ACTION_PAUSE,
                null,
                listOf(AutoControl.PAUSE_NEXT_PERIOD, AutoControl.PAUSE_UNTIL_RESUMED)
            )
        )
        assertEquals(
            idleAuto,
            AutoControl.of(AutoControl.ACTION_START, null, AutoControl.ACTION_PAUSE, null, listOf(AutoControl.PAUSE_UNTIL_RESUMED))
        )

        // An action this build cannot name, and a member that was not text at all, make *that* axis
        // unreadable -- and the other axis is still read, which is the whole reason the split is
        // read in halves rather than as one value.
        for (broken in listOf("resume_later", AutoControl.UNREADABLE)) {
            val noImmediate = AutoControl.of(
                broken, null, AutoControl.ACTION_PAUSE, null, listOf(AutoControl.PAUSE_UNTIL_RESUMED)
            )
            assertNull("$broken: no command is offered", noImmediate?.immediateAction)
            assertEquals(
                "$broken: and the automatic axis is still read",
                AutoControl.ACTION_PAUSE,
                noImmediate?.automaticAction
            )
            assertEquals(PlannerControl.PAUSE, noImmediate?.plannerControl())

            val noAutomatic = AutoControl.of(AutoControl.ACTION_STOP, null, broken, null, emptyList())
            assertEquals(AutoControl.ACTION_STOP, noAutomatic?.immediateAction)
            assertNull("$broken: no automatic control is offered", noAutomatic?.automaticAction)
            assertNull(noAutomatic?.plannerControl())
        }

        // Both halves unreadable is no decision at all.
        assertNull(AutoControl.of(AutoControl.UNREADABLE, null, AutoControl.UNREADABLE, null, emptyList()))

        // A reason from the wrong axis, a reason beside an action, and `none` without one.
        assertNull(
            "an immediate axis cannot explain itself with a planning code",
            AutoControl.of(AutoControl.ACTION_NONE, "pause_unsettled", AutoControl.ACTION_PAUSE, null, emptyList())
                ?.immediateAction
        )
        assertNull(
            "an action with a reason",
            AutoControl.of(AutoControl.ACTION_START, "action_pending", AutoControl.ACTION_NONE, "action_pending", emptyList())
                ?.immediateAction
        )
        assertNull(
            "`none` with no reason at all",
            AutoControl.of(AutoControl.ACTION_NONE, null, AutoControl.ACTION_PAUSE, null, emptyList())
                ?.immediateAction
        )
        assertNull(
            "an unknown reason is not a reason",
            AutoControl.of(AutoControl.ACTION_STOP, null, AutoControl.ACTION_NONE, "because", emptyList())
                ?.automaticAction
        )
        // Every reason of the *immediate* set is legal on the automatic axis too (the two axes
        // share `action_pending`), so this direction is the one that has to be refused.

        // Choices: an unknown one, and any of them beside an action that has none to make.
        assertNull(
            "an unknown choice is not a choice",
            AutoControl.of(AutoControl.ACTION_STOP, null, AutoControl.ACTION_PAUSE, null, listOf("until_forever"))
                ?.automaticAction
        )
        assertNull(
            "choices beside a resume",
            AutoControl.of(
                AutoControl.ACTION_STOP, null, AutoControl.ACTION_RESUME, null, listOf(AutoControl.PAUSE_UNTIL_RESUMED)
            )?.automaticAction
        )
        assertNull(
            "choices beside `none`",
            AutoControl.of(
                AutoControl.ACTION_STOP, null, AutoControl.ACTION_NONE, "pause_unsettled",
                listOf(AutoControl.PAUSE_UNTIL_RESUMED)
            )?.automaticAction
        )
        // And the choices travel only with a readable automatic axis.
        assertTrue(
            "an axis this build could not read publishes nothing to choose for",
            AutoControl.of(
                AutoControl.ACTION_STOP, null, AutoControl.UNREADABLE, null, listOf(AutoControl.PAUSE_UNTIL_RESUMED)
            )!!.pauseChoices.isEmpty()
        )
    }

    @Test
    fun theReasonTravelsWithTheAxisThatHasNoAction() {
        // Either axis saying `action_pending` is a Start or Stop under way:
        val both = AutoControl.of(
            AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING,
            AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING, emptyList()
        )!!
        assertEquals(AutoControl.REASON_ACTION_PENDING, both.immediateReason)
        assertEquals(AutoControl.REASON_ACTION_PENDING, both.automaticReason)
        assertTrue(both.actionPending)
        val automaticOnly = AutoControl.of(
            AutoControl.ACTION_STOP, null, AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING, emptyList()
        )!!
        assertTrue(automaticOnly.actionPending)
        val immediateOnly = AutoControl.of(
            AutoControl.ACTION_NONE, AutoControl.REASON_ACTION_PENDING, AutoControl.ACTION_PAUSE, null,
            listOf(AutoControl.PAUSE_UNTIL_RESUMED)
        )!!
        assertTrue(immediateOnly.actionPending)
        // Another reason is carried, but it is not a pending action:
        val noSettings = AutoControl.of(AutoControl.ACTION_NONE, "no_settings", AutoControl.ACTION_NONE, "no_settings", emptyList())!!
        assertEquals("no_settings", noSettings.immediateReason)
        assertFalse(noSettings.actionPending)
        // An axis this build could not read carries no reason either:
        val unknown = AutoControl.of(AutoControl.ACTION_NONE, "because", AutoControl.ACTION_PAUSE, null, listOf(AutoControl.PAUSE_UNTIL_RESUMED))!!
        assertNull(unknown.immediateAction)
        assertNull(unknown.immediateReason)
        assertFalse(unknown.actionPending)
        // An actionable decision has nothing pending.
        assertFalse(chargingAuto.actionPending)
    }

    private fun keys(payload: String): Set<String> = JSONObject(payload).keys().asSequence().toSet()
}
