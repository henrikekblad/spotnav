package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.site.BatteryControlRead
import se.sensnology.spotnav.ha.site.BatteryStatus
import se.sensnology.spotnav.ha.site.HeatNight
import se.sensnology.spotnav.ha.heat.HeatWrite
import se.sensnology.spotnav.ui.common.CardWords
import java.util.Locale

/**
 * The wording of the battery and heat settings, as the Home Assistant card words them (`settings-popover.ts`,
 * `heat-view.ts`): the same card keys, so the app says the same thing in the same words. Pure over [CardWords].
 */
internal class SiteSettingsText(private val words: CardWords, private val locale: Locale) {
    // ---- the battery

    fun modeName(mode: String): String = words.say("house.mode.${knownMode(mode)}")

    fun modeHelp(mode: String): String = words.say("battery.modeHelp.${knownMode(mode)}")

    private fun knownMode(mode: String) = if (mode in BatteryStatus.MODES) mode else "balanced"

    /** The control's state in a word: On, Off, or Test while it runs on paper only. */
    fun controlWord(control: BatteryStatus.Control): String = when (control) {
        BatteryStatus.Control.ON -> words.say("settings.value.on")
        BatteryStatus.Control.OFF -> words.say("settings.value.off")
        BatteryStatus.Control.TEST -> words.say("header.test")
    }

    /** The one line under the switch (`status.line`) in words. */
    fun batteryLine(line: String): String {
        LINE_KEYS[line]?.let { return words.say(it) }
        val parts = line.split(":")
        val kind = parts[0]
        val first = parts.getOrNull(1)
        val second = parts.getOrNull(2)
        return when (kind) {
            "waiting", "waiting_after_update" -> {
                val base = words.say(if (kind == "waiting") "battery.line.waiting" else "battery.line.waitingAfterUpdate")
                first?.let { WAITING_KEYS[it] }?.let { "$base ${words.say(it)}" } ?: base
            }
            "failed" -> words.say(
                "battery.line.failed",
                "mode" to modeNameOfCheck(if (first == null || first == "not_judgeable") "hold" else first)
            ) + if (second == "attempts") " ${words.say("battery.line.failedAttempts")}" else ""
            "blocked" -> words.say(if (first == "daily_write_cap") "battery.line.blockedWrites" else "battery.line.blocked")
            else -> line
        }
    }

    private fun modeNameOfCheck(mode: String): String =
        CHECK_MODE_KEYS[mode]?.let { words.say(it) } ?: mode.replace('_', ' ')

    /**
     * The one line under what SpotNav may do: the grid charge cap and what it rests on; `null` when charging from the
     * grid is not allowed or the cap could not be worked out.
     */
    fun gridChargeCap(status: BatteryStatus): String? {
        val cap = status.gridChargeCap
        if ("grid_charge" !in status.allowedActions) return null
        val kw = cap.capKw
        if (kw == null) return if (cap.rule == "import_limit") words.say("battery.cap.importLimit") else null
        if (cap.rule != "learned" && cap.rule != "learning" && cap.rule != "conservative") return null
        val ruleWords = words.say("battery.cap.rule.${cap.rule}")
        // How long it still takes: while cautious, until the first days are learned; while learning, until the
        // further margin is gone.
        val days = when {
            cap.daysMin == null || cap.daysSpread == null || cap.daysLeft == null -> 0
            cap.rule == "conservative" -> maxOf(cap.daysMin - cap.daysSpread, 1)
            cap.rule == "learning" -> cap.daysLeft
            else -> 0
        }
        val left = days.takeIf { it > 0 }
        if (cap.rule == "learning" && kw == 0.0) {
            // One reason, not two: the learning's further margin where the learned peaks alone would leave a charge,
            // the house itself where they would not.
            return if ((cap.capLearnedKw ?: 0.0) > 0 && left != null) {
                words.say("battery.cap.zeroLearning", "left" to daysLeft(left))
            } else {
                words.say("battery.cap.zero", "rule" to words.say("battery.cap.rule.learned"))
            }
        }
        val rule = if (left == null) ruleWords else "$ruleWords, ${daysLeft(left)}"
        return if (kw == 0.0) words.say("battery.cap.zero", "rule" to rule)
        else words.say("battery.cap.line", "kw" to fixed(kw, 1), "rule" to rule)
    }

    private fun daysLeft(days: Int): String =
        words.say("learning.daysLeft.${if (days == 1) "one" else "other"}", "days" to days.toString())

    // ---- the heat

    fun degrees(value: Double): String = "${fixed(value, 1)} °C"

    fun signedDegrees(value: Double): String =
        if (value == 0.0) "0 °C" else "${if (value > 0) "+" else "−"}${fixed(Math.abs(value), 1)} °C"

    fun nightValue(night: HeatNight): String =
        if (night.on) words.say("heat.settings.nightValue", "delta" to degrees(night.deltaC), "start" to night.start, "end" to night.end)
        else words.say("settings.value.off")

    fun pathName(path: String): String = if (path in PATHS) words.say("heat.path.$path") else path

    fun fixed(value: Double, decimals: Int): String = String.format(locale, "%.${decimals}f", value)

    /** The sensor's name as a room: the word for temperature at its end left out ("Ovanvåning temperatur" is "Ovanvåning"). */
    fun roomName(name: String): String {
        val trimmed = name.replace(Regex("\\s+(temperatur|temperature|température|temperatura|lämpötila|temperatuur)$", RegexOption.IGNORE_CASE), "").trim()
        return trimmed.ifEmpty { name }
    }

    // ---- what a refused write says

    /** What went wrong with a write, for the screen to word. */
    enum class Issue { CONFLICT, NOT_PERMITTED, RANGE, STEP, NO_SITE, VERSION, GENERIC }

    fun heatIssue(outcome: HeatWrite.Outcome): Issue? = when (outcome) {
        HeatWrite.Outcome.Written -> null
        HeatWrite.Outcome.Conflict -> Issue.CONFLICT
        HeatWrite.Outcome.NotPermitted -> Issue.NOT_PERMITTED
        is HeatWrite.Outcome.Refused -> when {
            "not_a_step" in outcome.codes -> Issue.STEP
            "out_of_range" in outcome.codes -> Issue.RANGE
            else -> Issue.GENERIC
        }
        HeatWrite.Outcome.NoSite -> Issue.NO_SITE
        HeatWrite.Outcome.Unsupported -> Issue.VERSION
        is HeatWrite.Outcome.Failed -> Issue.GENERIC
    }

    fun batteryIssue(outcome: BatteryControlRead.Outcome): Issue? = when (outcome) {
        is BatteryControlRead.Outcome.Status -> null
        BatteryControlRead.Outcome.NotPermitted -> Issue.NOT_PERMITTED
        BatteryControlRead.Outcome.Invalid -> Issue.GENERIC
        BatteryControlRead.Outcome.NoSite -> Issue.NO_SITE
        BatteryControlRead.Outcome.NotSupported -> Issue.VERSION
        is BatteryControlRead.Outcome.Failed -> Issue.GENERIC
    }

    /** The card's own sentence for the issues it has words for; `null` for the rest (the screen's own words). */
    fun cardSentence(issue: Issue, battery: Boolean): String? = when (issue) {
        Issue.CONFLICT -> words.say("heat.error.conflict")
        Issue.RANGE -> words.say("heat.error.range")
        Issue.STEP -> words.say("heat.error.step")
        Issue.GENERIC -> words.say(if (battery) "battery.error.generic" else "heat.error.generic")
        else -> null
    }

    private companion object {
        val LINE_KEYS = mapOf(
            "off" to "battery.line.off",
            "shadow_only" to "battery.line.shadowOnly",
            "checking" to "battery.line.checking",
            "checking_after_update" to "battery.line.checkingAfterUpdate",
            "on" to "battery.line.on",
            "watching:ai_mode" to "battery.line.watchingAiMode"
        )
        val WAITING_KEYS = mapOf(
            "firmware_unknown" to "battery.waiting.firmwareUnknown",
            "retry_gap" to "battery.waiting.retryGap",
            "no_battery_control" to "battery.waiting.noBatteryControl",
            "not_evening" to "battery.waiting.notEvening",
            "car_charging" to "battery.waiting.carCharging",
            "battery_not_discharging" to "battery.waiting.notDischarging",
            "soc_out_of_range" to "battery.waiting.socOutOfRange",
            "headroom_too_small" to "battery.waiting.headroom",
            "stale_data" to "battery.waiting.staleData",
            "executor_busy" to "battery.waiting.busy"
        )
        val CHECK_MODE_KEYS = mapOf(
            "hold" to "battery.mode.hold",
            "charge" to "battery.mode.charge",
            "discharge_house" to "battery.mode.dischargeHouse",
            "normal" to "battery.mode.normal"
        )
        val PATHS = setOf("none", "relay", "sg_ready", "offset", "setpoint")
    }
}
