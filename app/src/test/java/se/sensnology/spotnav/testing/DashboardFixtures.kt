package se.sensnology.spotnav.testing

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.dashboard.Dashboard

/**
 * Dashboards for tests that need one, built from the integration's own vendored fixtures
 * (`ha-fixtures/dashboard/`) so they can only ever say what Home Assistant says.
 */
internal object DashboardFixtures {
    /** The fixture used when a test only needs "some dashboard". */
    const val BASE = "cheapest_no_site.json"

    fun json(name: String = BASE): JSONObject = HaFixtures.json("dashboard/$name")

    fun parse(json: JSONObject): Dashboard = Dashboard.parse(json)

    fun dashboard(name: String = BASE, edit: JSONObject.() -> Unit = {}): Dashboard =
        parse(json(name).apply(edit))

    /**
     * A dashboard whose charger is charging or not, and whose installed schedule is [periods] at
     * [amps] (`null` for none installed).
     */
    fun withSchedule(
        scheduleActive: Boolean,
        amps: Int? = null,
        periods: List<Pair<String, String>> = emptyList(),
        charging: Boolean = false
    ): Dashboard = dashboard {
        getJSONObject("live").put("charging", charging).put("schedule_active", scheduleActive)
        getJSONObject("plan").put(
            "installed",
            if (!scheduleActive && periods.isEmpty() && amps == null) JSONObject.NULL else JSONObject().apply {
                put("active_period_index", JSONObject.NULL)
                put("amps", amps ?: JSONObject.NULL)
                put("identity", "x")
                put("phases", 1)
                put("power_kw", JSONObject.NULL)
                put("periods", JSONArray().apply {
                    periods.forEach { (start, end) -> put(JSONObject().put("start", start).put("end", end)) }
                })
            }
        )
    }
}
