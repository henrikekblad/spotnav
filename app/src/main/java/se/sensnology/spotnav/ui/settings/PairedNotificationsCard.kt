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
import se.sensnology.spotnav.ha.client.PhoneNotifications
import se.sensnology.spotnav.ha.client.PhoneNotificationsCall
import se.sensnology.spotnav.ha.settings.HaNotificationSettings
import se.sensnology.spotnav.ha.settings.NotificationEvent
import se.sensnology.spotnav.notify.LocalNotificationStore
import se.sensnology.spotnav.notify.LocalNotifications
import se.sensnology.spotnav.notify.PhoneDoor
import se.sensnology.spotnav.notify.PhoneNotificationsStore
import se.sensnology.spotnav.notify.PhoneNotificationsSync
import se.sensnology.spotnav.push.PushNotifications
import se.sensnology.spotnav.push.PushRegistration
import se.sensnology.spotnav.ui.common.ValueCue
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.checkbox
import se.sensnology.spotnav.ui.common.openEditor
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
 * A paired charger's notifications, after the paired cards.
 *
 * Where its Home Assistant answers `phone_notifications` (SpotNav 3.0): one box for this phone, as Home Assistant holds
 * it for the whole installation: on or off, its events grouped as the installation has them, the chargers it follows
 * (only with more than one), and instant notifications where the build and phone offer them. Which app shows a notice
 * is Home Assistant's routing, never said here.
 *
 * Where it does not (2.x, 3.0.0b4), as before: a summary of the two routes, named by the app that shows the
 * notification. Via the Home Assistant app is the Home Assistant card's own choice of phones and events (shown only
 * when Home Assistant states it); via the SpotNav app is this phone's own check (off by default, see
 * [LocalNotifications]), at once with instant notifications where the build and phone offer them.
 */
internal class PairedNotificationsCard(scope: ViewScope, parent: LinearLayout, baseUrl: String) : ViewScope(scope) {
    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val local = LocalNotificationStore.forContext(context)
    private val doorKey = PhoneDoor.key(baseUrl)
    private val phoneStore = PhoneNotificationsStore.forContext(context)
    // Home Assistant's answer for this phone; `null` for an older Home Assistant, or while none was read.
    private var phones: PhoneNotifications? = phoneStore.current(doorKey)
    private var phonesAsked = false
    private var record: HaNotificationSettings? = null
    private var writable = false
    // Whether the record states identification, so its event is offered (see NotificationEvent.offered).
    private var identifies: Boolean? = null

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
    fun show(notifications: HaNotificationSettings?, writable: Boolean, identifies: Boolean? = null) {
        record = notifications
        this.writable = writable
        this.identifies = identifies
        if (!phonesAsked) {
            phonesAsked = true
            readPhones()
        }
        repaint()
    }

    private fun door(): PhoneDoor? = PhoneNotificationsSync.doors(context).firstOrNull { it.key == doorKey }

    /** Ask Home Assistant for this phone (an older one is asked again only after a while), then paint what it said. */
    private fun readPhones() {
        val door = door() ?: return
        worker.execute {
            val sync = PhoneNotificationsSync.forContext(context)
            if (sync.due(door)) sync.read(door)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                phones = phoneStore.current(doorKey)
                // The check follows the answer's switch from now on.
                LocalNotifications.sync(context)
                repaint()
            }
        }
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

    // --- The card ---------------------------------------------------------------------------------

    private fun repaint() {
        container.removeAllViews()
        phones?.let { return paintPhone(it) }
        val card = card(container, t(R.string.notify_section), R.drawable.ic_bell)
        val body = card.body
        record?.let { settings ->
            settingRow(body, t(R.string.notify_companion_title), NotificationsOverview.homeAssistant(settings, texts, identifies),
                onTap = { openHomeAssistant() }, target = SettingsTarget.PHONES.tag)
            if (!writable) body.addView(muted(t(R.string.settings_paired_read_only), bottom = 4))
        }
        settingRow(body, t(R.string.notify_app_title), NotificationsOverview.spotNav(local.enabled, local.events, instantOn(), texts, identifies == true),
            onTap = { openSpotNav() })
        if (local.enabled && !LocalNotifications.allowed(context)) body.addView(muted(t(R.string.notify_local_denied), bottom = 4))
    }

    // --- This phone, as Home Assistant holds it ----------------------------------------------------

    private fun paintPhone(value: PhoneNotifications) {
        val card = card(container, t(R.string.notify_section), R.drawable.ic_bell)
        val body = card.body
        settingRow(body, value.name.ifBlank { t(R.string.notify_section) }, NotificationsOverview.phone(value, instantOn(), texts),
            onTap = { openPhone() }, target = SettingsTarget.PHONES.tag)
        if (value.on && !LocalNotifications.allowed(context)) body.addView(muted(t(R.string.notify_local_denied), bottom = 4))
    }

    private fun phoneEventName(event: String): String? = when (event) {
        PhoneNotificationsCall.POOL_SEASON -> t(R.string.notify_event_pool_season)
        else -> NotificationEvent.of(event)?.let { eventName(it) }
    }

    private fun groupName(group: PhoneNotificationsEdit.Group) = t(
        when (group) {
            PhoneNotificationsEdit.Group.CHARGING -> R.string.notify_group_charging
            PhoneNotificationsEdit.Group.POOL -> R.string.notify_group_pool
            PhoneNotificationsEdit.Group.SPA -> R.string.notify_group_spa
            PhoneNotificationsEdit.Group.SAUNA -> R.string.notify_group_sauna
        }
    )

    /** This phone's editor: on or off, instant or every 15 minutes, its events by group and the chargers it follows. */
    private fun openPhone() {
        val value = phones ?: return
        val door = door() ?: return
        val body = editorBody()
        val on = switch(t(R.string.notify_phone_send), value.on).also { body.addView(it) }
        val instant = if (instantOffered()) {
            switch(t(R.string.notify_push_toggle), PushNotifications.enabled(context)).also { body.addView(it) }
        } else null
        val help = muted("", top = 2).also { body.addView(it) }
        val eventBoxes = mutableListOf<Pair<String, CheckBox>>()
        for (group in PhoneNotificationsEdit.groups(value.loads)) {
            body.addView(heading(groupName(group.group), top = 10))
            for (event in group.events) {
                val name = phoneEventName(event) ?: continue
                eventBoxes += event to checkbox(name, event in value.events).also { body.addView(it) }
                if (PhoneNotificationsEdit.atRiskHelp(value.loads, event)) body.addView(muted(t(R.string.notify_at_risk_help), bottom = 4))
            }
        }
        val chargerBoxes = if (PhoneNotificationsEdit.asksChargers(value)) {
            body.addView(heading(t(R.string.notify_phone_chargers), top = 10))
            value.installationChargers.map { charger -> charger.id to checkbox(charger.name, value.follows(charger.id)).also { body.addView(it) } }
        } else emptyList()
        val error = errorView().also { body.addView(it) }
        fun follow() {
            instant?.isEnabled = on.isChecked
            eventBoxes.forEach { it.second.isEnabled = on.isChecked }
            chargerBoxes.forEach { it.second.isEnabled = on.isChecked }
            help.text = t(NotificationsOverview.spotNavHelp(instant?.isChecked == true))
        }
        on.setOnCheckedChangeListener { _, _ -> follow() }
        instant?.setOnCheckedChangeListener { _, _ -> follow() }
        follow()
        openEditor(value.name.ifBlank { t(R.string.notify_section) }, body, error) { done ->
            val change = PhoneNotificationsEdit.change(
                value,
                on = on.isChecked,
                events = eventBoxes.filter { it.second.isChecked }.map { it.first },
                chargers = chargerBoxes.filter { it.second.isChecked }.map { it.first }
            )
            // The instant switch can only change while this phone's notifications are on.
            val pushWrite = instantOffered() && on.isChecked && instant != null && instant.isChecked != PushNotifications.enabled(context)
            val turnedOn = on.isChecked && !value.on
            fun pushThen() {
                if (!pushWrite) {
                    repaint()
                    return done(null)
                }
                PushNotifications.setEnabled(context, instant!!.isChecked) { result ->
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        repaint()
                        val failure = NotificationsOverview.pushFailure(result)
                        // A failed turn-on leaves instant notifications off: the switch says so.
                        if (failure != null) instant.isChecked = false
                        done(failure?.let { t(it) })
                    }
                }
            }
            if (turnedOn && LocalNotifications.needsPermission(context)) requestPermission()
            if (change == null) return@openEditor pushThen()
            worker.execute {
                val result = PhoneNotificationsSync.forContext(context).read(door, change = change)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    when (result) {
                        is PhoneNotificationsSync.Result.Current -> {
                            phones = result.value
                            LocalNotifications.sync(context)
                            pushThen()
                        }
                        PhoneNotificationsSync.Result.Refused -> done(t(R.string.paired_error_invalid))
                        PhoneNotificationsSync.Result.Legacy -> {
                            // Home Assistant went back to a version without it: the routes as before.
                            phones = null
                            repaint()
                            done(t(R.string.paired_error_version))
                        }
                        is PhoneNotificationsSync.Result.Failed -> done(t(R.string.paired_error_generic))
                    }
                }
            }
        }
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
        val eventBoxes = NotificationEvent.offered(settings, identifies).map { event ->
            checkbox(eventName(event), event.wire in settings.events).also { it.isEnabled = writable; body.addView(it) }
        }
        val error = errorView().also { body.addView(it) }
        return HomeAssistantSection(phones, phoneBoxes, eventBoxes, error)
    }

    /** The SpotNav app's section: on or off, instant or every 15 minutes, and for what. */
    private class SpotNavSection(
        val on: Switch,
        val instant: Switch?,
        val offered: List<NotificationEvent>,
        val eventBoxes: List<CheckBox>,
        val error: TextView
    )

    private fun switch(label: String, checked: Boolean) = Switch(context).apply {
        text = label; isChecked = checked; textSize = 16f; setTextColor(dark)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun addSpotNavSection(body: LinearLayout): SpotNavSection {
        val on = switch(t(R.string.notify_local_toggle), local.enabled).also { body.addView(it) }
        val instant = if (instantOffered()) {
            switch(t(R.string.notify_push_toggle), PushNotifications.enabled(context)).also { body.addView(it) }
        } else null
        val help = muted("", top = 2).also { body.addView(it) }
        body.addView(heading(t(R.string.notify_events), top = 10))
        val chosen = local.events
        // The question which car is plugged in only where this charger's Home Assistant identifies cars.
        val offered = NotificationEvent.localOffered(identifies == true)
        val eventBoxes = offered.map { event ->
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
        return SpotNavSection(on, instant, offered, eventBoxes, error)
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

    /** The Home Assistant app's route, on its own: the phones and the events they hear about. */
    private fun openHomeAssistant() {
        val settings = record ?: return
        val body = editorBody()
        val section = addHomeAssistantSection(body, settings)
        openEditor(t(R.string.notify_companion_title), body, section.error) { done ->
            val current = record
            if (!writable || current == null) return@openEditor done(null)
            val choice = NotificationsSave.Choice(
                homeAssistant = NotificationsSave.homeAssistantChoice(
                    current, section.phones, section.phoneBoxes.map { it.isChecked }, section.eventBoxes.map { it.isChecked }, identifies
                ),
                // This phone's own route stays as it is.
                spotNavOn = local.enabled,
                spotNavEvents = local.events,
                instant = PushNotifications.enabled(context)
            )
            saver().save(current, choice) { outcome ->
                if (isDestroyed) return@save
                repaint()
                done(outcome.homeAssistantError)
            }
        }
    }

    /** The SpotNav app's route, on its own: on or off, instant or every 15 minutes, and for what. */
    private fun openSpotNav() {
        val body = editorBody()
        val section = addSpotNavSection(body)
        openEditor(t(R.string.notify_app_title), body, section.error) { done ->
            val choice = NotificationsSave.Choice(
                // The Home Assistant app's route is not written from here.
                homeAssistant = null,
                spotNavOn = section.on.isChecked,
                spotNavEvents = NotificationEvent.localChoice(section.offered, section.eventBoxes.map { it.isChecked }, local.events),
                instant = section.instant?.isChecked ?: PushNotifications.enabled(context)
            )
            saver().save(record, choice) { outcome ->
                if (isDestroyed) return@save
                repaint()
                val failure = NotificationsOverview.pushFailure(outcome.push)
                // A failed turn-on leaves instant notifications off: the switch says so.
                if (failure != null) section.instant?.isChecked = false
                done(failure?.let { t(it) })
            }
        }
    }

    private fun editorBody() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), 0)
    }

    private companion object {
        const val ERROR_COLOUR = 0xFFD65C5C.toInt()

        /** Where this phone's notifications are read and written, off the main thread. */
        val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
