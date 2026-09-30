package se.sensnology.spotnav.planning

import se.sensnology.spotnav.chargers.ChargerPhases
import se.sensnology.spotnav.widget.WidgetSettings
import java.time.LocalTime

/** The one place a stored [WidgetSettings] becomes calculation inputs. */
internal object LocalPlanningInputs {
    /** The floor the plan's own arithmetic has always applied to a stored consumption figure. */
    const val MIN_CONSUMPTION_KWH_PER_10KM = 0.1

    /** The energy control's own minimum: no screen in this app can ask for less. */
    const val MIN_ENERGY_KWH = 1.0

    /** The calculation inputs, or `null` when this record cannot describe one yet. */
    fun ofOrNull(settings: WidgetSettings): PlanningInputs? =
        if (settings.area.isBlank()) null else of(settings)

    fun of(settings: WidgetSettings): PlanningInputs = PlanningInputs(
        areaId = settings.area,
        // Anything that is not the quarter-hour presentation is the hourly one, which is exactly
        // how the aggregation has always read this field.
        intervalMinutes = if (settings.intervalMinutes == 15) 15 else 60,
        vat = local(settings.vat, settings.effectiveVatPercent),
        tax = local(settings.tax, settings.taxMinorUnit),
        transfer = local(settings.transfer, settings.gridFeeMinorUnit),
        phases = ChargerPhases.normalized(settings.chargingPhases),
        amps = settings.chargingAmps.coerceAtLeast(1),
        requestedEnergyKwh = settings.chargingKwh.toDouble().coerceAtLeast(MIN_ENERGY_KWH),
        consumptionKwhPer10Km = settings.consumptionKwhPerMil
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(MIN_CONSUMPTION_KWH_PER_10KM)
            ?: MIN_CONSUMPTION_KWH_PER_10KM,
        maxPeriods = settings.maxChargingPeriods.coerceIn(1, 8),
        departure = DepartureIntent(
            enabled = settings.useDepartureTime,
            time = LocalTime.of(
                settings.departureHour.coerceIn(0, 23),
                settings.departureMinute.coerceIn(0, 59)
            )
        ),
        driver = settings.driver,
        // The widget's own record carries no target: the target lives on the profile, and the
        // screen has already turned it into `chargingKwh` before a plan here is ever computed.
        targetSocPercent = null
    )

    /** One component, from the local record's own single figure. */
    private fun local(enabled: Boolean, figure: Double): FiscalInput {
        val value = fiscalFigure(figure)
        return FiscalInput(enabled = enabled, overrideValue = value, effectiveValue = value)
    }
}
