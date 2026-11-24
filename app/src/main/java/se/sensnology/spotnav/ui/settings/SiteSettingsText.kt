package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.app.noMinusZero
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.site.SitePriority
import se.sensnology.spotnav.ha.dashboard.DashboardSite
import se.sensnology.spotnav.ha.dashboard.DashboardSummary
import se.sensnology.spotnav.ha.dashboard.DemandBlock
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ha.site.BatteryControlRead
import se.sensnology.spotnav.ha.site.BatteryStatus
import se.sensnology.spotnav.ha.site.HeatNight
import se.sensnology.spotnav.ha.site.HeatSettings
import se.sensnology.spotnav.ui.site.HeatHoldText
import se.sensnology.spotnav.ha.heat.HeatSteering
import se.sensnology.spotnav.ha.heat.HeatWrite
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.site.HeatNowModel
import se.sensnology.spotnav.ui.site.SiteText
import java.time.Instant
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

    /** Balanced's selling margin as the mode dialog says it; `null` without the plan's figures. A fixed figure, not a setting. */
    fun planMargin(status: BatteryStatus): String? {
        val costs = status.planCosts ?: return null
        return words.say("battery.planMargin", "margin" to "${planMoney(costs.balancedMarginMinorPerKwh, costs)}/kWh")
    }

    /** A per-kWh figure in the area's minor unit whatever its size ("15 öre", "1,4 cent"): whole when it is, else up to two decimals. */
    fun planMinor(minor: Double, costs: BatteryStatus.PlanCosts): String {
        val shown = if (minor % 1.0 == 0.0) fixed(minor, 0) else fixed(minor, 2).trimEnd('0')
        return "$shown ${costs.minorUnit}"
    }

    /** The wear row's label, in the area's minor unit ("Slitage (öre/kWh åt varje håll)"). */
    fun wearLabel(costs: BatteryStatus.PlanCosts): String = words.say("battery.wear.label", "unit" to "${costs.minorUnit}/kWh")

    /** The wear the plan counts: the site's own ("20 öre/kWh"), or Default; `null` from a Home Assistant without a wear of the site's own. */
    fun wearValue(status: BatteryStatus): String? {
        val costs = status.planCosts ?: return null
        if (costs.wearDefaultMinorPerKwh == null) return null
        return status.wearOwnMinorPerKwh?.let { "${planMinor(it, costs)}/kWh" } ?: words.say("battery.wear.default")
    }

    /** The wear's help line with the currency's default ("… Standard 15 öre."); `null` as [wearValue]. */
    fun wearHelp(status: BatteryStatus): String? {
        val costs = status.planCosts ?: return null
        val fallback = costs.wearDefaultMinorPerKwh ?: return null
        return words.say("battery.wear.help", "default" to planMinor(fallback, costs))
    }

    /** A cold battery's preheating off or its switch disabled (the card's `preheatLine`); `null` for none or a newer code. */
    fun preheatLine(status: BatteryStatus): String? {
        val block = status.preheat ?: return null
        if (block.code != "off" && block.code != "disabled") return null
        val temp = block.cellC?.let { fixed(it, 1) } ?: "–"
        return words.say("battery.preheat.${block.code}", "temp" to temp, "name" to PREHEAT_SWITCH_NAME)
    }

    /** Facts the plan needs in disabled entities, in one line (the card's facts notice); `null` with none. */
    fun factsLine(status: BatteryStatus): String? {
        val found = status.enableFacts ?: return null
        if (found.entities == 0) return null
        val size = "size" in found.needs
        val floor = "floor" in found.needs
        val which = when {
            "rating" in found.needs -> if (size || floor) "several" else "rating"
            size && floor -> "both"
            size -> "size"
            else -> "floor"
        }
        return words.say("battery.enableFacts.$which", "integration" to found.integration)
    }

    /** The minor unit below one major unit ("15 öre"), the major one from there, whole when it is or from 10 up ("1 kr"). */
    fun planMoney(minor: Double, costs: BatteryStatus.PlanCosts): String {
        if (Math.abs(minor) < 100 && minor != 0.0) {
            return "${fixed(minor, if (minor % 1.0 == 0.0) 0 else 1)} ${costs.minorUnit}"
        }
        val major = minor / 100
        return "${fixed(major, if (major % 1.0 == 0.0 || Math.abs(major) >= 10) 0 else 2)} ${costs.majorUnit}"
    }

    // ---- the heat

    fun degrees(value: Double): String = "${fixed(value, 1)} °C"

    fun signedDegrees(value: Double): String =
        if (value == 0.0) "0 °C" else "${if (value > 0) "+" else "−"}${fixed(Math.abs(value), 1)} °C"

    fun nightValue(night: HeatNight): String =
        if (night.on) words.say("heat.settings.nightValue", "delta" to degrees(night.deltaC), "start" to night.start, "end" to night.end)
        else words.say("settings.value.off")

    fun pathName(path: String): String = if (path in PATHS) words.say("heat.path.$path") else path

    /** Whether "Styr värmen" has a row: on the paths SpotNav can steer, or while it is on. */
    fun steers(settings: HeatSettings): Boolean = SiteSettingsShown.heatComfort(settings)

    /** The label of the second entity's row: SG Ready's B contact, or the API's activation switch; `null`: no row. */
    fun secondEntityLabel(path: String): String? = when (path) {
        "sg_ready" -> words.say("heat.settings.controlB")
        "sg_ready_api" -> words.say("heat.settings.controlApiSwitch")
        else -> null
    }

    /** An offset as the card writes it: "0", "+1", "−2", "+1,5". */
    fun offsetValue(value: Double): String {
        if (value == 0.0) return "0"
        val whole = value == Math.floor(value)
        return "${if (value > 0) "+" else "−"}${fixed(Math.abs(value), if (whole) 0 else 1)}"
    }

    /** The stored offset's row value: "−2 (var 0)". */
    fun offsetHomeValue(home: se.sensnology.spotnav.ha.site.HeatControlStatus.OffsetHome): String =
        words.say("heat.settings.offsetHomeValue", "value" to offsetValue(home.value), "home" to offsetValue(home.home))

    /** The stored offset's question, as the card's editor asks it. */
    fun offsetHomeHelp(home: se.sensnology.spotnav.ha.site.HeatControlStatus.OffsetHome): String =
        words.say("heat.settings.offsetHomeHelp", "value" to offsetValue(home.value), "home" to offsetValue(home.home))

    /**
     * The line under "Styr värmen" (the card's `controlLine`): off, checking that the pump answers, controlling
     * (lowered or raised until when, or the pump's normal curve and why), or what it waits for. The relay's words name
     * the relay; the paths through the pump's own settings have their own. `null` without the control's status.
     */
    fun controlLine(settings: HeatSettings): String? {
        val control = settings.control ?: return null
        if (!control.enabled || control.state == "off") return words.say("heat.control.off")
        val own = if (settings.controlPath in RELAY_WORD_PATHS) "heat.control" else "heat.control.path"
        val reason = control.reason ?: ""
        return when (control.state) {
            "verifying" -> words.say("$own.${if (reason == "wait") "verifyingWait" else "verifying"}")
            "running" -> when {
                control.raised -> control.raiseUntil?.let { words.say("heat.control.raised", "until" to moment(settings, it)) }
                    ?: words.say("heat.control.raisedNow")
                control.lowered -> control.until?.let {
                    // Lowered one step by the shed order under the ceiling where the plan alone would not.
                    val key = when {
                        reason == "ceiling" -> "heat.control.loweredCeiling"
                        settings.wouldReason == "room_warm" -> "heat.control.loweredWarm"
                        else -> "heat.control.lowered"
                    }
                    words.say(key, "until" to moment(settings, it))
                } ?: words.say("heat.control.loweredNow")
                reason in CONTROL_SAFETY -> words.say("heat.control.$reason")
                else -> words.say("heat.control.normal")
            }
            else -> when {
                reason !in CONTROL_WAITS -> words.say("heat.control.waiting")
                else -> words.say("${if (reason in PATH_WAITS) own else "heat.control"}.wait.$reason", "name" to (control.automationName ?: ""))
            }
        }
    }

    /** The words for what SpotNav holds on the zones and the water heater, in the answer's moment and time zone. */
    fun hold(settings: HeatSettings): HeatHoldText =
        HeatHoldText(SiteText(words, locale), settings.now ?: Instant.now(), settings.zone ?: java.time.ZoneId.systemDefault())

    /** A time the line names: the clock today, the day before it otherwise ("fre 20 nov 16:00"). */
    private fun moment(settings: HeatSettings, at: Instant): String {
        val zone = settings.zone ?: java.time.ZoneId.systemDefault()
        return HeatNowModel.moment(SiteText(words, locale), settings.now ?: Instant.now(), zone, at)
    }

    fun fixed(value: Double, decimals: Int): String = String.format(locale, "%.${decimals}f", noMinusZero(value, decimals))

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
        /** The name a preheating switch goes by (the card's fallback when Home Assistant gives none). */
        const val PREHEAT_SWITCH_NAME = "ESS Preheating Enable"
        val PATHS = setOf("none", "relay", "sg_ready", "sg_ready_api", "offset", "setpoint")
        /** The paths whose control line names the relay (the card's `isRelayPath`). */
        val RELAY_WORD_PATHS = setOf("none", "relay", "sg_ready")
        val CONTROL_WAITS = setOf(
            "automation", "other_controller", "pump_price_control", "relay_unavailable", "relay_closed", "no_relay", "no_signal",
            "check_failed", "foreign_change", "no_read_back"
        )
        val CONTROL_SAFETY = setOf("too_cold", "room_cold", "stale", "sg_ready_b", "hot_water", "hot_water_low")
        /** The waits whose words name the relay: the other paths have their own (`heat.control.path.wait.*`). */
        val PATH_WAITS = setOf("relay_unavailable", "relay_closed", "no_relay", "no_signal", "check_failed", "foreign_change", "no_read_back")
    }
}

/**
 * What the paired Settings leave out where it could not act, by the Home Assistant card's rules (`settings-popover.ts`,
 * `heat-model.ts`, `card-view.ts`): the battery and heat cards, the import target, car or battery first, the Solar card.
 */
internal object SiteSettingsShown {
    /** The battery card is left out on a site with no battery; a failed read still shows the card with its line. */
    fun batteryCard(outcome: BatteryControlRead.Outcome): Boolean =
        !(outcome is BatteryControlRead.Outcome.Status && !outcome.status.batteryFound)

    /** The heat card's rows, or the one "Nothing found" row where no heating is found. */
    fun heatRows(settings: HeatSettings): Boolean = settings.found

    /** The offsets, night and away act only through SpotNav's steering: shown on a path it steers, or while it is on. */
    fun heatComfort(settings: HeatSettings): Boolean =
        HeatSteering.steers(settings.controlPath, settings.controlEnabled)

    /**
     * The import target row is shown unless the site cannot measure it (no total grid power) and none is set: a set
     * target stays, so it can be cleared (the card's `importTargetShown`).
     */
    fun importTarget(demand: DemandBlock): Boolean =
        demand.importTargetKw != null || demand.available || demand.reason != DEMAND_NEEDS_TOTAL_GRID_POWER

    /**
     * The old priority controls (car or battery first, the charger's priority) are a Home Assistant 2.x's: shown while
     * `get_site_settings_view` states no priority list ([list] `null`: no `priority` block, a 2.x view, or not read).
     * Where it states one (3.0), the list is shown read-only in their place and they are left out.
     */
    fun oldPriorityControls(list: SitePriority?): Boolean = list == null

    /**
     * Car or battery first chooses between two solar sinks: shown only with a home battery and where the site follows
     * solar (Sol and Hybrid both offered), as the card has it.
     */
    fun solarPriority(dashboard: Dashboard, setup: DashboardSummary.Site?): Boolean =
        setup?.batteryName != null &&
            HaSettingsStrategy.SOLAR in dashboard.strategyOptions && HaSettingsStrategy.HYBRID in dashboard.strategyOptions

    /** The Solar card holds the forecast source only: left out where there is none to choose and none chosen. */
    fun solarCard(site: DashboardSite): Boolean =
        site.solarForecastChoices.isNotEmpty() || site.solarForecastSelected.isNotEmpty()

    const val DEMAND_NEEDS_TOTAL_GRID_POWER = "needs_total_grid_power"
}
