package se.sensnology.spotnav.ui.settings

import android.app.TimePickerDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import se.sensnology.spotnav.R
import se.sensnology.spotnav.app.AppLanguageSettings
import se.sensnology.spotnav.chargers.ChargerCapabilities
import se.sensnology.spotnav.ha.house.SiteRead
import se.sensnology.spotnav.ha.site.BatteryControlRead
import se.sensnology.spotnav.ha.site.BatteryStatus
import se.sensnology.spotnav.ha.site.HeatNight
import se.sensnology.spotnav.ha.site.HeatSettings
import se.sensnology.spotnav.ha.heat.HeatWrite
import se.sensnology.spotnav.ui.common.NumberSpec
import se.sensnology.spotnav.ui.common.ViewScope
import se.sensnology.spotnav.ui.common.cardWords
import se.sensnology.spotnav.ui.common.card
import se.sensnology.spotnav.ui.common.chooseOne
import se.sensnology.spotnav.ui.common.editNumber
import se.sensnology.spotnav.ui.common.editorBody
import se.sensnology.spotnav.ui.common.errorLine
import se.sensnology.spotnav.ui.common.openEditor
import se.sensnology.spotnav.ui.common.settingRow
import se.sensnology.spotnav.ui.common.weight

/**
 * The battery and heat cards of a paired charger's Settings, drawn only where the charger's Home Assistant offers them
 * (`capabilities.battery_control`, `capabilities.get_heat`). What the webhook lets the app change is written: the
 * battery mode, the cooler night and the offsets of the stored zones and indoor sensors. The control switches
 * ("Batteristyrning", "Styr värmen") and the entity choices are shown read-only, with a line saying where they are
 * changed. Each write is followed by a fresh read, and a refusal is worded as the card words it.
 */
internal class SiteSettingsCards(scope: ViewScope, private val parent: LinearLayout) : ViewScope(scope) {
    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var capabilities: ChargerCapabilities? = null
    private var battery: BatteryControlRead.Outcome? = null
    private var heat: SiteRead<HeatSettings>? = null
    private var notice: String? = null
    private val text get() = SiteSettingsText(cardWords(), AppLanguageSettings.numberLocale(activity))

    private var readBattery: (String?, (BatteryControlRead.Outcome) -> Unit) -> Unit = { _, done -> done(BatteryControlRead.Outcome.Failed(null)) }
    private var readHeat: ((SiteRead<HeatSettings>) -> Unit) -> Unit = { done -> done(SiteRead.Failed(null)) }
    private var writeHeat: (HeatWrite.Request, (HeatWrite.Outcome) -> Unit) -> Unit =
        { _, done -> done(HeatWrite.Outcome.Failed(null)) }

    init {
        parent.addView(container)
    }

    /** Where the reads and writes are carried out (the screen owns the connection). */
    fun attach(
        battery: (String?, (BatteryControlRead.Outcome) -> Unit) -> Unit,
        heatRead: ((SiteRead<HeatSettings>) -> Unit) -> Unit,
        heatWrite: (HeatWrite.Request, (HeatWrite.Outcome) -> Unit) -> Unit
    ) {
        readBattery = battery
        readHeat = heatRead
        writeHeat = heatWrite
    }

    /** Draw the cards the charger's capabilities offer, and read what they show. */
    fun show(offered: ChargerCapabilities?) {
        capabilities = offered
        if (offered == null) {
            container.removeAllViews()
            return
        }
        if (offered.batteryControl) loadBattery(null)
        if (offered.getHeat) loadHeat()
    }

    private fun loadBattery(mode: String?, then: ((BatteryControlRead.Outcome) -> Unit)? = null) {
        readBattery(mode) { outcome ->
            if (isDestroyed) return@readBattery
            // A refused write leaves the status as it was shown; a status or a failed first read replaces it.
            if (outcome is BatteryControlRead.Outcome.Status || battery == null) battery = outcome
            then?.invoke(outcome)
            repaint()
        }
    }

    private fun loadHeat() {
        readHeat { outcome ->
            if (isDestroyed) return@readHeat
            if (outcome is SiteRead.Read || heat == null) heat = outcome
            repaint()
        }
    }

    private fun repaint() {
        container.removeAllViews()
        val offered = capabilities ?: return
        if (offered.batteryControl && battery != null) addBatteryCard()
        if (offered.getHeat && heat != null) addHeatCard()
    }

    private fun muted(text: String, top: Int = 0, bottom: Int = 0) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(muted); setPadding(0, dp(top), 0, dp(bottom))
    }

    private fun issueText(issue: SiteSettingsText.Issue, isBattery: Boolean): String =
        text.cardSentence(issue, isBattery) ?: t(
            when (issue) {
                SiteSettingsText.Issue.NOT_PERMITTED -> R.string.site_error_not_permitted
                SiteSettingsText.Issue.NO_SITE -> R.string.site_error_unavailable
                SiteSettingsText.Issue.VERSION -> R.string.paired_error_version
                else -> R.string.paired_error_generic
            }
        )

    // --- the battery ----------------------------------------------------------------------------

    private fun addBatteryCard() {
        val words = cardWords()
        val card = card(container, words.say("battery.title"), R.drawable.ic_now_battery)
        val status = (battery as? BatteryControlRead.Outcome.Status)?.status
        if (status == null) {
            card.body.addView(muted(words.say("battery.failed"), top = 8))
            return
        }
        val modeLabel = words.say("battery.modeLabel")
        settingRow(card.body, modeLabel, text.modeName(status.mode), text.modeHelp(status.mode), onTap = {
            val modes = BatteryStatus.MODES
            chooseOne(
                modeLabel,
                modes.map { text.modeName(it) },
                modes.indexOf(status.mode),
                helps = modes.map { text.modeHelp(it) }
            ) { index, done -> writeMode(modes[index], done) }
        })
        // The mode counts even when nothing is controlled: it moves the comparison's marked row.
        if (status.control != BatteryStatus.Control.ON) card.body.addView(muted(words.say("battery.modeShadow"), bottom = 4))
        // The control switch is Home Assistant's alone: shown, with the one line under it that says what it does.
        settingRow(card.body, words.say("battery.switch"), text.controlWord(status.control), text.batteryLine(status.line))
        text.gridChargeCap(status)?.let { card.body.addView(muted(it, bottom = 4)) }
        card.body.addView(muted(t(R.string.site_settings_in_home_assistant), top = 2))
        notice?.let { card.body.addView(muted(it, top = 4)) }
    }

    private fun writeMode(mode: String, done: (String?) -> Unit) {
        loadBattery(mode) { outcome ->
            val issue = text.batteryIssue(outcome)
            if (issue == null) {
                notice = null
                done(null)
            } else {
                done(issueText(issue, isBattery = true))
            }
        }
    }

    // --- the heat -------------------------------------------------------------------------------

    private fun addHeatCard() {
        val words = cardWords()
        val card = card(container, words.say("heat.settings.title"), R.drawable.ic_now_heat)
        val settings = (heat as? SiteRead.Read)?.value
        if (settings == null) {
            card.body.addView(muted(t(R.string.site_settings_heat_failed), top = 8))
            return
        }
        val none = words.say("heat.settings.none")
        val body = card.body
        // "Styr värmen": the opt-in is Home Assistant's alone, so it is read here.
        if (settings.controlPath == "relay" || settings.controlPath == "sg_ready" || settings.controlEnabled) {
            settingRow(body, words.say("heat.settings.steer"), words.say(if (settings.controlEnabled) "settings.value.on" else "settings.value.off"))
        }
        val sensors = settings.indoorSensors
        settingRow(
            body, words.say("heat.settings.indoor"),
            when {
                sensors.isEmpty() -> none
                sensors.size <= 2 -> sensors.joinToString(", ") { settings.nameOf(it) }
                else -> words.say("heat.settings.indoorCount", "count" to sensors.size.toString())
            }
        )
        // Each chosen sensor's room aim against the house: a cooler upstairs, a sensor that reads warm.
        for (sensor in sensors) {
            val label = text.roomName(settings.nameOf(sensor))
            val current = settings.indoorOffsetOf(sensor)
            settingRow(body, label, text.signedDegrees(current), onTap = {
                editOffset(label, current, words.say("heat.settings.sensorOffsetHelp")) { offset ->
                    HeatWrite.indoorOffset(settings, sensor, offset)
                }
            })
        }
        settingRow(body, words.say("heat.settings.outdoor"), settings.outdoorSensor?.let { settings.nameOf(it) } ?: words.say("heat.settings.outdoorAuto"))
        settingRow(
            body, words.say("heat.settings.meter"),
            settings.meterEntity?.let { settings.nameOf(it) } ?: words.say("heat.settings.meterNone"),
            if (settings.meterEntity == null) words.say("heat.settings.meterHelp") else null
        )
        settingRow(body, words.say("heat.settings.path"), text.pathName(settings.controlPath))
        if (settings.controlPath != "none") settingRow(body, words.say("heat.settings.control"), settings.controlEntity?.let { settings.nameOf(it) } ?: none)
        if (settings.controlPath == "sg_ready") settingRow(body, words.say("heat.settings.controlB"), settings.controlEntityB?.let { settings.nameOf(it) } ?: none)
        settingRow(body, words.say("heat.settings.hotWater"), settings.hotWaterEntity?.let { settings.nameOf(it) } ?: none)
        settingRow(
            body, words.say("heat.settings.zones"),
            if (settings.zones.isEmpty()) words.say("heat.settings.zonesNone") else settings.zones.size.toString()
        )
        for (zone in settings.zones) {
            val label = settings.nameOf(zone.entityId)
            val air = settings.zoneKinds[zone.entityId] == "air"
            val help = if (air) words.say("heat.settings.offsetHelp") + " " + words.say("heat.settings.airHelp") else words.say("heat.settings.offsetHelp")
            settingRow(body, label, text.signedDegrees(zone.offsetC), if (air) words.say("heat.settings.airHelp") else null, onTap = {
                editOffset(label, zone.offsetC, help) { offset -> HeatWrite.zoneOffset(settings, zone.entityId, offset) }
            })
        }
        settingRow(body, words.say("heat.settings.night"), text.nightValue(settings.night), onTap = { editNight(settings) })
        body.addView(muted(t(R.string.site_settings_in_home_assistant), top = 2))
        notice?.let { body.addView(muted(it, top = 4)) }
    }

    /** An offset, −3 to +3 °C in half degrees. */
    private fun editOffset(title: String, current: Double, help: String, request: (Double) -> HeatWrite.Request) {
        val words = cardWords()
        val spec = NumberSpec(HeatWrite.OFFSET_MIN, HeatWrite.OFFSET_MAX, 1, minDecimals = 1)
        editNumber(title, spec, "°C", current, words.say("heat.error.range"), help = help, signed = true) { value, done ->
            val offset = value ?: 0.0
            if (!HeatWrite.isHalfStep(offset)) return@editNumber done(words.say("heat.error.step"))
            send(request(offset), done)
        }
    }

    /** The cooler night: on or off, 0.5 to 3 degrees in half degrees, and the two times. */
    private fun editNight(settings: HeatSettings) {
        val words = cardWords()
        val night = settings.night
        val body = editorBody()
        body.addView(muted(words.say("heat.settings.nightHelp"), bottom = 6))
        val on = CheckBox(context).apply { this.text = words.say("settings.value.on"); textSize = 16f; isChecked = night.on }
        body.addView(on)
        val shownDelta = text.fixed(night.deltaC, 1)
        val delta = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(shownDelta)
            setSelectAllOnFocus(true)
        }
        body.addView(fieldRow(words.say("heat.settings.nightDelta"), delta, "°C"))
        var start = night.start
        var end = night.end
        val startView = timeView(start) { start = it }
        val endView = timeView(end) { end = it }
        body.addView(fieldRow(words.say("heat.settings.nightStart"), startView))
        body.addView(fieldRow(words.say("heat.settings.nightEnd"), endView))
        val error = errorLine().also { body.addView(it) }
        openEditor(words.say("heat.settings.night"), body, error) { done ->
            val spec = NumberSpec(HeatWrite.NIGHT_MIN, HeatWrite.NIGHT_MAX, 1, minDecimals = 1)
            val value = (spec.check(delta.text.toString()) as? se.sensnology.spotnav.ui.common.NumberCheck.Valid)?.value
            when {
                value == null -> done(words.say("heat.error.range"))
                !HeatWrite.isHalfStep(value) -> done(words.say("heat.error.step"))
                else -> {
                    val next = HeatNight(on.isChecked, value, start, end)
                    if (next == night) done(null) else send(HeatWrite.night(settings, next), done)
                }
            }
        }
    }

    private fun fieldRow(label: String, field: View, unit: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(muted) }, weight())
        addView(field, LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT))
        if (unit != null) addView(TextView(context).apply { text = unit; textSize = 15f; setTextColor(muted); setPadding(dp(8), 0, 0, 0) })
    }

    /** A time as text; tapping it opens the clock, in 24 hours, and the chosen time is kept as `HH:MM`. */
    private fun timeView(initial: String, chosen: (String) -> Unit) = TextView(context).apply {
        text = initial
        textSize = 16f
        setTextColor(accent)
        gravity = Gravity.END
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val (hour, minute) = text.toString().split(":").map { it.toInt() }
            TimePickerDialog(context, { _, h, m ->
                val picked = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)
                text = picked
                chosen(picked)
            }, hour, minute, true).show()
        }
    }

    /** One heat write, then a fresh read; [done] gets the refusal in words, or `null` when it took. */
    private fun send(request: HeatWrite.Request, done: (String?) -> Unit) {
        writeHeat(request) { outcome ->
            if (isDestroyed) return@writeHeat
            val issue = text.heatIssue(outcome)
            // After every answer, also a refused one (a conflict shows the latest), the settings are read again.
            loadHeat()
            if (issue == null) {
                notice = null
                done(null)
            } else {
                done(issueText(issue, isBattery = false))
            }
        }
    }
}
