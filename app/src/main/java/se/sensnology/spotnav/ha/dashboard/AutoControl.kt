package se.sensnology.spotnav.ha.dashboard

import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.ha.client.HomeAssistantCommand

/**
 * Home Assistant's control decision for one charger, as the dashboard states it: **two independent
 * axes**, each with its own action.
 */
data class AutoControl(
    /** The immediate axis: [ACTION_START], [ACTION_STOP], [ACTION_NONE], or `null` when unreadable. */
    val immediateAction: String?,
    /**
     * The automatic axis: [ACTION_PAUSE], [ACTION_RESUME], [ACTION_NONE], or `null` when
     * unreadable.
     */
    val automaticAction: String?,
    /** The choices the automatic pause would accept, in the backend's own order. Empty when none. */
    val pauseChoices: List<String>,
    /**
     * Why the immediate axis offers nothing ([IMMEDIATE_REASONS]), carried with a readable `none`;
     * `null` beside an action or when the axis could not be read.
     */
    val immediateReason: String? = null,
    /** Why the automatic axis offers nothing ([AUTOMATIC_REASONS]), read the same way. */
    val automaticReason: String? = null
) {
    /**
     * Whether either axis says a Start or Stop is under way ([REASON_ACTION_PENDING]): Home
     * Assistant awaits the charger's report, so neither axis offers anything until it arrives.
     */
    val actionPending: Boolean
        get() = immediateReason == REASON_ACTION_PENDING || automaticReason == REASON_ACTION_PENDING

    /** The one **automatic** control this decision offers this app, or `null` when it offers none. */
    internal fun plannerControl(): PlannerControl? = when (automaticAction) {
        ACTION_RESUME -> PlannerControl.RESUME
        ACTION_PAUSE -> if (pauseChoices.isNotEmpty()) PlannerControl.PAUSE else null
        else -> null
    }

    /** The one **immediate** command this decision offers this app, or `null` when it offers none. */
    internal fun chargerCommand(): ChargerAction? = when (immediateAction) {
        ACTION_START -> ChargerAction.START
        ACTION_STOP -> ChargerAction.STOP
        else -> null
    }

    /** Whether this decision would accept an indefinite pause. */
    val untilResumedOffered: Boolean get() = PAUSE_UNTIL_RESUMED in pauseChoices

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        /** The automatic axis's own action name, spelled exactly as the backend spells it. */
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_NONE = "none"

        /**
         * The three pause choices a dashboard may name, spelled as the integration spells them
         * (`PauseChoice` in `auto_settings.py`).
         */
        const val PAUSE_NEXT_PERIOD = "next_period"
        const val PAUSE_UNTIL_TOMORROW = "until_tomorrow"
        const val PAUSE_UNTIL_RESUMED = "until_resumed"

        /**
         * The pause a person's Start or Stop makes (Home Assistant 1.11): a pause this app shows and
         * resumes, never one it asks for, so it is never a choice here.
         */
        const val PAUSE_MANUAL = "manual"

        /** The two axes' actions, as *separate* closed sets. */
        val IMMEDIATE_ACTIONS = setOf(ACTION_START, ACTION_STOP, ACTION_NONE)
        val AUTOMATIC_ACTIONS = setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_NONE)

        /** A Start or Stop awaiting the charger's report, on either axis. */
        const val REASON_ACTION_PENDING = "action_pending"

        /** The reasons each axis may carry in the state where it has no action. */
        val IMMEDIATE_REASONS = setOf("no_settings", REASON_ACTION_PENDING)
        val AUTOMATIC_REASONS =
            setOf("no_settings", "pause_unsettled", "pause_clear_failed", REASON_ACTION_PENDING)

        /** The whole pause-choice vocabulary. A choice this build cannot name is not a choice. */
        val PAUSE_CHOICES = setOf(PAUSE_NEXT_PERIOD, PAUSE_UNTIL_TOMORROW, PAUSE_UNTIL_RESUMED)

        /** The value the transport hands over for a member the response did not write as text. */
        internal const val UNREADABLE = "\u0000unreadable"

        /**
         * One decision, from the members the status carried as plain values, or `null` when
         * *neither* axis is readable.
         */
        fun of(
            immediateAction: String,
            immediateReason: String?,
            automaticAction: String,
            automaticReason: String?,
            pauseChoices: List<String>
        ): AutoControl? {
            val immediate = immediateAxis(immediateAction, immediateReason)
            // `manual` is Home Assistant's own word for a person's Start or Stop, never something a
            // person picks from the pause sheet: it is left out rather than offered.
            val offered = pauseChoices.filterNot { it == PAUSE_MANUAL }
            val automatic = automaticAxis(automaticAction, automaticReason, offered)
            if (immediate == null && automatic == null) return null
            // The choices travel only with a readable automatic axis: an axis this build could not
            // read offers no pause, so it publishes nothing to choose for one either.
            return AutoControl(
                immediate, automatic, if (automatic == null) emptyList() else offered,
                // A reason travels only with the readable `none` it explains.
                immediateReason = if (immediate == ACTION_NONE) immediateReason else null,
                automaticReason = if (automatic == ACTION_NONE) automaticReason else null
            )
        }

        /** One axis's action, or `null` when the half that describes it is not a decision. */
        private fun immediateAxis(action: String, reason: String?): String? {
            if (action !in IMMEDIATE_ACTIONS) return null
            if (reason != null && reason !in IMMEDIATE_REASONS) return null
            // An actionable axis has nothing to explain, and `none` always explains itself.
            if ((action != ACTION_NONE) == (reason != null)) return null
            return action
        }

        /** The automatic axis's action, choices included, or `null` when its half is not a decision. */
        private fun automaticAxis(action: String, reason: String?, choices: List<String>): String? {
            if (action !in AUTOMATIC_ACTIONS) return null
            if (reason != null && reason !in AUTOMATIC_REASONS) return null
            if ((action != ACTION_NONE) == (reason != null)) return null
            // Every listed choice must be one this build can name. Dropping an odd member would
            // turn a malformed answer into a pause this app then asks for.
            if (choices.any { it !in PAUSE_CHOICES }) return null
            // Choices appear beside the automatic pause and nowhere else: a payload that carries
            // them beside another action has described two different requests.
            if (action != ACTION_PAUSE && choices.isNotEmpty()) return null
            return action
        }
    }
}

/**
 * The one automatic-control action this app offers beside the strategy row: **pause** automatic
 * charging, or **resume** it.
 */
internal enum class PlannerControl { PAUSE, RESUME }

/**
 * The one command each automatic control is, in one place so "Pause is exactly one typed pause
 * request" is a fact with a test rather than a shape inside a click listener.
 */
internal object PlannerCommands {
    fun pause(choice: String = AutoControl.PAUSE_UNTIL_RESUMED): HomeAssistantCommand =
        HomeAssistantCommand(action = "stop", choice = choice)

    fun resume(): HomeAssistantCommand = HomeAssistantCommand(action = AutoControl.ACTION_RESUME)
}
