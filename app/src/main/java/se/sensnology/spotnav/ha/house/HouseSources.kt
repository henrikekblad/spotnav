package se.sensnology.spotnav.ha.house

import org.json.JSONObject

/**
 * Where the house's figures come from and what was assumed (`sources`, `house`, the heat's meter and the battery's
 * estimates of `get_house`), as the details sheet's "Källor" lists them (the card's `houseSources`). Read apart from
 * [House]: a house whose answer lacks or garbles it still has its page, only the list is shorter.
 */
internal data class HouseSources(
    /** The weather entity, `null` where none was found; [weatherOk] says whether it has an hourly forecast. */
    val weatherEntity: String?,
    val weatherOk: Boolean,
    /** `sensor`, `weather` or none; [outdoorSensor] is the sensor's entity. */
    val outdoor: String?,
    val outdoorSensor: String?,
    val holidayCalendar: String?,
    val solarForecasts: List<String>,
    /** Whether the temperature is in the load forecast, and why not (`too_few_hours`, else too little explained); `null` without a model. */
    val temperature: Temperature?,
    val countsBattery: Boolean,
    val heat: Heat?,
    val batteryEstimates: List<String>
) {
    data class Temperature(val active: Boolean, val reason: String?)

    /** The heat pump's meter: its entity, where it was found from, its name and the split forecast against the whole. */
    data class Heat(val entityId: String, val source: String?, val name: String?, val splitMae: Double?, val houseMae: Double?, val hours: Int?)

    companion object {
        fun decode(json: JSONObject): HouseSources? = runCatching {
            val sources = json.optJSONObject("sources") ?: return null
            val outdoor = sources.optJSONObject("outdoor")
            val house = json.optJSONObject("house")
            val temperature = house?.optJSONObject("temperature")
            val heat = json.optJSONObject("heat")
            val comparison = heat?.optJSONObject("comparison")
            val estimates = json.optJSONObject("battery")?.optJSONArray("estimates")
            HouseSources(
                weatherEntity = sources.optString("weather_entity").takeIf { sources.has("weather_entity") && !sources.isNull("weather_entity") && it.isNotEmpty() },
                weatherOk = sources.optString("weather") == "ok",
                outdoor = outdoor?.optString("source")?.takeIf { it.isNotEmpty() && outdoor.has("source") && !outdoor.isNull("source") },
                outdoorSensor = outdoor?.takeIf { it.has("sensor") && !it.isNull("sensor") }?.optString("sensor"),
                holidayCalendar = sources.takeIf { it.has("holiday_calendar") && !it.isNull("holiday_calendar") }?.optString("holiday_calendar"),
                solarForecasts = sources.optJSONArray("solar_forecasts")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } } }.orEmpty(),
                temperature = temperature?.let { Temperature(it.optBoolean("active"), it.takeIf { t -> t.has("reason") && !t.isNull("reason") }?.optString("reason")) },
                countsBattery = house?.optBoolean("counts_battery") == true,
                heat = heat?.takeIf { it.optString("status") == "found" && it.has("entity_id") && !it.isNull("entity_id") }?.let {
                    Heat(
                        entityId = it.optString("entity_id"),
                        source = it.takeIf { h -> h.has("source") && !h.isNull("source") }?.optString("source"),
                        name = it.takeIf { h -> h.has("name") && !h.isNull("name") }?.optString("name"),
                        splitMae = comparison?.optDouble("split_mae")?.takeIf { v -> !v.isNaN() },
                        houseMae = comparison?.optDouble("house_mae")?.takeIf { v -> !v.isNaN() },
                        hours = comparison?.takeIf { c -> c.has("hours") }?.optInt("hours")
                    )
                },
                batteryEstimates = estimates?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } } }.orEmpty()
            )
        }.getOrNull()
    }
}
