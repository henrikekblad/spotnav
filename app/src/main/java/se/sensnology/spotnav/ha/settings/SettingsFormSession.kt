package se.sensnology.spotnav.ha.settings

import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.ha.authority.WriteOutcome
import se.sensnology.spotnav.ha.authority.WriteSubject

/**
 * The owner one admitted Save must be sent through, resolved **from the Save's own profile id**
 * immediately before the request.
 */
internal fun interface SettingsSaveTarget {
    /** The one request this Save is: the settings transport, its captured revision and document. */
    fun send(decision: SettingsSave.Send): SettingsUpdate.Outcome
}

/** What one press of Save produced. */
internal sealed interface FormSaveOutcome {
    /** Nothing was sent. */
    data class NotSent(val decision: SettingsSave, val abandoned: Boolean = false) : FormSaveOutcome

    /** The one admitted request went out, and this is what came back. */
    data class Sent(
        val decision: SettingsSave.Send,
        val outcome: WriteOutcome,
        val answer: SettingsUpdate.Outcome
    ) : FormSaveOutcome
}

/** One step the screen has to carry out for a press of Save. */
internal sealed interface SaveEffect {
    /** Write the widget's own record. */
    data class PersistLocal(val paired: Boolean) : SaveEffect

    /** The widget's own redraw and the way this screen ends. */
    data object Publish : SaveEffect

    /** Restore and explain: nothing was written, and [decision] says why ([abandoned] included). */
    data class RenderState(val decision: SettingsSave, val abandoned: Boolean) : SaveEffect

    /** Render the record the answer left standing, and what the answer was. */
    data class RenderAnswer(
        val decision: SettingsSave.Send,
        val outcome: WriteOutcome,
        val answer: SettingsUpdate.Outcome
    ) : SaveEffect
}

/** One press of Save, from admission to answer -- and the seam the tests drive. */
internal object SettingsFormSession {
    fun save(
        controller: AuthorityController,
        values: SettingsFormValues,
        /**
         * The same one value on another record: when the charger answers that its revision moved (Home
         * Assistant moves it on its own, so the record shown is often one behind), the value is built
         * again on the record it answered with and sent once more, so one Save writes it. `null` sends
         * nothing more.
         */
        replay: ((HaPlanningSettings) -> SettingsFormValues)? = null,
        /** The owner an admitted Save must be sent through: resolved from its own profile id. */
        targetFor: (String) -> SettingsSaveTarget?
    ): FormSaveOutcome {
        val first = save(controller, controller.admitFormSave(values), targetFor)
        val conflict = (first as? FormSaveOutcome.Sent)?.answer as? SettingsUpdate.Outcome.Conflict
        if (replay == null || conflict == null || first.outcome !is WriteOutcome.Applied) return first
        return save(controller, controller.admitFormSave(replay(conflict.current)), targetFor)
    }

    fun save(
        controller: AuthorityController,
        decision: SettingsSave,
        /** The owner this admitted Save must be sent through: resolved from its own profile id. */
        targetFor: (String) -> SettingsSaveTarget?
    ): FormSaveOutcome {
        // Abandonment is one outcome with one shape, so no path can report it differently.
        fun abandoned() = FormSaveOutcome.NotSent(SettingsSave.ReadOnly, abandoned = true)

        if (decision !is SettingsSave.Send) return FormSaveOutcome.NotSent(decision)
        if (!controller.stillAdmitted(decision)) return abandoned()
        val target = targetFor(decision.profileId) ?: return abandoned()
        // **The local linearization point.** Resolving the owner is an operation of its own, and a
        // newer press can be admitted while it runs, so the admission is checked once more here --
        // in one coherent snapshot (see AuthorityController.stillAdmitted) -- and this is the last
        // decision this process makes before the request leaves it.
        if (!controller.stillAdmitted(decision)) return abandoned()
        val answer = target.send(decision)
        val outcome = controller.onWriteAnswer(
            WriteSubject(decision.profileId, decision.operation, decision.expectedRevision), answer
        )
        return FormSaveOutcome.Sent(decision, outcome, answer)
    }

    /** What the screen must carry out for [outcome], in order. */
    fun effects(outcome: FormSaveOutcome): List<SaveEffect> = when (outcome) {
        is FormSaveOutcome.NotSent -> when (outcome.decision) {
            SettingsSave.LocalOnly -> listOf(SaveEffect.PersistLocal(paired = false), SaveEffect.Publish)
            else -> listOf(
                SaveEffect.PersistLocal(paired = true),
                SaveEffect.RenderState(outcome.decision, outcome.abandoned)
            )
        }
        is FormSaveOutcome.Sent -> listOf(
            SaveEffect.PersistLocal(paired = true),
            SaveEffect.RenderAnswer(outcome.decision, outcome.outcome, outcome.answer)
        )
    }
}
