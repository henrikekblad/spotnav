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
    val target: HaTargetIntent
)

/** Every weekday, Monday (1) to Sunday (7): what a record without `departure_weekdays` means. */
val ALL_WEEKDAYS: List<Int> = (1..7).toList()

/** A refusal from the contract codec, carrying a stable code rather than prose alone. */
internal class HaSettingsFormatException(
    val code: String,
    message: String
) : IllegalArgumentException(message)
