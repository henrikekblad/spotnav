package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.chargers.ChargerAction
import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.dashboard.AutoControl
import se.sensnology.spotnav.ha.dashboard.PlannerControl
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy

internal enum class ChargingStrategy {
    /** Charge in the cheapest periods before the deadline. Swedish: **Billigast**. */
    CHEAPEST,

    /** Consume measured solar surplus. Swedish: **Sol**. */
    SOLAR,

    /** Solar first, cheap grid energy for the rest. Swedish: **Hybrid**. */
    HYBRID
}

internal enum class StrategyGap {
    /** Nothing measures what the solar installation produces. */
    MEASURED_SOLAR_PRODUCTION,

    /** Neither measurement nor control of the installation exists. */
    SOLAR_MEASUREMENT_AND_CONTROL
}

/** Who calculates the plan, installs it and keeps it up to date. */
internal enum class PlanningOwner {
    /** The phone's job is to edit the record. */
    HOME_ASSISTANT_AUTOMATIC,



    /** Nothing is paired: this phone plans from its own stored values, as it always has. */
    THIS_PHONE_LOCAL,

    /**
     * A concrete Home Assistant charger is paired and **nothing has said yet** which mode its
     * record states.
     */
    UNKNOWN
}

internal enum class ChargerBinding {
    /** Nothing is paired: the widget's own values are the authority, exactly as before pairing. */
    UNPAIRED,

    /** A configured charger is bound and no authority answer has arrived yet. */
    PAIRED_AWAITING_STATUS,

    /** A configured charger is bound and an authority answer is in hand. */
    PAIRED_WITH_AUTHORITY;

    companion object {
        /** The binding from the two facts only the screen has. */
        fun of(chargerConfigured: Boolean, authorityAnswered: Boolean): ChargerBinding = when {
            !chargerConfigured -> UNPAIRED
            authorityAnswered -> PAIRED_WITH_AUTHORITY
            else -> PAIRED_AWAITING_STATUS
        }
    }
}

/** How settled the values behind the strategy row are, and therefore how freely it may be changed. */
internal enum class StrategyStatus {
    /** A record (or this phone's own values) is confirmed and in force. */
    CONFIRMED,

    /**
     * A configured charger is bound and nothing has been read from it yet: no ownership is claimed
     * and no paired-only surface is offered until an answer arrives (see [ChargerBinding]).
     */
    PENDING,

    /**
     * The last confirmed record is *remembered* while the current revision cannot be checked. It is
     * shown, clearly stale, and nothing about it may be changed from here.
     */
    REMEMBERED_READ_ONLY,

    /** No answer yet, or a decision the person has to make first: nothing is in force to change. */
    UNAVAILABLE
}

/** One row of the strategy chooser. */
internal data class StrategyChoice(
    val strategy: ChargingStrategy,
    /** Why this charger is not offering [strategy] right now, or `null` when it is offered. */
    val gap: StrategyGap?,
    val chosen: Boolean,
    val selectable: Boolean
)

/** Everything the screen's strategy row and chooser need, and nothing they may rediscover. */
internal data class ChargingStrategyUi(
    /** The chooser's rows, in the order they are read. */
    val choices: List<StrategyChoice>,
    /** The strategy in force: */
    val strategy: ChargingStrategy,
    val owner: PlanningOwner,
    val status: StrategyStatus,
    /**
     * True for every state that is about a readable record, including the stale and the not-yet-
     * decided ones: who owns the values is a different question from what may be written.
     */
    val homeAssistantOwns: Boolean,
    /** the charger card's own row. */
    val phaseRowVisible: Boolean,
    /**
     * The second half of the same rule, named separately because the two surfaces have to be able
     * to answer for themselves:
     */
    val phaseRadioEnabled: Boolean,

    val automaticControl: PlannerControl?,
    /**
     * The one immediate action the charger's own state calls for ([ChargerActions.primaryAction]),
     * or `null` when there is none.
     */
    val immediateAction: ChargerAction?,
    /**
     * Shown as a sentence, and it is the answer to every attempt to change the strategy: zero
     * writes, and no fallback to this phone's own storage.
     */
    val readOnly: Boolean
) {
    /** Whether Home Assistant already owns automatic planning, so choosing it is a no-op. */
    val automaticOwner: Boolean get() = owner == PlanningOwner.HOME_ASSISTANT_AUTOMATIC

    /**
     * Whether the strategy row (and the chooser behind it) exists at all: strategies are a Home
     * Assistant planner's, so a phone that plans for itself shows none.
     */
    val strategyRowVisible: Boolean get() = owner != PlanningOwner.THIS_PHONE_LOCAL
}

/** What one tap on a chooser row means. */
internal sealed interface StrategyIntent {
    /** The row is already what is in force: nothing is written, nothing is said. */
    data object NothingToDo : StrategyIntent

    /** The row is not implemented at all: nothing is written, and the row says why. */
    data object Unavailable : StrategyIntent

    /**
     * The row would be a change, and Home Assistant's record cannot be written from here: **zero**
     * writes and no fallback to the widget's own stored values.
     */
    data object NotWritable : StrategyIntent

    /**
     * One admitted change: [strategy] is offered (the confirmed record's own `strategy_options`
     * names it), is not already in force, and the record is writable.
     */
    data class Save(val strategy: HaSettingsStrategy) : StrategyIntent
}

/** Which screen, and which profile, one open chooser belongs to. */
internal data class ChooserSubject(val profileId: String?, val screenGeneration: Int)

/**
 * The one piece of transient chooser state a screen owns: whether *its* chooser is open, and for
 * which subject.
 */
internal class StrategyChooserState {
    private var open: ChooserSubject? = null

    fun opened(subject: ChooserSubject) {
        open = subject
    }

    fun closed() {
        open = null
    }

    /** Whether [subject] is the screen this chooser was opened for. */
    fun isCurrent(subject: ChooserSubject): Boolean = open == subject
}


internal object ChargingStrategyPresentation {
    /** The presentation for one binding and whatever answer is in hand. */
    fun of(
        binding: ChargerBinding,
        authority: VisibleAuthority?,
        immediateAction: ChargerAction?,
        control: AutoControl?,
        strategyOptions: Set<HaSettingsStrategy> = setOf(HaSettingsStrategy.CHEAPEST)
    ): ChargingStrategyUi {
        // A configured charger is bound and nothing has said what its record states. One rule for
        // the whole first frame:
        val pending = binding != ChargerBinding.UNPAIRED && authority == null
        val owner = if (pending) PlanningOwner.UNKNOWN else owner(authority)
        val owns = authority?.haOwnsPlanning == true
        val writable = authority?.writable == true
        // Either Home Assistant's record is the authority, or nobody has said yet: both are cases
        // where phases are not this phone's to choose.
        val phasesAreHomeAssistants = owns || pending
        // A pending pairing writes nothing: there is no revision to check and no mode to build a
        // replacement from, and the widget's own values are not a fallback for a charger's record.
        val readOnly = pending || (owns && !writable)
        // The strategy in force: the confirmed record's own field, or this app's own default --
        // cheapest -- for every state that has no canonical record to read one from at all.
        val active = authority?.remoteSettings?.strategy?.let(::uiStrategy) ?: ChargingStrategy.CHEAPEST
        return ChargingStrategyUi(
            choices = ChargingStrategy.entries.map { strategy ->
                val chosen = strategy == active
                // Selectability comes from strategy_options and nowhere else:
                val offered = wireStrategy(strategy) in strategyOptions
                StrategyChoice(
                    strategy = strategy,
                    gap = if (offered) null else gapFor(strategy),
                    chosen = chosen,
                    selectable = offered && !chosen && !readOnly
                )
            },
            strategy = active,
            owner = owner,
            status = if (pending) StrategyStatus.PENDING else status(authority),
            homeAssistantOwns = owns,
            phaseRowVisible = !phasesAreHomeAssistants,
            phaseRadioEnabled = !phasesAreHomeAssistants,
            // The automatic control: Home Assistant's own decision, shown only on a paired screen
            // that may send something.
            automaticControl = if (pending || readOnly) null else control?.plannerControl(),
            immediateAction = immediateAction,
            readOnly = readOnly
        )
    }

    /**
     * The capability [strategy] is missing while this charger is not offering it. [ChargingStrategy
     * .CHEAPEST] never has one: it is always offered, per the webhook's own rule.
     */
    private fun gapFor(strategy: ChargingStrategy): StrategyGap? = when (strategy) {
        ChargingStrategy.CHEAPEST -> null
        ChargingStrategy.SOLAR -> StrategyGap.MEASURED_SOLAR_PRODUCTION
        ChargingStrategy.HYBRID -> StrategyGap.SOLAR_MEASUREMENT_AND_CONTROL
    }

    /** The UI row's own wire spelling, for reading `strategy_options` and for building an edit. */
    private fun wireStrategy(strategy: ChargingStrategy): HaSettingsStrategy = when (strategy) {
        ChargingStrategy.CHEAPEST -> HaSettingsStrategy.CHEAPEST
        ChargingStrategy.SOLAR -> HaSettingsStrategy.SOLAR
        ChargingStrategy.HYBRID -> HaSettingsStrategy.HYBRID
    }

    /** The confirmed record's own strategy, as the row it is shown by. */
    private fun uiStrategy(strategy: HaSettingsStrategy): ChargingStrategy = when (strategy) {
        HaSettingsStrategy.CHEAPEST -> ChargingStrategy.CHEAPEST
        HaSettingsStrategy.SOLAR -> ChargingStrategy.SOLAR
        HaSettingsStrategy.HYBRID -> ChargingStrategy.HYBRID
    }

    fun owner(authority: VisibleAuthority?): PlanningOwner =
        when {
            authority?.remoteSettings != null -> PlanningOwner.HOME_ASSISTANT_AUTOMATIC
            // No readable record to own anything:
            authority?.haOwnsPlanning == true -> PlanningOwner.UNKNOWN
            else -> PlanningOwner.THIS_PHONE_LOCAL
        }

    /** How settled the values behind the row are. */
    fun status(authority: VisibleAuthority?): StrategyStatus = when (authority) {
        null -> StrategyStatus.UNAVAILABLE
        is VisibleAuthority.ReadOnlyOffline -> StrategyStatus.REMEMBERED_READ_ONLY
        is VisibleAuthority.LocalOwner, is VisibleAuthority.AutoRemote,
        is VisibleAuthority.Incomplete -> StrategyStatus.CONFIRMED
    }

    /**
     * What choosing [chosen] means. The one place a chooser tap becomes an intent, so no listener
     * has an opinion about whether a row is a write, a refusal or a no-op.
     */
    fun intent(ui: ChargingStrategyUi, chosen: ChargingStrategy): StrategyIntent {
        val choice = ui.choices.firstOrNull { it.strategy == chosen } ?: return StrategyIntent.Unavailable
        return when {
            // Not offered by this charger's own strategy_options: never a choice, whatever the
            // record's writability says -- this app never guesses that solar or hybrid is possible.
            choice.gap != null -> StrategyIntent.Unavailable
            // The row already in force. Chosen and not selectable:
            choice.chosen -> if (ui.readOnly && !ui.automaticOwner) StrategyIntent.NotWritable else StrategyIntent.NothingToDo
            ui.readOnly -> StrategyIntent.NotWritable
            // An admitted change: offered, not already in force, and writable -- one settings edit,
            // through the same session every other Save uses (see `HaSettingsEdit.Strategy`).
            else -> StrategyIntent.Save(wireStrategy(chosen))
        }
    }
}

/**
 * The face the charging screen shows, built from exactly what that screen knows -- so the *first*
 * frame is a value with a test rather than a shape inside view code.
 */
internal object ChargingStrategyScreenFace {
    /**
     * The presentation for [profile] (this widget's resolved binding) and whatever authority answer
     * is in hand.
     */
    fun first(
        profile: ChargerProfile?,
        authority: VisibleAuthority?,
        immediateAction: ChargerAction?,
        control: AutoControl?,
        strategyOptions: Set<HaSettingsStrategy> = setOf(HaSettingsStrategy.CHEAPEST)
    ): ChargingStrategyUi = ChargingStrategyPresentation.of(
        binding = ChargerBinding.of(
            chargerConfigured = profile?.configured == true,
            authorityAnswered = authority != null
        ),
        authority = authority,
        immediateAction = immediateAction,
        control = control,
        strategyOptions = strategyOptions
    )
}
