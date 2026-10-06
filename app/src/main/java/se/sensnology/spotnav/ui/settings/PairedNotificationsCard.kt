package se.sensnology.spotnav.ui.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.settings.HaNotificationSettings
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.push.PushNotifications
import se.sensnology.spotnav.push.PushRegistration
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.checkbox
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.ui.common.valueColour
import se.sensnology.spotnav.ui.common.valueLabel

/** The phones a notification choice can name: the ones that exist, then chosen ones that no longer do. */
internal object NotificationPhones {
    data class Phone(val service: String, val name: String, val chosen: Boolean, val missing: Boolean)

    fun of(settings: HaNotificationSettings): List<Phone> =
        settings.available.map { Phone(it.service, it.name, it.service in settings.targets, missing = false) } +
            settings.targets.filter { target -> settings.available.none { it.service == target } }
                .map { Phone(it, it, chosen = true, missing = true) }
}

/**
 * A paired charger's notifications, after the paired cards: a summary of the two routes, named by
 * the app that shows the notification, and one dialog that changes both. Via the Home Assistant app
 * is the Home Assistant card's own choice of phones and events (shown only when Home Assistant
 * states it); via the SpotNav app is this phone's own check (off by default, see
 * [LocalNotifications]), at once with instant notifications where the build and phone offer them.
 */
internal class PairedNotificationsCard(scope: ViewScope, parent: LinearLayout) : ViewScope(scope) {
    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val local = LocalNotificationStore.forContext(context)
    private var record: HaNotificationSettings? = null
    private var writable = false

    private var save: (List<String>, List<String>, (String?) -> Unit) -> Unit = { _, _, done -> done(null) }
    private var requestPermission: () -> Unit = {}

    private val texts = object : NotificationTexts {
        override fun text(id: Int, vararg args: Any) = t(id, *args)
        override fun quantity(id: Int, count: Int, vararg args: Any) = tq(id, count, *args)
    }

    init {
        parent.addView(container)
    }

    /** Where the Companion choice is written, and how Android 13+ is asked to allow notifications. */
    fun attach(save: (List<String>, List<String>, (String?) -> Unit) -> Unit, requestPermission: () -> Unit) {
        this.save = save
        this.requestPermission = requestPermission
    }

    /** Paint the confirmed record's choice (`null`: Home Assistant does not state one). */
    fun show(notifications: HaNotificationSettings?, writable: Boolean) {
        record = notifications
        this.writable = writable
        repaint()
    }

    /** The permission prompt was answered: say what it means for the summary. */
    fun permissionAnswered() {
        LocalNotifications.sync(context)
        repaint()
    }

    private fun instantOffered() = PushNotifications.available(context)

    private fun instantOn() = instantOffered() && PushNotifications.enabled(context)

    private fun muted(text: String, top: Int = 0, bottom: Int = 0) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, dp(top), 0, dp(bottom))
    }

    private fun heading(text: String, top: Int = 14) = TextView(context).apply {
        this.text = text; textSize = 15f; setTextColor(dark); setPadding(0, dp(top), 0, dp(2))
    }

    private fun sectionHeading(text: String, top: Int) = TextView(context).apply {
        this.text = text; textSize = 17f; setTextColor(dark); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(top), 0, dp(2))
    }

    /** A route's summary under its name: both are long, so the value never shares a line with it. */
    private fun readRow(parent: LinearLayout, label: String, value: String) {
        parent.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(muted) })
            addView(valueLabel().apply { text = value; gravity = Gravity.START; setTextColor(palette.valueColour(ValueCue.READ_ONLY)) })
        })
    }

    private fun eventName(event: NotificationEvent) = t(
        when (event) {
            NotificationEvent.PLAN_STOPPED -> R.string.notify_event_plan_stopped
            NotificationEvent.PLAN_AT_RISK -> R.string.notify_event_plan_at_risk
            NotificationEvent.CHARGE_COMPLETE -> R.string.notify_event_charge_complete
            NotificationEvent.CHARGE_STARTED -> R.string.notify_event_charge_started
            NotificationEvent.PLUGGED_IN -> R.string.notify_event_plugged_in
            NotificationEvent.UNPLUGGED -> R.string.notify_event_unplugged
            NotificationEvent.PLAN_INSTALLED -> R.string.notify_event_plan_installed
            NotificationEvent.VEHICLE_IDENTIFY -> R.string.identify_question
        }
    )

    private fun errorView() = TextView(context).apply {
        textSize = 13f; setTextColor(ERROR_COLOUR); setPadding(0, dp(4), 0, 0); visibility = View.GONE
    }

    private fun TextView.say(message: String?) {
        text = message.orEmpty()
        visibility = if (message == null) View.GONE else View.VISIBLE
    }

    // --- The card ---------------------------------------------------------------------------------

    private fun repaint() {
        container.removeAllViews()
        val card = card(container, t(R.string.notify_section), R.drawable.ic_settings)
        val body = card.body
        // Both routes open the one dialog that changes them.
        val open = { openDialog() }
        record?.let { settings ->
            settingRow(body, t(R.string.notify_companion_title), NotificationsOverview.homeAssistant(settings, texts), onTap = open)
            if (!writable) body.addView(muted(t(R.string.settings_paired_read_only), bottom = 4))
        }
        settingRow(body, t(R.string.notify_app_title), NotificationsOverview.spotNav(local.enabled, local.events, instantOn(), texts), onTap = open)
        if (local.enabled && !LocalNotifications.allowed(context)) body.addView(muted(t(R.string.notify_local_denied), bottom = 4))
    }

    // --- The dialog -------------------------------------------------------------------------------

    /** The Home Assistant app's section: who gets it and for what; read-only when it cannot be written. */
    private class HomeAssistantSection(
        val phones: List<NotificationPhones.Phone>,
        val phoneBoxes: List<CheckBox>,
        val eventBoxes: List<CheckBox>,
        val error: TextView
    )

    private fun addHomeAssistantSection(body: LinearLayout, settings: HaNotificationSettings): HomeAssistantSection {
        body.addView(sectionHeading(t(R.string.notify_companion_title), top = 0))
        body.addView(muted(t(R.string.notify_companion_intro)))
        if (!writable) body.addView(muted(t(R.string.settings_paired_read_only), top = 4))
        body.addView(heading(t(R.string.notify_phones), top = 10))
        val phones = NotificationPhones.of(settings)
        if (phones.isEmpty()) body.addView(muted(t(R.string.notify_no_phones)))
        val phoneBoxes = phones.map { phone ->
            checkbox(if (phone.missing) t(R.string.notify_missing, phone.name) else phone.name, phone.chosen)
                .also { it.isEnabled = writable; body.addView(it) }
        }
        body.addView(heading(t(R.string.notify_events), top = 10))
        val eventBoxes = NotificationEvent.entries.map { event ->
            checkbox(eventName(event), event.wire in settings.events).also { it.isEnabled = writable; body.addView(it) }
        }
        val error = errorView().also { body.addView(it) }
        return HomeAssistantSection(phones, phoneBoxes, eventBoxes, error)
    }

    /** The SpotNav app's section: on or off, instant or every 15 minutes, and for what. */
    private class SpotNavSection(val on: Switch, val instant: Switch?, val eventBoxes: List<CheckBox>, val error: TextView)

    private fun switch(label: String, checked: Boolean) = Switch(context).apply {
        text = label; isChecked = checked; textSize = 16f; setTextColor(dark)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun addSpotNavSection(body: LinearLayout, top: Int): SpotNavSection {
        body.addView(sectionHeading(t(R.string.notify_app_title), top = top))
        val on = switch(t(R.string.notify_local_toggle), local.enabled).also { body.addView(it) }
        val instant = if (instantOffered()) {
            switch(t(R.string.notify_push_toggle), PushNotifications.enabled(context)).also { body.addView(it) }
        } else null
        val help = muted("", top = 2).also { body.addView(it) }
        body.addView(heading(t(R.string.notify_events), top = 10))
        val chosen = local.events
        val eventBoxes = NotificationEvent.LOCAL.map { event ->
            checkbox(eventName(event), event in chosen).also { body.addView(it) }
        }
        val error = errorView().also { body.addView(it) }
        fun follow() {
            instant?.isEnabled = on.isChecked
            eventBoxes.forEach { it.isEnabled = on.isChecked }
            help.text = t(NotificationsOverview.spotNavHelp(instant?.isChecked == true))
        }
        on.setOnCheckedChangeListener { _, _ -> follow() }
        instant?.setOnCheckedChangeListener { _, _ -> follow() }
        follow()
        return SpotNavSection(on, instant, eventBoxes, error)
    }

    private fun saver() = NotificationsSave(
        local = local,
        push = object : NotificationsSave.Push {
            override val offered get() = instantOffered()
            override val on get() = PushNotifications.enabled(context)
            override fun set(on: Boolean, done: (PushRegistration.Result) -> Unit) =
                PushNotifications.setEnabled(context, on) { result -> runOnUiThread { done(result) } }
        },
        writeHomeAssistant = { targets, events, done -> save(targets, events, done) },
        localChanged = { LocalNotifications.sync(context) },
        turnedOn = { if (LocalNotifications.needsPermission(context)) requestPermission() }
    )

    private fun openDialog() {
        val settings = record
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val homeAssistant = settings?.let { addHomeAssistantSection(body, it) }
        val spotNav = addSpotNavSection(body, top = if (homeAssistant == null) 0 else 22)
        openSaveDialog(t(R.string.notify_section), body) { dialog, saveButton ->
            val current = record
            val choice = NotificationsSave.Choice(
                homeAssistant = homeAssistant?.takeIf { writable && current != null }?.let { section ->
                    NotificationsSave.homeAssistantChoice(
                        current!!, section.phones, section.phoneBoxes.map { it.isChecked }, section.eventBoxes.map { it.isChecked }
                    )
                },
                spotNavOn = spotNav.on.isChecked,
                spotNavEvents = NotificationEvent.LOCAL.filterIndexed { index, _ -> spotNav.eventBoxes[index].isChecked }.toSet(),
                instant = spotNav.instant?.isChecked ?: PushNotifications.enabled(context)
            )
            saveButton.isEnabled = false
            homeAssistant?.error?.say(null)
            spotNav.error.say(null)
            saver().save(current, choice) { outcome -> finish(dialog, saveButton, homeAssistant, spotNav, outcome) }
        }
    }

    private fun finish(
        dialog: AlertDialog,
        saveButton: Button,
        homeAssistant: HomeAssistantSection?,
        spotNav: SpotNavSection,
        outcome: NotificationsSave.Outcome
    ) {
        if (isDestroyed) return
        repaint()
        if (outcome.saved) {
            dialog.dismiss()
            return
        }
        saveButton.isEnabled = true
        homeAssistant?.error?.say(outcome.homeAssistantError)
        spotNav.error.say(NotificationsOverview.pushFailure(outcome.push)?.let { t(it) })
        // A failed turn-on leaves instant notifications off: the switch says so.
        if (NotificationsOverview.pushFailure(outcome.push) != null) spotNav.instant?.isChecked = false
    }

    private companion object {
        const val ERROR_COLOUR = 0xFFD65C5C.toInt()
    }
}
