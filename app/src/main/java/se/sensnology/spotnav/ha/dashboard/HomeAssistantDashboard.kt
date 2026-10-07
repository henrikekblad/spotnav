package se.sensnology.spotnav.ha.dashboard

import org.json.JSONArray
import org.json.JSONObject
import se.sensnology.spotnav.app.optIntOrNull
import se.sensnology.spotnav.app.optStringOrNull
import se.sensnology.spotnav.app.strictBoolean
import se.sensnology.spotnav.app.strictText
import se.sensnology.spotnav.chargers.ChargerCapabilities
import se.sensnology.spotnav.ha.settings.HaAreaOverrideComponent
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.ha.settings.HaSettingsCodec
import se.sensnology.spotnav.ha.settings.HaSettingsFormatException
import se.sensnology.spotnav.ha.settings.HaSettingsStrategy
import se.sensnology.spotnav.ha.sessions.SessionsCodec
import se.sensnology.spotnav.ha.sessions.SessionsSummary
import se.sensnology.spotnav.prices.AreaSource
import se.sensnology.spotnav.prices.IncludedPart
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.vehicles.VehicleStatus
import java.time.OffsetDateTime

/**
 * Home Assistant's dashboard answer (webhook `dashboard`, `api_version` 1): the app's one read of a
 * paired charger.
 */
internal class DashboardDecodeException(message: String) : IllegalArgumentException(message)

/**
 * How loudly a status block speaks. `BLOCKING` is the app's existing error styling; the rest is
 * neutral.
 */
internal enum class StatusTone { NORMAL, NOTICE, BLOCKING }

internal data class StatusLine(val code: String, val params: Map<String, Any?>)

internal data class DashboardStatus(val lines: List<StatusLine>, val tone: StatusTone)

/**
 * The charger's live facts this app reads: whether the charge is on, and whether a schedule is
 * held.
 */
internal data class DashboardLive(
    val charging: Boolean,
    val scheduleActive: Boolean,
    /**
     * The charger's own measured current per phase (`measured_current_a`), read leniently: `null` when
     * Home Assistant states none, or a value that is not a finite, non-negative number.
     */
    val measuredCurrentA: Double? = null
)

/**
 * One charger the instance reports: the config entry id that identifies it, and the entry's current
 * title.
 */
data class StatusCharger(val id: String, val name: String)

internal data class DashboardCurrentRange(val minA: Int, val maxA: Int, val source: String)

/** The market's own clock and money, which the status words need to write an instant or an amount. */
internal data class DashboardMarket(
    val areaId: String?,
    val timezone: String?,
    val currency: String?,
    val majorUnit: String?,
    val minorUnit: String?,
    /** The area's countries; empty from an answer that does not state them. */
    val countries: List<String> = emptyList(),
    /**
     * Home Assistant 1.8: the calendar of the relay's day files (`market_timezone`), equal to [timezone]
     * except for Great Britain and Portugal; `null` from an older Home Assistant.
     */
    val marketTimezone: String? = null,
    /** Home Assistant 1.8: what the published price already contains (`included`, relay names). */
    val included: Set<IncludedPart> = emptySet(),
    /** Home Assistant 1.8: where the prices come from (`source`), or `null`. */
    val source: AreaSource? = null,
    /** The fiscal components the answer's `fiscal` block states with the policy `included`. */
    val fiscalIncluded: Set<HaAreaOverrideComponent> = emptySet()
) {
    /** Whether the market is in Great Britain: distances are then written in miles. */
    val inGreatBritain: Boolean get() = countries.any { it.equals(PriceMarket.GREAT_BRITAIN, ignoreCase = true) }
}

internal data class DashboardPeriod(val start: OffsetDateTime, val end: OffsetDateTime)

internal data class DashboardProposal(
    val amps: Int?,
    val phases: Int?,
    val powerKw: Double?,
    val plannedKwh: Double?,
    val requestedKwh: Double?,
    val distanceMil: Double?,
    val costCurrency: String?,
    val costValue: Double?,
    /** Whether any slot of the plan charges without a published price. */
    val unpriced: Boolean,
    val unpricedSlots: Int?,
    val pricedSlots: Int?,
    val periods: List<DashboardPeriod>
)

internal data class DashboardInstalled(
    val amps: Int?,
    val phases: Int?,
    val origin: String?,
    val periods: List<DashboardPeriod>,
    /** The schedule's power as Home Assistant calculated it (`power_kw`), read leniently; `null` when absent. */
    val powerKw: Double? = null
)

internal data class DashboardPlan(
    val proposal: DashboardProposal?,
    val installed: DashboardInstalled?,
    /**
     * A manual need's count (Home Assistant 1.9): the energy delivered toward it and what remains,
     * `null` where nothing tracks it (a target, nothing delivered known, an older Home Assistant).
     */
    val deliveredKwh: Double? = null,
    val remainingKwh: Double? = null
)

internal data class DashboardVehicleRef(val id: String, val name: String)

internal data class DashboardSoc(
    val value: Double?,
    val ageS: Double?,
    val capacityKwh: Double?,
    val efficiency: Double?,
    val estimated: Boolean,
    val missing: List<String>,
    val needKwh: Double?,
    val source: String?,
    val targetPercent: Double?,
    val vehicleId: String?,
    val vehicleName: String?,
    val vehicleMaxPercent: Double?,
    /** The vehicles the charge state may be read from, for the choice when it is ambiguous. */
    val vehicles: List<DashboardVehicleRef>,
    /**
     * The wall energy the battery still has room for, to the car's own limit (else 100 %): what Home
     * Assistant caps a manual amount at, and the kWh slider's top. `null` without a level or a battery
     * size, and from a Home Assistant that does not state it.
     */
    val roomKwh: Double? = null
)

internal data class DashboardVehicle(
    val id: String,
    val name: String,
    val capacityKwh: Double?,
    val capacitySource: String?,
    val consumptionKwhPer10km: Double?,
    val maxPercent: Double?,
    val socEntityId: String?,
    /** The vehicle's current charge level, or `null` when Home Assistant cannot read it. */
    val socPercent: Double? = null,
    /**
     * The most phases the car's own onboard charger takes, 1 or 3; `null` from a Home Assistant that
     * does not state it (which plans as three).
     */
    val onboardPhases: Int? = null,
    /**
     * The car's own target percent, the same at every charger (`target_percent`, 0-100); `null` when
     * never set, or not stated ([targetStated]).
     */
    val targetPercent: Double? = null,
    /** Whether the row states `target_percent` at all: only then is a target shown or written for the car. */
    val targetStated: Boolean = false,
    /** The car's identification sources (`identification`); `null` from a Home Assistant without them. */
    val identification: VehicleIdentificationSources? = null
)

/**
 * The `charging_phases` block: how many phases a charge uses (the smaller of the charger's wiring
 * and the planned vehicle's onboard charger), and which of the two sets it.
 */
internal data class DashboardChargingPhases(
    val phases: Int,
    val charger: Int?,
    val vehicle: Int?,
    /** `"vehicle"` when the car, not the wiring, sets [phases]; otherwise `null`. */
    val limitedBy: String?
) {
    val limitedByVehicle: Boolean get() = limitedBy == "vehicle"
}

/** One solar forecast source the site can use: the config entry id the write names, and its title. */
internal data class DashboardForecastChoice(val id: String, val title: String)

/**
 * One price interval as the dashboard states it, already all-in: [effectivePrice] is in the
 * market's minor unit per kWh (fiscal components included), `null` while the interval's price is
 * unknown.
 */
internal data class DashboardPriceInterval(
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val day: String,
    val durationMinutes: Int,
    val effectivePrice: Double?,
    val proposalPlanned: Boolean,
    val installedPlanned: Boolean
)

/** The `prices` block: the intervals the card draws, with the two market days they belong to. */
internal data class DashboardPrices(
    val state: String?,
    val today: String?,
    val tomorrow: String?,
    val resolutionMinutes: Int?,
    val unpriced: Boolean,
    val intervals: List<DashboardPriceInterval>
)

/**
 * The `charger_priority` block (Home Assistant 1.9): this charger's place in its site's order, one of
 * [VALUES]. [choices] keeps only values this app knows, in the order sent; [writable] is false for a
 * reader. `null` on the dashboard for a charger on no site and from a Home Assistant without it.
 */
internal data class ChargerPriority(val value: String, val choices: List<String>, val writable: Boolean) {
    companion object {
        const val FIRST = "first"
        const val NORMAL = "normal"
        const val LAST = "last"
        val VALUES = listOf(FIRST, NORMAL, LAST)

        /** Read leniently: anything that does not state a known value is simply not there. */
        fun parse(raw: Any?): ChargerPriority? {
            val block = raw as? JSONObject ?: return null
            val value = (block.opt("value") as? String)?.takeIf { it in VALUES } ?: return null
            val sent = block.optJSONArray("choices")
            val choices = (0 until (sent?.length() ?: 0)).mapNotNull { (sent?.opt(it) as? String)?.takeIf { c -> c in VALUES } }
                .distinct().ifEmpty { VALUES }
            return ChargerPriority(value, choices, block.opt("writable") == true)
        }
    }
}

internal data class DashboardSite(
    val name: String?,
    val writable: Boolean,
    val chargerCount: Int?,
    val solarPriority: String?,
    val solarForecastSelected: List<String>,
    val solarForecastChoices: List<DashboardForecastChoice>,
    val activeControlEnabled: Boolean,
    val activeControlAvailable: Boolean,
    val activeControlWritable: Boolean,
    val activeControlReason: String?
)

internal data class Dashboard(
    val generatedAt: String?,
    /** The charger's own config entry id and title, as the dashboard names them. */
    val chargerId: String,
    val chargerName: String?,
    val capabilities: ChargerCapabilities,
    val status: DashboardStatus,
    val currentRange: DashboardCurrentRange,
    val market: DashboardMarket,
    val plan: DashboardPlan,
    val prices: DashboardPrices,
    val live: DashboardLive,
    /**
     * The canonical settings record, revision included, or `null` when Home Assistant has none to
     * state (its settings store is not available). Never a default: this app shows what it could
     * not read as exactly that.
     */
    val settings: HaPlanningSettings?,
    /**
     * `null` when the block cannot be read completely: no control is offered from it, and it is
     * never a literal this app may fill in.
     */
    val control: AutoControl?,
    /**
     * Whether the vehicle is taking the charge that was started (see [ChargeProgressContract]);
     * `null` when unreadable.
     */
    val chargeProgress: ChargeProgress?,
    /**
     * Cheapest-only when the list is absent, not a list, or names nothing this app recognises --
     * this app never guesses that solar or hybrid is possible.
     */
    val strategyOptions: Set<HaSettingsStrategy>,
    /**
     * The strategies the answer's `strategy.available` rows hold back with the reason
     * `needs_total_grid_power` (a direct site without the meter's total grid power); empty when the
     * block is absent or unreadable.
     */
    val strategiesNeedingTotalGridPower: Set<HaSettingsStrategy> = emptySet(),
    val detectedPhases: Int?,
    val phaseDetectionSource: String?,
    val phaseDetectionConfidence: String?,
    /**
     * Every charger config entry on the instance; system-wide, so every charger's answer carries
     * the same list.
     */
    val chargers: List<StatusCharger>,
    val soc: DashboardSoc?,
    val vehicles: List<DashboardVehicle>,
    val targetVehicleId: String?,
    val site: DashboardSite?,
    /** The setup in words (friendly names, never entity ids); `null` from a Home Assistant that does not send it. */
    val summary: DashboardSummary? = null,
    /** How many phases a charge uses and why; `null` from a Home Assistant that does not state it. */
    val chargingPhases: DashboardChargingPhases? = null,
    /**
     * This and last month's charge totals; `null` from a Home Assistant that records no charge
     * history. The History view is offered only when it is there.
     */
    val sessionsSummary: SessionsSummary? = null,
    /** The charger's connection state (Home Assistant 1.6); `null` when absent or `unknown`. */
    val connection: ConnectionState? = null,
    /** This charger's priority on its site (Home Assistant 1.9); `null` when absent, or on no site. */
    val chargerPriority: ChargerPriority? = null,
    /**
     * Why the last calculation produced what it did (`planning.reason`, e.g. `deadline_too_short`);
     * read leniently, `null` when absent or not text.
     */
    val planningReason: String? = null,
    /**
     * When Home Assistant's start-up ends at the latest (`starting_up.until` while `active`), read
     * leniently: `null` when it is not starting up or the time cannot be read.
     */
    val startingUpUntil: java.time.Instant? = null,
    /**
     * Home Assistant's own charge bar (`progress`, see [DashboardProgress]): `null` when it does not say
     * (an older Home Assistant, or a block this app cannot read), [DashboardProgress.None] for no bar.
     */
    val progress: DashboardProgress? = null,
    /**
     * Which car is plugged in (`identification`, see [DashboardIdentification]): `null` when nothing is
     * being identified, from an older Home Assistant, or for a block this app cannot read.
     */
    val identification: DashboardIdentification? = null,
    /**
     * Every detected car, whether or not it can charge here (`vehicle_choices`, by name): the cars the
     * charger's `vehicle_ids` are ticked from. `null` from a Home Assistant that does not state it.
     */
    val vehicleChoices: List<DashboardVehicleRef>? = null,
    /**
     * The camera for identification (`camera_identification`, see [DashboardCamera]): `null` where Home
     * Assistant offers no camera, from an older one, or for a block this app cannot read.
     */
    val cameraIdentification: DashboardCamera? = null
) {
    /** Whether the charge switch is on: the dashboard's `live.charging`. */
    val chargingEnabled: Boolean get() = live.charging

    /**
     * The vehicles Home Assistant currently reads a state of charge for, as the vehicle card shows
     * them: every `vehicles` row with a `soc_percent`, with the figures of its own row (the
     * target's reading also falls back to the `soc` block).
     */
    val readVehicles: List<VehicleStatus>
        get() {
            val target = soc?.takeIf { it.vehicleId != null && it.value != null }
            val fromRows = vehicles.mapNotNull { row ->
                val level = target?.takeIf { it.vehicleId == row.id }?.value ?: row.socPercent ?: return@mapNotNull null
                VehicleStatus(
                    id = row.id,
                    name = row.name,
                    socPercent = level,
                    targetSocPercentMax = row.maxPercent ?: target?.takeIf { it.vehicleId == row.id }?.vehicleMaxPercent,
                    batteryCapacityKwh = row.capacityKwh ?: target?.takeIf { it.vehicleId == row.id }?.capacityKwh,
                    capacitySource = row.capacitySource
                )
            }
            if (fromRows.isNotEmpty() || target == null) return fromRows
            val id = target.vehicleId ?: return emptyList()
            return listOf(
                VehicleStatus(
                    id = id,
                    name = target.vehicleName ?: id,
                    socPercent = target.value ?: return emptyList(),
                    targetSocPercentMax = target.vehicleMaxPercent,
                    batteryCapacityKwh = target.capacityKwh,
                    capacitySource = null
                )
            )
        }

    companion object {
        const val API_VERSION = 1

        /** Decode a whole dashboard answer (the webhook envelope's `ok`/`action` keys are ignored). */
        fun parse(json: JSONObject): Dashboard {
            if (json.opt("ok") == false) throw DashboardDecodeException("the answer says it failed")
            if (whole(json.opt("api_version")) != API_VERSION) throw DashboardDecodeException("not api_version 1")
            val charger = obj(json, "charger")
            val phases = json.optJSONObject("phase_detection")
            val market = market(obj(json, "market"), json.optJSONObject("fiscal"))
            return Dashboard(
                generatedAt = optText(json, "generated_at"),
                chargerId = requiredText(charger, "charger_id"),
                chargerName = optText(charger, "charger_name"),
                capabilities = capabilities(obj(charger, "capabilities")),
                status = parseStatus(obj(json, "status")),
                currentRange = obj(json, "current_range").let {
                    DashboardCurrentRange(requiredInt(it, "min_a"), requiredInt(it, "max_a"), optText(it, "source").orEmpty())
                },
                market = market,
                plan = plan(obj(json, "plan")),
                prices = prices(obj(json, "prices")),
                live = obj(json, "live").let {
                    DashboardLive(bool(it, "charging"), bool(it, "schedule_active"), nonNegative(it.opt("measured_current_a")))
                },
                settings = optObj(json, "settings")?.let(::settings)?.let { record ->
                    withIncluded(record, market)
                },
                control = parseControl(json.optJSONObject("control")),
                chargeProgress = ChargeProgressContract.of(json.optJSONObject("charge_progress")),
                strategyOptions = strategyOptions(json.optJSONArray("strategy_options")),
                strategiesNeedingTotalGridPower = needingTotalGridPower(json.optJSONObject("strategy")),
                // Any out-of-range or malformed value (a convention this build does not know) is
                // "not detected".
                detectedPhases = json.optIntOrNull("detected_phases")?.takeIf { it in 1..3 },
                phaseDetectionSource = phases?.optStringOrNull("source"),
                phaseDetectionConfidence = phases?.optStringOrNull("confidence"),
                chargers = json.optJSONArray("chargers")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::charger) }
                }.orEmpty(),
                soc = optObj(json, "soc")?.let(::soc),
                vehicles = array(json, "vehicles").objects().map(::parseVehicle),
                targetVehicleId = optText(json, "target_vehicle_id"),
                site = optObj(json, "site")?.let(::parseSite),
                summary = DashboardSummary.parse(json.opt("summary")),
                chargingPhases = chargingPhases(json.opt("charging_phases")),
                sessionsSummary = SessionsCodec.parseSummary(json.opt("sessions_summary")),
                connection = ChargerConnectionContract.of(json.optJSONObject("connection")),
                chargerPriority = ChargerPriority.parse(json.opt("charger_priority")),
                planningReason = json.optJSONObject("planning")?.opt("reason") as? String,
                startingUpUntil = json.optJSONObject("starting_up")
                    ?.takeIf { it.opt("active") == true }
                    ?.let { it.opt("until") as? String }
                    ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                progress = DashboardProgress.parse(json),
                identification = DashboardIdentification.parse(json.opt("identification")),
                vehicleChoices = vehicleChoices(json.opt("vehicle_choices")),
                cameraIdentification = DashboardCamera.parse(json.opt("camera_identification"))
            )
        }

        /** `vehicle_choices`, read leniently: an entry without an id (or a repeated one) is skipped. */
        private fun vehicleChoices(raw: Any?): List<DashboardVehicleRef>? {
            val list = raw as? JSONArray ?: return null
            return (0 until list.length()).mapNotNull { index ->
                val row = list.opt(index) as? JSONObject ?: return@mapNotNull null
                val id = (row.opt("id") as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                DashboardVehicleRef(id, (row.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: id)
            }.distinctBy { it.id }
        }

        /**
         * The `charging_phases` block, read leniently: one that does not state a usable count of one
         * or three phases is simply not there, and never refuses the whole answer.
         */
        private fun chargingPhases(raw: Any?): DashboardChargingPhases? {
            val block = raw as? JSONObject ?: return null
            val phases = whole(block.opt("phases"))?.takeIf { it == 1 || it == 3 } ?: return null
            return DashboardChargingPhases(
                phases = phases,
                charger = whole(block.opt("charger"))?.takeIf { it == 1 || it == 3 },
                vehicle = whole(block.opt("vehicle"))?.takeIf { it == 1 || it == 3 },
                limitedBy = block.opt("limited_by") as? String
            )
        }

        /**
         * The `market` block, with Home Assistant 1.8's additive fields read leniently: a field this app
         * cannot read is absent, never a refusal of the answer.
         */
        private fun market(json: JSONObject, fiscal: JSONObject?): DashboardMarket = DashboardMarket(
            areaId = optText(json, "area_id"),
            timezone = optText(json, "timezone"),
            currency = optText(json, "currency"),
            majorUnit = optText(json, "major_unit"),
            minorUnit = optText(json, "minor_unit"),
            countries = (json.opt("countries") as? JSONArray)?.let { list ->
                (0 until list.length()).mapNotNull { (list.opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
            }.orEmpty(),
            marketTimezone = optText(json, "market_timezone"),
            included = (json.opt("included") as? JSONArray)?.let { list ->
                (0 until list.length()).mapNotNull { IncludedPart.of(list.opt(it)) }.toSet()
            }.orEmpty(),
            source = (json.opt("source") as? JSONObject)?.let { block ->
                val name = optText(block, "name")
                val url = optText(block, "url")
                if (name != null && url != null && (url.startsWith("https://") || url.startsWith("http://"))) AreaSource(name, url) else null
            },
            fiscalIncluded = fiscal?.let { block ->
                HaAreaOverrideComponent.entries.filter { component ->
                    (block.opt(component.wire) as? JSONObject)?.opt("policy") == "included"
                }.toSet()
            }.orEmpty()
        )

        /**
         * The record with what its area's price already includes, from every place the answer states
         * it: the record's own `fiscal_included`, the market's `included` and the `fiscal` block's
         * `included` policy (each only for the record's own area).
         */
        private fun withIncluded(record: HaPlanningSettings, market: DashboardMarket): HaPlanningSettings {
            if (market.areaId == null || market.areaId != record.areaId) return record
            val included = record.fiscalIncluded + HaAreaOverrideComponent.of(market.included) + market.fiscalIncluded
            return if (included == record.fiscalIncluded) record else record.copy(fiscalIncluded = included)
        }

        /** The canonical settings record; one this app cannot read refuses the whole answer. */
        private fun settings(json: JSONObject): HaPlanningSettings = try {
            HaSettingsCodec.parseResponse(json)
        } catch (refusal: HaSettingsFormatException) {
            throw DashboardDecodeException("`settings` is not the settings record (${refusal.code})")
        }

        /**
         * The block's own answer to "what may this charger be told to do": these five keys, present
         * (more keys are how the contract grows), each read as what it is and nothing coerced.
         */
        private fun parseControl(control: JSONObject?): AutoControl? {
            control ?: return null
            if (!CONTROL_KEYS.all(control::has)) return null
            val listed = control.opt("pause_choices") as? JSONArray ?: return null
            val choices = ArrayList<String>(listed.length())
            for (index in 0 until listed.length()) {
                choices += listed.opt(index) as? String ?: return null
            }
            return AutoControl.of(
                immediateAction = member(control, "immediate_action") ?: AutoControl.UNREADABLE,
                immediateReason = member(control, "immediate_action_reason"),
                automaticAction = member(control, "automatic_action") ?: AutoControl.UNREADABLE,
                automaticReason = member(control, "automatic_action_reason"),
                pauseChoices = choices
            )
        }

        private val CONTROL_KEYS = setOf(
            "immediate_action",
            "immediate_action_reason",
            "automatic_action",
            "automatic_action_reason",
            "pause_choices"
        )

        /**
         * One member of `control` as text, `null` when it is JSON null, and
         * [AutoControl.UNREADABLE] when it is any other type.
         */
        private fun member(control: JSONObject, key: String): String? = when (val value = control.opt(key)) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> AutoControl.UNREADABLE
        }

        /** The rows of `strategy.available` that are unavailable for lack of the meter's total grid power. */
        private fun needingTotalGridPower(block: JSONObject?): Set<HaSettingsStrategy> {
            val rows = block?.optJSONArray("available") ?: return emptySet()
            val found = LinkedHashSet<HaSettingsStrategy>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                if (row.opt("available") != false || row.opt("reason") != NEEDS_TOTAL_GRID_POWER) continue
                HaSettingsStrategy.of(row.opt("strategy"))?.let(found::add)
            }
            return found
        }

        private const val NEEDS_TOTAL_GRID_POWER = "needs_total_grid_power"

        private fun strategyOptions(array: JSONArray?): Set<HaSettingsStrategy> {
            val listed = array ?: return setOf(HaSettingsStrategy.CHEAPEST)
            val recognised = (0 until listed.length()).mapNotNull { HaSettingsStrategy.of(listed.opt(it)) }.toSet()
            return recognised.ifEmpty { setOf(HaSettingsStrategy.CHEAPEST) }
        }

        /**
         * One `chargers` row; without an id there is nothing to match a profile against, so the row
         * is skipped.
         */
        private fun charger(json: JSONObject): StatusCharger? {
            val id = json.strictText("id") ?: return null
            return StatusCharger(id = id, name = json.strictText("name") ?: id)
        }

        /** One `status` block: lines in reading order, and the tone the whole line is styled by. */
        fun parseStatus(json: JSONObject): DashboardStatus {
            val lines = array(json, "lines").objects().map { line ->
                val code = optText(line, "code") ?: throw DashboardDecodeException("a status line has no code")
                StatusLine(code, params(obj(line, "params")))
            }
            val tone = when (json.opt("tone")) {
                "blocking" -> StatusTone.BLOCKING
                "notice" -> StatusTone.NOTICE
                "normal" -> StatusTone.NORMAL
                is String -> StatusTone.NORMAL // a tone from a newer Home Assistant: neutral
                else -> throw DashboardDecodeException("the status has no tone")
            }
            return DashboardStatus(lines, tone)
        }

        /** Param values are JSON scalars or a list of them; anything else is kept as absent. */
        private fun params(json: JSONObject): Map<String, Any?> = buildMap {
            for (key in json.keys()) {
                when (val value = json.opt(key)) {
                    is String, is Boolean -> put(key, value)
                    is Number -> put(key, value.toDouble())
                    is JSONArray -> put(key, (0 until value.length()).mapNotNull { value.opt(it) as? String })
                    else -> put(key, null)
                }
            }
        }

        /**
         * The capabilities this app reads, each one strictly: a value that is not really `true` is
         * "cannot", which hides the control -- offering an action this integration does not perform
         * would be the app promising something the server will not do.
         */
        private fun capabilities(json: JSONObject) = ChargerCapabilities(
            refreshVehicle = json.strictBoolean("refresh_vehicle") == true,
            setChargeLimit = json.strictBoolean("set_charge_limit") == true,
            setCurrent = json.strictBoolean("set_current") == true
        )

        private fun plan(json: JSONObject) = DashboardPlan(
            proposal = optObj(json, "proposal")?.let { p ->
                val cost = optObj(p, "cost")
                DashboardProposal(
                    amps = optInt(p, "amps"),
                    phases = optInt(p, "phases"),
                    powerKw = optNum(p, "power_kw"),
                    plannedKwh = optNum(p, "planned_kwh"),
                    requestedKwh = optNum(p, "requested_kwh"),
                    distanceMil = optNum(p, "distance_mil"),
                    costCurrency = cost?.let { optText(it, "currency") },
                    costValue = cost?.let { optNum(it, "value") },
                    unpriced = p.opt("unpriced") == true,
                    unpricedSlots = optInt(p, "unpriced_slots"),
                    pricedSlots = optInt(p, "priced_slots"),
                    periods = periods(p)
                )
            },
            installed = optObj(json, "installed")?.let { i ->
                DashboardInstalled(
                    optInt(i, "amps"), optInt(i, "phases"), optText(i, "origin"), periods(i),
                    powerKw = nonNegative(i.opt("power_kw"))?.takeIf { it > 0.0 }
                )
            },
            // Read leniently: a count that is not a finite, non-negative number is simply not there.
            deliveredKwh = (json.opt("delivered_kwh") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 },
            remainingKwh = (json.opt("remaining_kwh") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        )

        private fun periods(json: JSONObject): List<DashboardPeriod> = array(json, "periods").objects().map {
            val start = optText(it, "start")?.let(::instant)
            val end = optText(it, "end")?.let(::instant)
            if (start == null || end == null) throw DashboardDecodeException("a period has no readable instants")
            DashboardPeriod(start, end)
        }

        private fun prices(json: JSONObject) = DashboardPrices(
            state = optText(json, "state"),
            today = optText(json, "today"),
            tomorrow = optText(json, "tomorrow"),
            resolutionMinutes = optInt(json, "resolution_minutes"),
            unpriced = json.opt("unpriced") == true,
            intervals = array(json, "intervals").objects().map {
                val start = optText(it, "start")?.let(::instant)
                val end = optText(it, "end")?.let(::instant)
                if (start == null || end == null) throw DashboardDecodeException("a price interval has no readable instants")
                DashboardPriceInterval(
                    start = start,
                    end = end,
                    day = requiredText(it, "day"),
                    durationMinutes = requiredInt(it, "duration_minutes"),
                    effectivePrice = optNum(it, "effective_price"),
                    proposalPlanned = it.opt("proposal_planned") == true,
                    installedPlanned = it.opt("installed_planned") == true
                )
            }
        )

        private fun instant(text: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(text) }.getOrNull()

        private fun soc(json: JSONObject) = DashboardSoc(
            value = optNum(json, "value"),
            ageS = optNum(json, "age_s"),
            capacityKwh = optNum(json, "capacity_kwh"),
            efficiency = optNum(json, "efficiency"),
            estimated = json.opt("estimated") == true,
            missing = strings(json, "missing"),
            needKwh = optNum(json, "need_kwh"),
            source = optText(json, "source"),
            targetPercent = optNum(json, "target_percent"),
            vehicleId = optText(json, "vehicle_id"),
            vehicleName = optText(json, "vehicle_name"),
            vehicleMaxPercent = optNum(json, "vehicle_max_percent"),
            vehicles = array(json, "vehicles").objects().map {
                DashboardVehicleRef(requiredText(it, "id"), optText(it, "name") ?: requiredText(it, "id"))
            },
            roomKwh = optNum(json, "room_kwh")?.takeIf { it >= 0.0 }
        )

        /** One `vehicles` row, as the dashboard and the vehicle writes' own answers state it. */
        fun parseVehicle(json: JSONObject) = DashboardVehicle(
            id = requiredText(json, "id"),
            name = optText(json, "name") ?: requiredText(json, "id"),
            capacityKwh = optNum(json, "capacity_kwh"),
            capacitySource = optText(json, "capacity_source"),
            consumptionKwhPer10km = optNum(json, "consumption_kwh_per_10km"),
            maxPercent = optNum(json, "max_percent"),
            socEntityId = optText(json, "soc_entity_id"),
            socPercent = optNum(json, "soc_percent"),
            onboardPhases = whole(json.opt("onboard_phases"))?.takeIf { it == 1 || it == 3 },
            targetPercent = (json.opt("target_percent") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 },
            targetStated = json.has("target_percent"),
            identification = VehicleIdentificationSources.parse(json.opt("identification"))
        )

        /** The `site` block, as the dashboard and the site write's own answers state it. */
        fun parseSite(json: JSONObject): DashboardSite {
            val control = optObj(json, "active_control")
            return DashboardSite(
                name = optText(json, "name"),
                writable = bool(json, "writable"),
                chargerCount = optInt(json, "charger_count"),
                solarPriority = optText(json, "solar_priority"),
                solarForecastSelected = optObj(json, "solar_forecast")?.let { strings(it, "selected") }.orEmpty(),
                solarForecastChoices = optObj(json, "solar_forecast")?.let { forecast ->
                    array(forecast, "choices").objects().map {
                        DashboardForecastChoice(requiredText(it, "id"), optText(it, "title") ?: requiredText(it, "id"))
                    }
                }.orEmpty(),
                activeControlEnabled = control?.opt("enabled") == true,
                activeControlAvailable = control?.opt("available") == true,
                activeControlWritable = control?.opt("writable") == true,
                activeControlReason = control?.let { optText(it, "reason") }
            )
        }

        /** A lenient number: a finite, non-negative one, else not there (never a refusal of the answer). */
        private fun nonNegative(raw: Any?): Double? =
            (raw as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }

        // --- strict readers: a wrong kind is a refusal, an absent optional is null

        private fun whole(raw: Any?): Int? = (raw as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it == Math.floor(it) && Math.abs(it) < Int.MAX_VALUE }?.toInt()

        private fun obj(json: JSONObject, key: String): JSONObject =
            json.opt(key) as? JSONObject ?: throw DashboardDecodeException("`$key` is not an object")

        private fun optObj(json: JSONObject, key: String): JSONObject? = when (val v = json.opt(key)) {
            null, JSONObject.NULL -> null
            is JSONObject -> v
            else -> throw DashboardDecodeException("`$key` is not an object")
        }

        private fun array(json: JSONObject, key: String): JSONArray =
            json.opt(key) as? JSONArray ?: throw DashboardDecodeException("`$key` is not a list")

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map {
            opt(it) as? JSONObject ?: throw DashboardDecodeException("a list member is not an object")
        }

        private fun strings(json: JSONObject, key: String): List<String> = when (val v = json.opt(key)) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> (0 until v.length()).map {
                v.opt(it) as? String ?: throw DashboardDecodeException("`$key` holds a non-text member")
            }
            else -> throw DashboardDecodeException("`$key` is not a list")
        }

        private fun bool(json: JSONObject, key: String): Boolean =
            json.opt(key) as? Boolean ?: throw DashboardDecodeException("`$key` is not a boolean")

        private fun requiredInt(json: JSONObject, key: String): Int =
            whole(json.opt(key)) ?: throw DashboardDecodeException("`$key` is not a whole number")

        private fun requiredText(json: JSONObject, key: String): String =
            (json.opt(key) as? String)?.takeIf { it.isNotBlank() } ?: throw DashboardDecodeException("`$key` is not text")

        private fun optText(json: JSONObject, key: String): String? = when (val v = json.opt(key)) {
            null, JSONObject.NULL -> null
            is String -> v
            else -> throw DashboardDecodeException("`$key` is not text")
        }

        private fun optNum(json: JSONObject, key: String): Double? = when (val v = json.opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> v.toDouble().takeIf { it.isFinite() } ?: throw DashboardDecodeException("`$key` is not finite")
            else -> throw DashboardDecodeException("`$key` is not a number")
        }

        private fun optInt(json: JSONObject, key: String): Int? = when (val v = json.opt(key)) {
            null, JSONObject.NULL -> null
            else -> whole(v) ?: throw DashboardDecodeException("`$key` is not a whole number")
        }
    }
}
