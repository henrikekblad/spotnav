package se.sensnology.spotnav.testing

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.settings.HaFiscalValue
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.ha.settings.HaSettingsDriver
import se.sensnology.spotnav.planning.DepartureIntent
import se.sensnology.spotnav.planning.FiscalResolution
import se.sensnology.spotnav.planning.PlanDriver
import se.sensnology.spotnav.planning.PlanningInputs
import se.sensnology.spotnav.prices.PriceMarket

/** Complete settings API documents, and their parsed equivalents, in one place. */
internal object SettingsFixtures {
    const val AREA = "SE4"
    const val OTHER_AREA = "SE3"

    fun putValue(json: JSONObject, key: String, value: Any?) {
        json.put(key, value ?: JSONObject.NULL)
    }

    fun fiscal(enabled: Boolean = false, value: Any? = null): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("value", value ?: JSONObject.NULL)
    }

    fun override(
        areaId: Any? = AREA,
        vat: Any? = fiscal(),
        tax: Any? = fiscal(),
        transfer: Any? = fiscal()
    ): JSONObject = JSONObject().apply {
        put("area_id", areaId ?: JSONObject.NULL)
        put("vat", vat ?: JSONObject.NULL)
        put("tax", tax ?: JSONObject.NULL)
        put("transfer", transfer ?: JSONObject.NULL)
    }

    fun target(
        vehicleId: Any? = null,
        targetPercent: Any? = null
    ): JSONObject = JSONObject().apply {
        put("vehicle_id", vehicleId ?: JSONObject.NULL)
        put("target_percent", targetPercent ?: JSONObject.NULL)
    }

    /** A complete response record: every body key plus `revision`. */
    fun response(
        revision: Any? = 0,
        areaId: Any? = AREA,
        overrides: Any? = JSONArray(),
        phases: Any? = 3,
        amps: Any? = 16,
        requestedKwh: Any? = 20.5,
        maxPeriods: Any? = 4,
        departureEnabled: Any? = true,
        departureTime: Any? = "07:30",
        strategy: Any? = "cheapest",
        driver: Any? = "manual_kwh",
        target: Any? = target()
    ): JSONObject = JSONObject().apply {
        put("revision", revision ?: JSONObject.NULL)
        put("area_id", areaId ?: JSONObject.NULL)
        put("overrides", overrides ?: JSONObject.NULL)
        put("phases", phases ?: JSONObject.NULL)
        put("amps", amps ?: JSONObject.NULL)
        put("requested_kwh", requestedKwh ?: JSONObject.NULL)
        put("max_periods", maxPeriods ?: JSONObject.NULL)
        put("departure_enabled", departureEnabled ?: JSONObject.NULL)
        put("departure_time", departureTime ?: JSONObject.NULL)
        put("strategy", strategy ?: JSONObject.NULL)
        put("driver", driver ?: JSONObject.NULL)
        put("target", target ?: JSONObject.NULL)
    }

    /** The parsed equivalent of [response] with the same defaults. */
    fun parsed(
        revision: Int = 0,
        areaId: Any? = AREA,
        overrides: Any? = JSONArray(),
        phases: Any? = 3,
        amps: Any? = 16,
        requestedKwh: Any? = 20.5,
        maxPeriods: Any? = 4,
        departureEnabled: Any? = true,
        departureTime: Any? = "07:30",
        strategy: Any? = "cheapest",
        driver: Any? = "manual_kwh",
        target: Any? = target()
    ): HaPlanningSettings = HaSettingsCodec.parseResponse(
        response(
            revision = revision,
            areaId = areaId,
            overrides = overrides,
            phases = phases,
            amps = amps,
            requestedKwh = requestedKwh,
            maxPeriods = maxPeriods,
            departureEnabled = departureEnabled,
            departureTime = departureTime,
            strategy = strategy,
            driver = driver,
            target = target
        )
    )

    /** A settings write envelope, with `null` written as JSON `null`. */
    fun envelope(
        ok: Boolean,
        code: Any? = null,
        settings: Any? = null,
        apiVersion: Any? = 1,
        action: Any? = "settings"
    ): JSONObject = JSONObject().apply {
        put("api_version", apiVersion ?: JSONObject.NULL)
        put("ok", ok)
        put("error", code ?: JSONObject.NULL)
        put("settings", settings ?: JSONObject.NULL)
        put(
            "pause",
            JSONObject().put("choice", JSONObject.NULL).put("admitted_at", JSONObject.NULL)
                .put("expires_at", JSONObject.NULL)
        )
        put("action", action ?: JSONObject.NULL)
    }

    /** Synthetic local-plan fixture only; never a production adaptation of HA ownership. */
    fun localInputs(record: HaPlanningSettings, catalogue: List<PriceMarket>, presentation: HaPresentation): PlanningInputs {
        val market = catalogue.first { it.id == record.areaId }
        val row = record.overrides.firstOrNull { it.areaId == record.areaId }
        return PlanningInputs(
            areaId = market.id, intervalMinutes = presentation.intervalMinutes,
            vat = FiscalResolution.component(row?.vat ?: HaFiscalValue.OFF, market.vatPercent).input,
            tax = FiscalResolution.component(row?.tax ?: HaFiscalValue.OFF, market.suggestedTax).input,
            transfer = FiscalResolution.component(row?.transfer ?: HaFiscalValue.OFF, market.suggestedGridFee).input,
            phases = requireNotNull(record.phases), amps = requireNotNull(record.amps),
            requestedEnergyKwh = record.requestedKwh, consumptionKwhPer10Km = 2.0,
            maxPeriods = record.maxPeriods,
            departure = DepartureIntent(record.departureEnabled, java.time.LocalTime.parse(record.departureTime)),
            driver = if (record.driver == HaSettingsDriver.TARGET_SOC) PlanDriver.TARGET_SOC else PlanDriver.KWH,
            targetSocPercent = if (record.driver == HaSettingsDriver.TARGET_SOC) record.target.targetPercent else null
        )
    }
}
