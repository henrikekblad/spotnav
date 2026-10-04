package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.settings.HaNotificationSettings
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.push.PushRegistration

/** Translated text by resource id, so the wording below can be checked without Android. */
internal interface NotificationTexts {
    fun text(id: Int, vararg args: Any): String
    fun quantity(id: Int, count: Int, vararg args: Any): String
}

/**
 * What the Notifications card says and which help the dialog shows. The two routes are named by
 * the app that shows the notification: the Home Assistant app (Companion), and the SpotNav app,
 * which checks every 15 minutes or, with instant notifications, at once.
 */
internal object NotificationsOverview {
    private const val JOIN = " · "

    /** "3 of 7 events": the noun agrees with the total. */
    private fun events(count: Int, texts: NotificationTexts) =
        NotificationEvent.entries.size.let { total -> texts.quantity(R.plurals.notify_events_count, total, count, total) }

    /** The Home Assistant app's row: the chosen phones, then the events once a phone is chosen. */
    fun homeAssistant(settings: HaNotificationSettings, texts: NotificationTexts): String {
        val chosen = NotificationPhones.of(settings).filter { it.chosen }
        val phones = when (chosen.size) {
            0 -> return texts.text(R.string.notify_phones_none)
            1 -> chosen[0].let { if (it.missing) texts.text(R.string.notify_missing, it.name) else it.name }
            else -> texts.quantity(R.plurals.notify_phones_count, chosen.size, chosen.size)
        }
        val known = settings.events.count { NotificationEvent.of(it) != null }
        return phones + JOIN + events(known, texts)
    }

    /** The SpotNav app's row: off, every 15 minutes or instant, with the events while it is on. */
    fun spotNav(on: Boolean, events: Set<NotificationEvent>, instant: Boolean, texts: NotificationTexts): String {
        if (!on) return texts.text(R.string.notify_app_off)
        val mode = texts.text(if (instant) R.string.notify_app_instant else R.string.notify_app_periodic)
        return mode + JOIN + events(events.size, texts)
    }

    /** The dialog's help under the SpotNav switches follows the instant switch. */
    fun spotNavHelp(instantChecked: Boolean): Int =
        if (instantChecked) R.string.notify_push_help else R.string.notify_local_help

    /** Why turning instant notifications on failed, in one line; `null` when it did not fail. */
    fun pushFailure(result: PushRegistration.Result?): Int? = when (result) {
        null, PushRegistration.Result.ON, PushRegistration.Result.OFF -> null
        PushRegistration.Result.NO_TOKEN -> R.string.notify_push_no_token
        PushRegistration.Result.SERVER_OFF -> R.string.notify_push_server_off
        PushRegistration.Result.RATE_LIMITED -> R.string.notify_push_rate_limited
        PushRegistration.Result.FAILED -> R.string.notify_push_failed
    }
}

/**
 * Saving the Notifications dialog: the Home Assistant app's choice through the settings webhook,
 * the SpotNav app's switch and events in this phone's preferences (then the background check and
 * every Home Assistant's instant-notification registration brought up to date), and instant
 * notifications turned on or off. Only what changed is written; [save] answers once everything has.
 * Every callback is expected on the main thread.
 */
internal class NotificationsSave(
    private val local: LocalNotificationStore,
    private val push: Push,
    private val writeHomeAssistant: (targets: List<String>, events: List<String>, done: (String?) -> Unit) -> Unit,
    private val localChanged: () -> Unit,
    private val turnedOn: () -> Unit
) {
    /** Instant notifications as this phone has them. */
    interface Push {
        val offered: Boolean
        val on: Boolean
        fun set(on: Boolean, done: (PushRegistration.Result) -> Unit)
    }

    /** The Home Assistant app's choice in the dialog, as it would be written. */
    data class HomeAssistantChoice(val targets: List<String>, val events: List<String>)

    data class Choice(
        val homeAssistant: HomeAssistantChoice?,
        val spotNavOn: Boolean,
        val spotNavEvents: Set<NotificationEvent>,
        val instant: Boolean
    )

    /** What did not go through: Home Assistant's refusal in words, and the instant-notification result. */
    data class Outcome(val homeAssistantError: String? = null, val push: PushRegistration.Result? = null) {
        val saved: Boolean get() = homeAssistantError == null && NotificationsOverview.pushFailure(push) == null
    }

    fun save(current: HaNotificationSettings?, choice: Choice, done: (Outcome) -> Unit) {
        val haWrite = choice.homeAssistant?.takeIf { wanted ->
            current != null && (wanted.targets != current.targets || wanted.events.toSet() != current.events.toSet())
        }
        val wasOn = local.enabled
        if (choice.spotNavOn != wasOn || choice.spotNavEvents != local.events) {
            local.events = choice.spotNavEvents
            local.enabled = choice.spotNavOn
            localChanged()
            if (choice.spotNavOn && !wasOn) turnedOn()
        }
        // The instant switch can only change while the SpotNav app's notifications are on.
        val pushWrite = push.offered && choice.spotNavOn && choice.instant != push.on

        var pending = listOfNotNull(haWrite, pushWrite.takeIf { it }).size
        if (pending == 0) return done(Outcome())
        var outcome = Outcome()
        fun finished() {
            pending -= 1
            if (pending == 0) done(outcome)
        }
        haWrite?.let { wanted ->
            writeHomeAssistant(wanted.targets, wanted.events) { failure ->
                outcome = outcome.copy(homeAssistantError = failure)
                finished()
            }
        }
        if (pushWrite) {
            push.set(choice.instant) { result ->
                outcome = outcome.copy(push = result)
                finished()
            }
        }
    }

    companion object {
        /** The phones and events ticked in the dialog; an event this app cannot name stays as Home Assistant has it. */
        fun homeAssistantChoice(
            current: HaNotificationSettings,
            phones: List<NotificationPhones.Phone>,
            phoneTicked: List<Boolean>,
            eventTicked: List<Boolean>
        ) = HomeAssistantChoice(
            targets = phones.filterIndexed { index, _ -> phoneTicked[index] }.map { it.service },
            events = NotificationEvent.entries.filterIndexed { index, _ -> eventTicked[index] }.map { it.wire } +
                current.events.filter { NotificationEvent.of(it) == null }
        )
    }
}
