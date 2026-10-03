package se.sensnology.spotnav.ha.client

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.ha.dashboard.Dashboard
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.ha.settings.SettingsUpdate
import se.sensnology.spotnav.vehicles.ChargeLimit
import se.sensnology.spotnav.vehicles.VehicleRefresh
import se.sensnology.spotnav.vehicles.VehicleStatus
import se.sensnology.spotnav.ha.sessions.SessionsCsv
import se.sensnology.spotnav.ha.sessions.SessionsMonth
import java.net.HttpURLConnection
import java.time.YearMonth
import java.net.URL

data class HomeAssistantCommand(
    val action: String,
    val amps: Int? = null,
    val phases: Int? = null,
    /** `refresh_vehicle`'s subject and `set_charge_limit`'s: a [VehicleStatus.id], the HA device id. */
    val vehicleId: String? = null,
    /**
     * `set_charge_limit`'s value: the charge limit to write to the car, in whole percent (see
     * [ChargeLimit]).
     */
    val percent: Double? = null,
    /**
     * `settings`'s revision: the one this edit was built on, which the server's compare-and-set
     * checks. Written only for that action.
     */
    val expectedRevision: Int? = null,
    /**
     * `settings`'s complete replacement body, written through the contract's own encoder, so
     * nothing here can spell a field differently from the reader.
     */
    val settingsReplacement: HaPlanningSettings? = null,
    /** The typed pause choice a `stop` means, or `null` for a plain immediate stop. */
    val choice: String? = null
)

/** A webhook answer that was not 2xx, carrying what the caller needs to read it. */
/**
 * One dashboard read: the decoded [dashboard], and the [body] it was decoded from (the answer's
 * JSON text, which decodes to an equal dashboard again -- the widget stores it, and draws from it
 * later).
 */
internal class FetchedDashboard(val dashboard: Dashboard, val body: String)

internal class WebhookHttpStatusException(
    val status: Int,
    val bodyText: String
) : IllegalStateException("HTTP $status")

/**
 * The settings fields this app reads that Home Assistant withholds from an app that does not ask
 * (`docs/api.md`, "Withheld settings field"). Every request the app sends names them.
 */
internal object WebhookReads {
    val FIELDS: List<String> = listOf("departure_date", "departure_weekdays")

    fun put(body: JSONObject): JSONObject = body.put("reads", JSONArray(FIELDS))
}

object HomeAssistantClient {
    internal fun payload(command: HomeAssistantCommand): String = JSONObject().apply {
        put("version", 1)
        WebhookReads.put(this)
        put("action", command.action)
        command.amps?.let { put("amps", it) }
        command.phases?.let { put("phases", it) }
        // `percent` is `set_charge_limit`'s own value, and only that action's.
        command.percent
            ?.takeIf { command.action == "set_charge_limit" }
            ?.let { put("percent", it) }
        // `choice` is a *kind of stop* and nothing else.
        command.choice
            ?.takeIf { command.action == "stop" }
            ?.let { put("choice", it) }
        // `vehicle_id` is the whole subject of the two actions that name a vehicle.
        command.vehicleId
            ?.takeIf { command.action == "refresh_vehicle" || command.action == "set_charge_limit" }
            ?.let { put("vehicle_id", it) }
        // `settings` is the one action whose body carries a body: the complete replacement, written
        // by the contract's encoder, and the revision it was built on.
        if (command.action == "settings") {
            command.expectedRevision?.let { put("expected_revision", it) }
            command.settingsReplacement?.let { replacement ->
                put("settings", HaSettingsCodec.encodeBody(replacement))
            }
        }
        // The dashboard is asked for by its own version.
        if (command.action == "dashboard") put("api_version", Dashboard.API_VERSION)
    }.toString()

    fun send(settings: HomeAssistantSettings, command: HomeAssistantCommand) {
        request(settings, command)
    }

    /** The charger's dashboard (webhook `dashboard`, `api_version` 1): */
    internal fun dashboard(
        settings: HomeAssistantSettings,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): Dashboard = dashboardRead(settings, connectTimeoutMs, readTimeoutMs).dashboard

    /**
     * The same read, with the answer's own text kept beside what was decoded from it: a widget that
     * draws from the last dashboard has to store *that answer*, and decoding it again later is the
     * only way to hold it whole (see [FetchedDashboard]).
     */
    internal fun dashboardRead(
        settings: HomeAssistantSettings,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): FetchedDashboard {
        val body = request(settings, HomeAssistantCommand("dashboard"), connectTimeoutMs, readTimeoutMs)
        return FetchedDashboard(Dashboard.parse(body), body.toString())
    }

    /**
     * Replace the charger's canonical settings through its own scoped webhook, and say what came
     * back.
     */
    fun updateSettings(
        settings: HomeAssistantSettings,
        expectedRevision: Int,
        replacement: HaPlanningSettings
    ): SettingsUpdate.Outcome {
        require(expectedRevision >= 0) { "expected_revision must not be negative" }
        return try {
            val response = request(
                settings,
                HomeAssistantCommand(
                    action = "settings",
                    expectedRevision = expectedRevision,
                    settingsReplacement = replacement
                )
            )
            SettingsUpdate.answer(200, response.toString())
        } catch (refused: WebhookHttpStatusException) {
            SettingsUpdate.answer(refused.status, refused.bodyText)
        } catch (failure: Exception) {
            // No HTTP answer at all: unreachable, refused, timed out, malformed.
            SettingsUpdate.answer(null, null)
        }
    }

    /** Ask the integration to re-read one vehicle's own entities, and say what came back. */
    fun refreshVehicle(settings: HomeAssistantSettings, vehicleId: String): VehicleRefresh.Answer =
        try {
            val response = request(settings, HomeAssistantCommand("refresh_vehicle", vehicleId = vehicleId))
            VehicleRefresh.answer(200, response.toString())
        } catch (refused: WebhookHttpStatusException) {
            VehicleRefresh.answer(refused.status, refused.bodyText)
        } catch (failure: Exception) {
            // No HTTP answer at all: unreachable, refused, timed out, malformed.
            VehicleRefresh.answer(null, null)
        }

    /** Write one vehicle's charge limit through the integration, and say what came back. */
    fun setChargeLimit(
        settings: HomeAssistantSettings,
        vehicleId: String,
        percent: Int
    ): ChargeLimit.Answer =
        try {
            val response = request(
                settings,
                HomeAssistantCommand(
                    "set_charge_limit",
                    vehicleId = vehicleId,
                    percent = percent.toDouble()
                )
            )
            ChargeLimit.answer(200, response.toString())
        } catch (refused: WebhookHttpStatusException) {
            ChargeLimit.answer(refused.status, refused.bodyText)
        } catch (failure: Exception) {
            // No HTTP answer at all: unreachable, refused, timed out, malformed.
            ChargeLimit.answer(null, null)
        }

    private fun request(
        settings: HomeAssistantSettings,
        command: HomeAssistantCommand,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): JSONObject {
        require(
            command.action in setOf(
                "dashboard", "start", "stop", "resume", "refresh_vehicle", "settings", "set_charge_limit"
            )
        ) { "Unsupported action" }
        return post(settings, payload(command), connectTimeoutMs, readTimeoutMs)
    }

    /**
     * One bounded write (`update_vehicle`, `choose_vehicle_soc`, `update_site_settings`): the pure
     * [body] posted to the charger's own webhook, and the (status, body) of whatever answered.
     */
    private fun write(settings: HomeAssistantSettings, body: JSONObject): Pair<Int?, String?> = try {
        Pair(200, post(settings, body.toString()).toString())
    } catch (refused: WebhookHttpStatusException) {
        Pair(refused.status, refused.bodyText)
    } catch (failure: Exception) {
        Pair(null, null)
    }

    /** Change one vehicle's capacity and/or consumption in one request (webhook `update_vehicle`). */
    internal fun updateVehicle(
        settings: HomeAssistantSettings,
        vehicleId: String,
        changes: List<VehicleUpdate.FieldChange>
    ): VehicleUpdate.Outcome {
        val (status, body) = write(settings, VehicleUpdate.payload(vehicleId, changes))
        return VehicleUpdate.answer(status, body)
    }

    /**
     * Change the charger's site: solar priority or the forecast sources (webhook
     * `update_site_settings`).
     */
    internal fun updateSiteSettings(settings: HomeAssistantSettings, request: SiteUpdate.Request): SiteUpdate.Outcome {
        val (status, body) = write(settings, request.payload())
        return SiteUpdate.answer(status, body)
    }

    /** One month of charge history (webhook `sessions`); `null` asks for the current month. Never throws. */
    internal fun sessionsMonth(settings: HomeAssistantSettings, month: YearMonth?): SessionsOutcome<SessionsMonth> {
        val (status, body) = write(settings, SessionsRead.payload(month))
        return SessionsRead.month(status, body)
    }

    /** One month of charge history as a CSV text and the name to save it under. Never throws. */
    internal fun sessionsCsv(settings: HomeAssistantSettings, month: YearMonth): SessionsOutcome<SessionsCsv> {
        val (status, body) = write(settings, SessionsRead.payload(month, csv = true))
        return SessionsRead.csv(status, body)
    }

    private fun post(
        settings: HomeAssistantSettings,
        body: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): JSONObject {
        require(settings.configured) { "Home Assistant is not configured" }
        require(HomeAssistantSettings.isAllowedBaseUrl(settings.baseUrl)) { "HTTPS or a local HTTP address is required" }

        val connection = URL(settings.webhookUrl()).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw WebhookHttpStatusException(status, responseBody)
            return JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
