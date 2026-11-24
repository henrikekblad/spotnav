package se.sensnology.spotnav.ui.settings

import se.sensnology.spotnav.ha.heat.HeatLoads
import se.sensnology.spotnav.ha.site.HeatLoadSettings
import se.sensnology.spotnav.ha.site.HeatSettings
import se.sensnology.spotnav.ui.common.CardWords
import se.sensnology.spotnav.ui.site.HeatLoadsText
import se.sensnology.spotnav.ui.site.SiteText
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The Pool, Spa and Bastu sections of the settings as the card's `loadSettingsSections` (`heat-view.ts`) lists them:
 * each only where the load is set up, its rows (entities, filtering, powers, "Mellan baden", the control opt-ins, a
 * relay sauna's safety measure) and the line under it on what SpotNav would do. All an administrator's in Home
 * Assistant, so read only here. Pure.
 */
internal class LoadSettingsRows(private val words: CardWords, private val locale: Locale) {
    data class Row(val label: String, val value: String)

    /** One section: [kind] `pool`, `spa` or `sauna`, its title, rows and the line under them (or `null`). */
    data class Section(val kind: String, val title: String, val rows: List<Row>, val help: String?)

    private fun fixed(value: Double, decimals: Int) = String.format(locale, "%.${decimals}f", value)

    private fun kw(value: Double) = "${fixed(value, 1)} kW"

    private fun onOff(on: Boolean) = words.say(if (on) "settings.value.on" else "settings.value.off")

    /** A load's power: the entered kW, else the learned one, else the typical one it counts on until learned. */
    fun powerWords(entered: Double?, kw: Double?, source: String?): String = when {
        entered != null -> words.say("heat.settings.kwEntered", "kw" to kw(entered))
        kw == null -> words.say("heat.settings.kwNone")
        source == "learned" -> words.say("heat.settings.kwLearned", "kw" to kw(kw))
        else -> words.say("heat.settings.kwTypical", "kw" to kw(kw))
    }

    fun sections(settings: HeatSettings): List<Section> {
        val name = { id: String? -> if (id == null) words.say("heat.settings.none") else settings.nameOf(id) ?: words.say("settings.status.chosen") }
        val said = HeatLoadsText(SiteText(words, locale), settings.now ?: Instant.now(), settings.zone ?: ZoneId.systemDefault())
        return listOfNotNull(
            settings.pool?.let { pool(it, settings.poolStatus, name, said) },
            settings.spa?.let { spa(it, settings.spaStatus, name, said) },
            settings.sauna?.let { sauna(it, settings.saunaStatus, name, said) }
        )
    }

    private fun pool(pool: HeatLoadSettings.Pool, status: HeatLoads.Pool?, name: (String?) -> String, said: HeatLoadsText): Section {
        val rows = mutableListOf(
            Row(words.say("heat.settings.poolHeatPump"), name(pool.heatPump)),
            Row(words.say("heat.settings.poolCirculation"), name(pool.circulation)),
            Row(words.say("heat.settings.poolSensor"), pool.waterSensor?.let { name(it) } ?: words.say("heat.settings.poolSensorPump")),
            Row(
                words.say("heat.settings.poolFilterHours"),
                pool.filterHours?.let { "${fixed(it, if (it == Math.floor(it)) 0 else 1)} h" } ?: words.say("heat.settings.poolFilterAuto")
            )
        )
        if (pool.heatPump != null) rows += Row(words.say("heat.settings.poolHeatPumpKw"), powerWords(pool.heatPumpKw, status?.heatPump?.kw, status?.heatPump?.kwSource))
        if (pool.circulation != null) {
            rows += Row(words.say("heat.settings.poolCirculationKw"), powerWords(pool.circulationKw, status?.circulation?.kw, status?.circulation?.kwSource))
            rows += Row(words.say("heat.settings.poolCirculationControl"), onOff(pool.controlCirculation))
        }
        // The interlock: the heat pump is only ever SpotNav's with a circulation pump chosen.
        if (pool.heatPump != null && pool.circulation != null) rows += Row(words.say("heat.settings.poolHeatPumpControl"), onOff(pool.controlHeatPump))
        val help = status?.let { said.poolWouldShown(it, withStatus = false) ?: said.poolStatus(it) }
        return Section("pool", words.say("heat.settings.section.pool"), rows, help)
    }

    private fun spa(spa: HeatLoadSettings.Spa, status: HeatLoads.Deadline?, name: (String?) -> String, said: HeatLoadsText): Section {
        val rows = mutableListOf(
            Row(words.say("heat.settings.spa"), name(spa.entityId)),
            Row(words.say("heat.spa.idleLabel"), "${fixed(spa.idleC, 1)} °C"),
            Row(words.say("heat.settings.spaKw"), powerWords(spa.heaterKw, status?.kw, status?.kwSource))
        )
        if (spa.family in HeatLoadSettings.SPA_CONTROLLABLE) rows += Row(words.say("heat.settings.spaControl"), onOff(spa.control))
        return Section("spa", words.say("heat.settings.section.spa"), rows, status?.let { said.deadlineWouldShown(it, withStatus = false) })
    }

    private fun sauna(sauna: HeatLoadSettings.Sauna, status: HeatLoads.Deadline?, name: (String?) -> String, said: HeatLoadsText): Section {
        val rows = mutableListOf(
            Row(words.say("heat.settings.sauna"), name(sauna.entityId)),
            Row(words.say("heat.settings.saunaKw"), powerWords(sauna.heaterKw, status?.kw, status?.kwSource))
        )
        if (sauna.family == "relay") {
            rows += Row(words.say("heat.settings.saunaSafety"), words.say(if (sauna.safetyDeclared) "heat.settings.saunaSafetyYes" else "heat.settings.saunaSafetyNo"))
        }
        if (HeatLoadSettings.saunaControllable(sauna)) rows += Row(words.say("heat.settings.saunaControl"), onOff(sauna.control))
        return Section("sauna", words.say("heat.settings.section.sauna"), rows, status?.let { said.deadlineWouldShown(it, withStatus = false) })
    }
}
