package se.sensnology.spotnav.ui.settings

import android.app.TimePickerDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.SeekBar
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

/** The docs page that explains the plan's wear and Balanced's margin. */
private const val PLAN_DOCS_URL = "https://github.com/henrikekblad/spotnav-home-assistant/blob/main/docs/battery-plan.md#the-wear-cost"

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
        // The battery card only where the site has a battery: its own section in Home Assistant adds one.
        if (offered.batteryControl && battery?.let(SiteSettingsShown::batteryCard) == true) addBatteryCard()
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
        text.planCosts(status)?.let { addPlanCosts(card.body, it, words.say("battery.planCostsDocs")) }
        // The mode counts even when nothing is controlled: it moves the comparison's marked row.
        if (status.control != BatteryStatus.Control.ON) card.body.addView(muted(words.say("battery.modeShadow"), bottom = 4))
        // The control switch is Home Assistant's alone: shown, with the one line under it that says what it does.
        settingRow(card.body, words.say("battery.switch"), text.controlWord(status.control), text.batteryLine(status.line))
        text.gridChargeCap(status)?.let { card.body.addView(muted(it, bottom = 4)) }
        card.body.addView(muted(t(R.string.site_settings_in_home_assistant), top = 2))
        notice?.let { card.body.addView(muted(it, top = 4)) }
    }

    /** The two fixed figures of the plan, with the docs page's name as a link after them. */
    private fun addPlanCosts(body: LinearLayout, line: String, docs: String) {
        val view = muted("$line $docs", top = 2, bottom = 4)
        view.text = android.text.SpannableString("$line $docs").apply {
            setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PLAN_DOCS_URL))) }
                }
            }, line.length + 1, line.length + 1 + docs.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        body.addView(view)
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
        // Nothing heat-related found: one row, and where heating is added.
        if (!SiteSettingsShown.heatRows(settings)) {
            settingRow(body, words.say("heat.settings.title"), words.say("heat.none.value"), t(R.string.site_settings_heat_add_in_home_assistant))
            return
        }
        // The offsets, night and away are left out until there is a path SpotNav steers: they could not act.
        val comfort = SiteSettingsShown.heatComfort(settings)
        // "Styr värmen": the opt-in is Home Assistant's alone, so it is read here, with the one line on what it does.
        if (text.steers(settings)) {
            settingRow(
                body, words.say("heat.settings.steer"), words.say(if (settings.controlEnabled) "settings.value.on" else "settings.value.off"),
                text.controlLine(settings)
            )
            // A path that has not run on a real heat pump yet says so, on or off.
            if (settings.control?.untested == true) body.addView(muted(words.say("heat.settings.untested"), bottom = 4))
            // A stored offset that was not put back: put back or kept by an administrator in Home Assistant only.
            settings.control?.offsetHome?.let { home ->
                settingRow(
                    body, words.say("heat.settings.offsetHome"), text.offsetHomeValue(home),
                    "${text.offsetHomeHelp(home)} ${t(R.string.site_settings_offset_home_in_home_assistant)}"
                )
            }
        }
        val sensors = settings.indoorSensors
        settingRow(
            body, words.say("heat.settings.indoor"),
            when {
                sensors.isEmpty() -> none
                sensors.size <= 2 -> sensors.joinToString(", ") { named(settings, it) }
                else -> words.say("heat.settings.indoorCount", "count" to sensors.size.toString())
            }
        )
        // Each chosen sensor's room aim against the house: a cooler upstairs, a sensor that reads warm.
        for (sensor in if (comfort) sensors else emptyList()) {
            val label = text.roomName(named(settings, sensor))
            val current = settings.indoorOffsetOf(sensor)
            settingRow(body, label, text.signedDegrees(current), onTap = {
                editOffset(label, current, words.say("heat.settings.sensorOffsetHelp")) { offset ->
                    HeatWrite.indoorOffset(settings, sensor, offset)
                }
            })
        }
        settingRow(body, words.say("heat.settings.outdoor"), settings.outdoorSensor?.let { named(settings, it) } ?: words.say("heat.settings.outdoorAuto"))
        settingRow(
            body, words.say("heat.settings.meter"),
            settings.meterEntity?.let { named(settings, it) } ?: words.say("heat.settings.meterNone"),
            if (settings.meterEntity == null) words.say("heat.settings.meterHelp") else null
        )
        settingRow(body, words.say("heat.settings.path"), text.pathName(settings.controlPath))
        if (settings.controlPath != "none") settingRow(body, words.say("heat.settings.control"), settings.controlEntity?.let { named(settings, it) } ?: none)
        text.secondEntityLabel(settings.controlPath)?.let { label -> settingRow(body, label, settings.controlEntityB?.let { named(settings, it) } ?: none) }
        settingRow(body, words.say("heat.settings.hotWater"), settings.hotWaterEntity?.let { named(settings, it) } ?: none)
        settingRow(
            body, words.say("heat.settings.zones"),
            if (settings.zones.isEmpty()) words.say("heat.settings.zonesNone") else settings.zones.size.toString()
        )
        for (zone in if (comfort) settings.zones else emptyList()) {
            val label = named(settings, zone.entityId)
            val air = settings.zoneKinds[zone.entityId] == "air"
            val help = if (air) words.say("heat.settings.offsetHelp") + " " + words.say("heat.settings.airHelp") else words.say("heat.settings.offsetHelp")
            settingRow(body, label, text.signedDegrees(zone.offsetC), if (air) words.say("heat.settings.airHelp") else null, onTap = {
                editOffset(label, zone.offsetC, help) { offset -> HeatWrite.zoneOffset(settings, zone.entityId, offset) }
            })
        }
        if (comfort) settingRow(body, words.say("heat.settings.night"), text.nightValue(settings.night), onTap = { editNight(settings) })
        // Only from a Home Assistant that has the setting.
        settings.awayDeltaC?.takeIf { comfort }?.let { away ->
            settingRow(
                body, words.say("heat.settings.away"), words.say("heat.settings.awayValue", "delta" to text.degrees(away)),
                onTap = {
                    editOffset(
                        words.say("heat.settings.away"), away, words.say("heat.settings.awayHelp"),
                        slider = AwaySlider, show = text::degrees
                    ) { next -> HeatWrite.awayDelta(settings, next) }
                }
            )
        }
        body.addView(muted(t(R.string.site_settings_in_home_assistant), top = 2))
        notice?.let { body.addView(muted(it, top = 4)) }
    }

    /** The name Home Assistant gave an entity; never its id: without a name, the generic word "Vald". */
    private fun named(settings: HeatSettings, entityId: String): String = settings.nameOf(entityId) ?: cardWords().say("settings.status.chosen")

    /**
     * An offset on a slider, −3 to +3 °C in half degrees, the value shown signed ("+1,0 °C") and a tick at 0. Saving
     * sends the same compare-and-set write as before and the settings are read again after it.
     */
    private fun editOffset(
        title: String, current: Double, help: String,
        slider: StepSlider = OffsetSlider, show: (Double) -> String = text::signedDegrees,
        request: (Double) -> HeatWrite.Request
    ) {
        val steps = slider.steps()
        val body = editorBody()
        body.addView(muted(help, bottom = 6))
        val value = TextView(context).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        body.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val bar = SeekBar(context).apply {
            max = steps
            progress = slider.progress(current)
            contentDescription = title
        }
        val tick = View(context).apply { setBackgroundColor(muted) }
        val track = FrameLayout(context).apply {
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            // The tick at 0: the middle of the track, above the bar (an offset only; a setback has no zero).
            if (slider === OffsetSlider) addView(tick, FrameLayout.LayoutParams(dp(2), dp(14), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        }
        body.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(slider.value(0), slider.value(steps / 2), slider.value(steps)).forEachIndexed { i, v ->
                addView(TextView(context).apply {
                    this.text = show(v); textSize = 12f; setTextColor(muted)
                    gravity = if (i == 0) Gravity.START else if (i == 1) Gravity.CENTER else Gravity.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        fun refresh() { value.text = show(slider.value(bar.progress)) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        refresh()
        val error = errorLine().also { body.addView(it) }
        openEditor(title, body, error) { done ->
            val offset = slider.value(bar.progress)
            if (offset == current) done(null) else send(request(offset), done)
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
