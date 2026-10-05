package se.sensnology.spotnav.ha.settings

import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalDate

/** `driver`: what the plan is a function of — an amount of energy, or a target state of charge. */
enum class HaSettingsDriver(val wire: String) {
    MANUAL_KWH("manual_kwh"),
    TARGET_SOC("target_soc");

    companion object {
        fun of(value: Any?): HaSettingsDriver? = entries.find { it.wire == value }
    }
}

/** What the system optimizes. */
enum class HaSettingsStrategy(val wire: String) {
    CHEAPEST("cheapest"),
    SOLAR("solar"),
    HYBRID("hybrid");

    companion object {
        fun of(value: Any?): HaSettingsStrategy? = entries.find { it.wire == value }
    }
}

/**
 * One fiscal component as a **three-state** value: off, on with an explicit figure, or on with
 * none. [value] is `null` in the last case and only there.
 */
data class HaFiscalValue(val enabled: Boolean, val value: Double?) {
    companion object {
        val OFF = HaFiscalValue(enabled = false, value = null)
    }
}

/** One area's overrides, inside one charger's record. Fee figures belong to *their* area's currency. */
data class HaAreaOverride(
    val areaId: String,
    val vat: HaFiscalValue = HaFiscalValue.OFF,
    val tax: HaFiscalValue = HaFiscalValue.OFF,
    val transfer: HaFiscalValue = HaFiscalValue.OFF
)

/** What the person chose about charging to a state of charge — and nothing live. */
data class HaTargetIntent(
    val vehicleId: String? = null,
    val targetPercent: Double? = null
) {
    /**
     * The target a driver edit carries. Only the driver is changing, so the stored vehicle and
     * percent are kept; choosing the target driver takes the slider's percent.
     */
    fun forDriver(driver: HaSettingsDriver, sliderPercent: Double): HaTargetIntent =
        if (driver == HaSettingsDriver.TARGET_SOC) copy(targetPercent = sliderPercent) else this

    companion object {
        val EMPTY = HaTargetIntent()
    }
}

data class HaPlanningSettings(
    val revision: Int,
    val areaId: String?,
    val overrides: List<HaAreaOverride>,
    val phases: Int?,
    val amps: Int?,
    val requestedKwh: Double,
    val maxPeriods: Int,
    val departureEnabled: Boolean,
    val departureTime: String,
    /** A departure on one particular day (ISO date in the area's zone), or `null` for every day. */
    val departureDate: LocalDate? = null,
    /**
     * The weekdays a daily departure applies on, 1 (Monday) to 7 (Sunday), ascending and never
     * empty; every day for a record that states none.
     */
    val departureWeekdays: List<Int> = ALL_WEEKDAYS,
    val strategy: HaSettingsStrategy,
    val driver: HaSettingsDriver,
    val target: HaTargetIntent,
    /**
     * Read-only (Home Assistant 1.8, `fiscal_included`): the fiscal components the record's area's
     * published price already contains. They are locked as included in the price, nothing is added for
     * them, and they are never sent back as an edit. Empty from an older Home Assistant.
     */
    val fiscalIncluded: Set<HaAreaOverrideComponent> = emptySet(),
    /**
     * Who hears about which events through the Home Assistant Companion app (`notifications`, Home
     * Assistant 1.9); `null` from a Home Assistant that does not state it, and then nothing about it
     * is shown or sent.
     */
    val notifications: HaNotificationSettings? = null,
    /**
     * "Fill", the kWh slider's last step (`fill_to_limit`, Home Assistant's next release): while it is
     * set the manual need is the battery's room at every calculation, and [requestedKwh] is what is
     * planned while no room is known. `null` from a Home Assistant that does not state it, and then it
     * is never offered nor sent.
     */
    val fillToLimit: Boolean? = null
) {
    /**
     * This record with the read-only facts of [confirmed] carried over: a replacement body never
     * states them, so a record built from one keeps the ones the confirmed record had.
     */
    internal fun withReadOnlyOf(confirmed: HaPlanningSettings): HaPlanningSettings = copy(
        revision = confirmed.revision,
        fiscalIncluded = confirmed.fiscalIncluded,
        notifications = notifications?.copy(available = confirmed.notifications?.available.orEmpty())
    )
}

/** One phone Home Assistant can notify: the Companion app's notify service and the phone's name. */
data class HaNotifyService(val service: String, val name: String)

/**
 * One charger's notification choice (the settings record's `notifications`): the notify services
 * that are told ([targets]), the events they are told about ([events], wire ids, unknown ones kept as
 * they are so a newer Home Assistant's choice survives an edit from here), the Home Assistant path a
 * tap opens ([url]) and the read-only phones that exist now ([available]).
 */
data class HaNotificationSettings(
    val targets: List<String> = emptyList(),
    val events: List<String> = NotificationEvent.DEFAULTS.map { it.wire },
    val url: String? = null,
    val available: List<HaNotifyService> = emptyList()
)

/** The events a notification can be sent for, in the order a client lists them (`notifications/settings.py`). */
enum class NotificationEvent(val wire: String) {
    PLAN_STOPPED("plan_stopped"),
    PLAN_AT_RISK("plan_at_risk"),
    CHARGE_COMPLETE("charge_complete"),
    CHARGE_STARTED("charge_started"),
    PLUGGED_IN("plugged_in"),
    UNPLUGGED("unplugged"),
    PLAN_INSTALLED("plan_installed");

    companion object {
        /** On until a person turns them off: what needs attention, and the end of a charge. */
        val DEFAULTS: List<NotificationEvent> = listOf(PLAN_STOPPED, PLAN_AT_RISK, CHARGE_COMPLETE)

        fun of(wire: Any?): NotificationEvent? = entries.firstOrNull { it.wire == wire }
    }
}

/** Every weekday, Monday (1) to Sunday (7): what a record without `departure_weekdays` means. */
val ALL_WEEKDAYS: List<Int> = (1..7).toList()

/** A refusal from the contract codec, carrying a stable code rather than prose alone. */
internal class HaSettingsFormatException(
    val code: String,
    message: String
) : IllegalArgumentException(message)
