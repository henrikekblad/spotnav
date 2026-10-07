package se.sensnology.spotnav.ha.settings

import se.sensnology.spotnav.ha.authority.AuthorityController
import se.sensnology.spotnav.ha.authority.CommitRoute
import se.sensnology.spotnav.ha.authority.WriteOutcome
import se.sensnology.spotnav.ha.authority.WriteSubject

/**
 * One value of Settings written through the settings record ([HaSettingsEdit]: the cars at a charger,
 * how the plugged-in car is found, who is notified). One Save writes it: when the charger answers that
 * its revision moved (Home Assistant moves it on its own, so the record shown is often one behind), the
 * same one edit is built again on the record it answered with and sent once more.
 */
internal object SettingsEditSend {
    sealed interface Result {
        /** Sent: the last answer, and what the screen now shows. */
        data class Sent(val answer: SettingsUpdate.Outcome, val outcome: WriteOutcome) : Result

        /** The edit would make a record the contract refuses; nothing was sent. */
        data class Refused(val code: String) : Result

        /** Nothing may be written now. */
        data object ReadOnly : Result
    }

    /**
     * Send [edit] through [transport] (the expected revision, the replacement, and the answer when it
     * comes); [done] hears how it ended.
     */
    fun send(
        controller: AuthorityController,
        profileId: String,
        edit: HaSettingsEdit,
        transport: (Int, HaPlanningSettings, (SettingsUpdate.Outcome) -> Unit) -> Unit,
        done: (Result) -> Unit
    ) = attempt(controller, profileId, edit, transport, done, again = true)

    private fun attempt(
        controller: AuthorityController,
        profileId: String,
        edit: HaSettingsEdit,
        transport: (Int, HaPlanningSettings, (SettingsUpdate.Outcome) -> Unit) -> Unit,
        done: (Result) -> Unit,
        again: Boolean
    ) {
        when (val route = controller.beginWrite(edit)) {
            is CommitRoute.Send -> transport(route.expectedRevision, route.replacement) { answer ->
                val outcome = controller.onWriteAnswer(WriteSubject(profileId, route.operation, route.expectedRevision), answer)
                // The record the conflict answered with is now the one shown: the same edit, on it, once.
                if (again && answer is SettingsUpdate.Outcome.Conflict && outcome is WriteOutcome.Applied) {
                    attempt(controller, profileId, edit, transport, done, again = false)
                } else {
                    done(Result.Sent(answer, outcome))
                }
            }
            is CommitRoute.Refused -> done(Result.Refused(route.code))
            CommitRoute.ReadOnly, CommitRoute.LocalSave -> done(Result.ReadOnly)
        }
    }
}
