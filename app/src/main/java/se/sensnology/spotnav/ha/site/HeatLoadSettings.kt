package se.sensnology.spotnav.ha.site

import se.sensnology.spotnav.ha.house.StrictJson

/**
 * The pool's, the spa's and the sauna's stored settings (`get_heat`'s `config.pool`, `config.spa`, `config.sauna`,
 * `heat/config.py`), as the card's Pool, Spa and Bastu settings sections show them. An administrator's in Home
 * Assistant (`spotnav/update_heat`, not over the webhook), so read only in the app. `null` without the load set up.
 */
internal object HeatLoadSettings {
    data class Pool(
        val heatPump: String?,
        val circulation: String?,
        /** `null`: the heat pump's own reading of the water. */
        val waterSensor: String?,
        /** `null`: by the water temperature. */
        val filterHours: Double?,
        val heatPumpKw: Double?,
        val circulationKw: Double?,
        val controlHeatPump: Boolean,
        val controlCirculation: Boolean
    )

    data class Spa(val entityId: String, val family: String, val idleC: Double, val heaterKw: Double?, val control: Boolean)

    data class Sauna(val entityId: String, val family: String, val heaterKw: Double?, val safetyDeclared: Boolean, val control: Boolean)

    fun pool(raw: Any?): Pool? = with(StrictJson) {
        val pool = objOrNull(raw, "config.pool") ?: return null
        Pool(
            heatPump = objOrNull(pool.opt("heat_pump"), "pool.heat_pump")?.let { textOrNull(it.opt("entity_id"), "pool.heat_pump.entity_id") },
            circulation = textOrNull(pool.opt("circulation_id"), "pool.circulation_id"),
            waterSensor = textOrNull(pool.opt("water_sensor"), "pool.water_sensor"),
            filterHours = numberOrNull(pool.opt("filter_hours"), "pool.filter_hours"),
            heatPumpKw = numberOrNull(pool.opt("heat_pump_kw"), "pool.heat_pump_kw"),
            circulationKw = numberOrNull(pool.opt("circulation_kw"), "pool.circulation_kw"),
            controlHeatPump = flag(pool.opt("control_heat_pump"), "pool.control_heat_pump"),
            controlCirculation = flag(pool.opt("control_circulation"), "pool.control_circulation")
        )
    }

    fun spa(raw: Any?): Spa? = with(StrictJson) {
        val spa = objOrNull(raw, "config.spa") ?: return null
        Spa(
            entityId = text(spa.opt("entity_id"), "spa.entity_id"),
            family = text(spa.opt("family"), "spa.family"),
            idleC = number(spa.opt("idle_c"), "spa.idle_c"),
            heaterKw = numberOrNull(spa.opt("heater_kw"), "spa.heater_kw"),
            control = flag(spa.opt("control"), "spa.control")
        )
    }

    fun sauna(raw: Any?): Sauna? = with(StrictJson) {
        val sauna = objOrNull(raw, "config.sauna") ?: return null
        Sauna(
            entityId = text(sauna.opt("entity_id"), "sauna.entity_id"),
            family = text(sauna.opt("family"), "sauna.family"),
            heaterKw = numberOrNull(sauna.opt("heater_kw"), "sauna.heater_kw"),
            safetyDeclared = flag(sauna.opt("safety_declared"), "sauna.safety_declared"),
            control = flag(sauna.opt("control"), "sauna.control")
        )
    }

    /** The spa families SpotNav can control (`spa/config.py` `CONTROLLABLE`, the card's `SPA_CONTROLLABLE`). */
    val SPA_CONTROLLABLE = setOf("balboa", "smarttub", "mspa", "gecko", "bestway", "intex_spa", "tuya_intex")

    /** The sauna integrations SpotNav can control (`sauna/config.py` `INTEGRATIONS`); a relay only with its safety declared. */
    val SAUNA_INTEGRATIONS = setOf("huum", "saunum", "harvia_sauna", "tylo_sauna", "sentio")

    fun saunaControllable(sauna: Sauna): Boolean = sauna.family in SAUNA_INTEGRATIONS || (sauna.family == "relay" && sauna.safetyDeclared)

}
