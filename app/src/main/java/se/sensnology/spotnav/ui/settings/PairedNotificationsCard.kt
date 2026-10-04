package se.sensnology.spotnav.ui.settings

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.ha.settings.HaNotificationSettings
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.checkbox
import se.sensnology.spotnav.ui.common.valueColour
import se.sensnology.spotnav.ui.common.valueLabel
import se.sensnology.spotnav.ui.common.valueRow

/** The phones a notification choice can name: the ones that exist, then chosen ones that no longer do. */
internal object NotificationPhones {
    data class Phone(val service: String, val name: String, val chosen: Boolean, val missing: Boolean)

    fun of(settings: HaNotificationSettings): List<Phone> =
        settings.available.map { Phone(it.service, it.name, it.service in settings.targets, missing = false) } +
            settings.targets.filter { target -> settings.available.none { it.service == target } }
                .map { Phone(it, it, chosen = true, missing = true) }
}

/**
 * A paired charger's notifications, after the paired cards: the Home Assistant card's own choice of
 * phones and events (sent through the Companion app; shown only when Home Assistant states it), and
 * this phone's own background check (off by default, see [LocalNotifications]).
 */
internal class PairedNotificationsCard(scope: ViewScope, parent: LinearLayout) : ViewScope(scope) {
    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val local = LocalNotificationStore.forContext(context)
    private var record: HaNotificationSettings? = null
    private var writable = false
    private var notice: String? = null

    private var save: (List<String>, List<String>, (String?) -> Unit) -> Unit = { _, _, done -> done(null) }
    private var requestPermission: () -> Unit = {}

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

    /** The permission prompt was answered: say what it means for the switch. */
    fun permissionAnswered() {
        LocalNotifications.sync(context)
        repaint()
    }

    private fun muted(text: String, top: Int = 0, bottom: Int = 0) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, dp(top), 0, dp(bottom))
    }

    private fun heading(text: String) = TextView(context).apply {
        this.text = text; textSize = 15f; setTextColor(dark); setPadding(0, dp(14), 0, dp(2))
    }

    private fun readRow(parent: LinearLayout, label: String, value: String) {
        if (value.length <= 18) {
            valueRow(parent, label, valueLabel().apply { text = value; setTextColor(palette.valueColour(ValueCue.READ_ONLY)) })
            return
        }
        parent.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(muted) })
            addView(valueLabel().apply { text = value; gravity = Gravity.START; setTextColor(palette.valueColour(ValueCue.READ_ONLY)) })
        })
    }

    private fun button(parent: LinearLayout, label: String, enabled: Boolean, onClick: () -> Unit) {
        parent.addView(Button(context).apply {
            text = label; isAllCaps = false; isEnabled = enabled; setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
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
        }
    )

    private fun eventsOn(count: Int) = t(R.string.notify_events_on, count, NotificationEvent.entries.size)

    private fun repaint() {
        container.removeAllViews()
        val card = card(container, t(R.string.notify_section), R.drawable.ic_settings)
        record?.let { addCompanion(card.body, it) }
        addLocal(card.body)
    }

    // --- Through the Companion app ----------------------------------------------------------------

    private fun addCompanion(body: LinearLayout, settings: HaNotificationSettings) {
        body.addView(heading(t(R.string.notify_companion_title)))
        body.addView(muted(t(R.string.notify_companion_intro), bottom = 4))
        val phones = NotificationPhones.of(settings).filter { it.chosen }
            .map { if (it.missing) t(R.string.notify_missing, it.name) else it.name }
        readRow(body, t(R.string.notify_phones), phones.joinToString(", ").ifEmpty { t(R.string.notify_phones_none) })
        val known = settings.events.count { NotificationEvent.of(it) != null }
        readRow(body, t(R.string.notify_events), eventsOn(known))
        if (!writable) body.addView(muted(t(R.string.settings_paired_read_only), top = 4))
        notice?.let { body.addView(muted(it, top = 4)) }
        button(body, t(R.string.notify_change), enabled = writable) { openCompanionDialog() }
    }

    private fun openCompanionDialog() {
        val settings = record ?: return
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(muted(t(R.string.notify_companion_intro), bottom = 4))
        body.addView(heading(t(R.string.notify_phones)))
        val phones = NotificationPhones.of(settings)
        if (phones.isEmpty()) body.addView(muted(t(R.string.notify_no_phones)))
        val phoneBoxes = phones.map { phone ->
            checkbox(if (phone.missing) t(R.string.notify_missing, phone.name) else phone.name, phone.chosen)
                .also { body.addView(it) }
        }
        body.addView(heading(t(R.string.notify_events)))
        val eventBoxes = NotificationEvent.entries.map { event ->
            checkbox(eventName(event), event.wire in settings.events).also { body.addView(it) }
        }
        val error = TextView(context).apply {
            textSize = 13f; setTextColor(ERROR_COLOUR); setPadding(0, dp(4), 0, 0); visibility = View.GONE
        }
        body.addView(error)
        openSaveDialog(t(R.string.notify_section), body) { dialog, saveButton ->
            val current = record ?: return@openSaveDialog
            val targets = phones.filterIndexed { index, _ -> phoneBoxes[index].isChecked }.map { it.service }
            // An event this app cannot name stays as Home Assistant has it.
            val events = NotificationEvent.entries.filterIndexed { index, _ -> eventBoxes[index].isChecked }.map { it.wire } +
                current.events.filter { NotificationEvent.of(it) == null }
            if (targets == current.targets && events.toSet() == current.events.toSet()) {
                dialog.dismiss()
                return@openSaveDialog
            }
            saveButton.isEnabled = false
            error.visibility = View.GONE
            save(targets, events) { failure ->
                if (failure == null) {
                    notice = null
                    dialog.dismiss()
                    repaint()
                } else {
                    saveButton.isEnabled = true
                    error.text = failure
                    error.visibility = View.VISIBLE
                }
            }
        }
    }

    // --- On this phone ----------------------------------------------------------------------------

    private fun addLocal(body: LinearLayout) {
        body.addView(heading(t(R.string.notify_local_title)))
        val toggle: CheckBox = checkbox(t(R.string.notify_local_toggle), local.enabled)
        body.addView(toggle)
        body.addView(muted(t(R.string.notify_local_help), top = 2))
        toggle.setOnCheckedChangeListener { _, on ->
            local.enabled = on
            LocalNotifications.sync(context)
            if (on && LocalNotifications.needsPermission(context)) requestPermission() else repaint()
        }
        if (!local.enabled) return
        if (!LocalNotifications.allowed(context)) body.addView(muted(t(R.string.notify_local_denied), top = 4))
        readRow(body, t(R.string.notify_events), eventsOn(local.events.size))
        button(body, t(R.string.notify_local_events_change), enabled = true) { openLocalDialog() }
    }

    private fun openLocalDialog() {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(muted(t(R.string.notify_local_help), bottom = 4))
        val chosen = local.events
        val boxes = NotificationEvent.entries.map { event ->
            checkbox(eventName(event), event in chosen).also { body.addView(it) }
        }
        openSaveDialog(t(R.string.notify_local_title), body) { dialog, _ ->
            local.events = NotificationEvent.entries.filterIndexed { index, _ -> boxes[index].isChecked }.toSet()
            dialog.dismiss()
            repaint()
        }
    }

    private companion object {
        const val ERROR_COLOUR = 0xFFD65C5C.toInt()
    }
}
