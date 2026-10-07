package se.sensnology.spotnav.ha.settings

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.regex.Pattern

internal object HaSettingsCodec {
    /** A replacement body's exact keys, deliberately without `revision`. */
    val BODY_KEYS: Set<String> = setOf(
        "area_id",
        "overrides",
        "phases",
        "amps",
        "requested_kwh",
        "max_periods",
        "departure_enabled",
        "departure_time",
        "departure_date",
        "departure_weekdays",
        "strategy",
        "driver",
        "target"
    )

    /**
     * What a response must carry: the body plus the revision the record is at. `departure_date` and
     * `departure_weekdays` are the body keys a response may leave out (an older Home Assistant, or a
     * request that did not ask for them): they then read as no date and every weekday.
     */
    val RESPONSE_KEYS: Set<String> = BODY_KEYS - "departure_date" - "departure_weekdays" + "revision"

    /**
     * Keys a record may carry although it need not (see [RESPONSE_KEYS]). `fiscal_included` is a
     * read-only fact of the record (Home Assistant 1.8): read from an answer, kept in this app's stored
     * copy, and never part of a replacement body. `notifications` (Home Assistant 1.9) is optional
     * both ways: a body that leaves it out keeps the stored choice, and so is `fill_to_limit`.
     */
    private val OPTIONAL_KEYS = setOf(
        "departure_date", "departure_weekdays", FISCAL_INCLUDED, NOTIFICATIONS, FILL_TO_LIMIT, IDENTIFY_MODE, VEHICLE_IDS, IDENTIFY_CAMERA
    )

    private const val FISCAL_INCLUDED = "fiscal_included"
    private const val NOTIFICATIONS = "notifications"
    private const val FILL_TO_LIMIT = "fill_to_limit"
    private const val IDENTIFY_MODE = "identify_mode"
    private const val VEHICLE_IDS = "vehicle_ids"
    private const val IDENTIFY_CAMERA = "identify_camera"
    private val CAMERA_KEYS = setOf("camera_entity_id", "ai_task_entity_id", "frame")
    private val FRAME_KEYS = setOf("x", "y", "w", "h")
    private const val INVALID_VEHICLES = "invalid_vehicles"

    /** A notify service as Home Assistant spells one, and how many one charger may name. */
    private val SERVICE: Pattern = Pattern.compile("^[a-z0-9_]{1,100}$")
    private const val MAX_TARGETS = 10

    private val OVERRIDE_KEYS = setOf("area_id", "vat", "tax", "transfer")
    private val FISCAL_KEYS = setOf("enabled", "value")
    private val TARGET_KEYS = setOf("vehicle_id", "target_percent")

    /** A wall time, `HH:MM`, exactly as the dashboard reports it. */
    private val WALL_TIME: Pattern = Pattern.compile("^([01][0-9]|2[0-3]):([0-5][0-9])$")

    private val ISO_DATE: Pattern = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    /** The domain ranges the server itself enforces. Never clamped — out of range is a refusal. */
    private const val MAX_AMPS = 80
    private const val MAX_PERIODS = 8
    private const val MAX_TARGET_PERCENT = 100.0

    /** A response record: the body plus the revision it is at. */
    fun parseResponse(raw: JSONObject): HaPlanningSettings =
        parse(raw, RESPONSE_KEYS, withRevision = true, exact = false)

    /** A replacement body: every body key, and no `revision`. */
    fun parseBody(raw: JSONObject): HaPlanningSettings =
        parse(raw, BODY_KEYS, withRevision = false, exact = true)

    /**
     * This app's own stored copy of a confirmed record (what [encode] wrote): read exactly, so a
     * document of another shape is never taken for this one.
     */
    fun parseStored(raw: JSONObject): HaPlanningSettings =
        parse(raw, RESPONSE_KEYS, withRevision = true, exact = true)

    /** The record's revision, read from a response and only from a response. */
    private fun revision(raw: JSONObject, withRevision: Boolean): Int =
        if (withRevision) {
            whole(raw.opt("revision"), "revision", "invalid_number", 0, Int.MAX_VALUE)
        } else {
            0
        }

    private fun parse(raw: JSONObject, keys: Set<String>, withRevision: Boolean, exact: Boolean): HaPlanningSettings {
        requireKeys(raw, keys, "settings", exact)
        val overrides = raw.opt("overrides")
        if (overrides !is JSONArray) refuse("invalid_area", "overrides must be an array")
        val parsed = (0 until overrides.length()).map { index -> override(overrides.opt(index), index, exact) }
        val seen = mutableSetOf<String>()
        parsed.forEach { item ->
            if (!seen.add(item.areaId)) {
                refuse("invalid_area", "'${item.areaId}' has two override records")
            }
        }
        return HaPlanningSettings(
            revision = revision(raw, withRevision),
            areaId = nullable(raw.opt("area_id")) {
                text(it, "area_id", "invalid_area").takeIf { name -> name.isNotEmpty() }
                    ?: refuse("invalid_area", "area_id must be a non-empty string or absent")
            },
            overrides = parsed,
            phases = nullable(raw.opt("phases")) { value ->
                whole(value, "phases", "invalid_phases", 1, 3)
                    .takeIf { it == 1 || it == 3 }
                    ?: refuse("invalid_phases", "phases must be 1 or 3 when it is set")
            },
            amps = nullable(raw.opt("amps")) { value -> whole(value, "amps", "invalid_amps", 1, MAX_AMPS) },
            requestedKwh = positive(raw.opt("requested_kwh"), "requested_kwh", "invalid_energy"),
            // `null` is automatic periods; the key itself is still required (`requireKeys`).
            maxPeriods = nullable(raw.opt("max_periods")) { whole(it, "max_periods", "invalid_periods", 1, MAX_PERIODS) },
            departureEnabled = boolean(raw.opt("departure_enabled"), "departure_enabled", "invalid_departure"),
            departureTime = wallTime(raw.opt("departure_time")),
            departureDate = nullable(raw.opt("departure_date")) { departureDate(it) },
            departureWeekdays = nullable(raw.opt("departure_weekdays")) { weekdays(it) } ?: ALL_WEEKDAYS,
            strategy = HaSettingsStrategy.of(
                enum(raw.opt("strategy"), HaSettingsStrategy.entries.map { it.wire }, "invalid_strategy")
            )!!,
            driver = HaSettingsDriver.of(enum(raw.opt("driver"), HaSettingsDriver.entries.map { it.wire }, "invalid_driver"))!!,
            target = target(raw.opt("target"), exact),
            fiscalIncluded = if (withRevision) fiscalIncluded(raw.opt(FISCAL_INCLUDED)) else emptySet(),
            notifications = notifications(raw.opt(NOTIFICATIONS), strict = !withRevision),
            fillToLimit = nullable(raw.opt(FILL_TO_LIMIT)) { boolean(it, FILL_TO_LIMIT, "invalid_energy") },
            identification = identification(raw, strict = !withRevision),
            camera = camera(raw, strict = !withRevision)
        )
    }

    /**
     * `identify_mode` and `vehicle_ids`: stated when the record has the mode (the list then defaults
     * to every car). A replacement body is held to the contract ([strict]: `invalid_vehicles`); an
     * answer is read leniently, so a shape this app cannot read hides the section, is never sent
     * back, and never refuses the whole record.
     */
    private fun identification(raw: JSONObject, strict: Boolean): HaIdentificationSettings? {
        if (!raw.has(IDENTIFY_MODE) && !raw.has(VEHICLE_IDS)) return null
        return try {
            if (!strict && !(raw.has(IDENTIFY_MODE) && raw.has(VEHICLE_IDS))) refuse(INVALID_VEHICLES, "both fields or neither")
            val mode = IdentifyMode.of(raw.opt(IDENTIFY_MODE)) ?: refuse(INVALID_VEHICLES, "identify_mode is not a mode")
            val ids = nullable(raw.opt(VEHICLE_IDS)) { value ->
                val list = value as? JSONArray ?: refuse(INVALID_VEHICLES, "vehicle_ids must be null or a list")
                val ids = (0 until list.length()).map { index ->
                    (list.opt(index) as? String)?.takeIf { it.isNotEmpty() }
                        ?: refuse(INVALID_VEHICLES, "vehicle_ids must hold vehicle ids")
                }
                if (ids.isEmpty() || ids.toSet().size != ids.size) {
                    refuse(INVALID_VEHICLES, "vehicle_ids must name at least one vehicle, each once")
                }
                ids
            }
            HaIdentificationSettings(mode, ids)
        } catch (refusal: HaSettingsFormatException) {
            if (strict) throw refusal else null
        }
    }

    /**
     * `identify_camera`: stated when the record has it (`null` is no camera); read-only for this app, so
     * never part of a replacement it sends. A replacement body is held
     * to the contract ([strict]: `invalid_camera`); an answer is read leniently, so a shape this app
     * cannot read hides the camera, is never sent back, and never refuses the whole record.
     */
    private fun camera(raw: JSONObject, strict: Boolean): HaCameraChoice? {
        if (!raw.has(IDENTIFY_CAMERA)) return null
        return try {
            HaCameraChoice(nullable(raw.opt(IDENTIFY_CAMERA)) { value ->
                val json = value as? JSONObject ?: refuse(HaCameraRules.INVALID_CAMERA, "identify_camera must be null or an object")
                requireKeys(json, CAMERA_KEYS, IDENTIFY_CAMERA, exact = strict, code = HaCameraRules.INVALID_CAMERA)
                val camera = HaCameraSettings(
                    cameraEntityId = json.opt("camera_entity_id") as? String
                        ?: refuse(HaCameraRules.INVALID_CAMERA, "camera_entity_id must be a camera"),
                    aiTaskEntityId = nullable(json.opt("ai_task_entity_id")) {
                        it as? String ?: refuse(HaCameraRules.INVALID_CAMERA, "ai_task_entity_id must be an AI Task entity or null")
                    },
                    frame = nullable(json.opt("frame")) { frame(it, strict) }
                )
                camera.takeIf { HaCameraRules.valid(it) } ?: refuse(HaCameraRules.INVALID_CAMERA, "identify_camera is not a camera")
            })
        } catch (refusal: HaSettingsFormatException) {
            if (strict) throw refusal else null
        }
    }

    /** A frame: four fractions, inside the picture and at least the least size each way. */
    private fun frame(raw: Any, strict: Boolean): CameraFrame {
        val json = raw as? JSONObject ?: refuse(HaCameraRules.INVALID_CAMERA, "frame must be an object or null")
        requireKeys(json, FRAME_KEYS, "frame", exact = strict, code = HaCameraRules.INVALID_CAMERA)
        val (x, y, w, h) = listOf("x", "y", "w", "h").map { number(json.opt(it), "frame.$it", HaCameraRules.INVALID_CAMERA) }
        return CameraFrame(x, y, w, h)
    }

    /**
     * `notifications`: absent or `null` is "not stated". A replacement body is held to the contract
     * ([strict]: a refusal names `invalid_notifications`); an answer is read leniently, so a shape
     * this app cannot read hides the section instead of refusing the whole record. Event ids this
     * app does not know are kept, so an edit from here never turns off a newer Home Assistant's event.
     */
    private fun notifications(raw: Any?, strict: Boolean): HaNotificationSettings? {
        if (raw == null || raw === JSONObject.NULL) return null
        return try {
            val json = raw as? JSONObject ?: refuse(INVALID_NOTIFICATIONS, "notifications must be an object")
            val targets = textList(json.opt("targets"), "targets")
            val events = textList(json.opt("events"), "events")
            if (targets.size > MAX_TARGETS) refuse(INVALID_NOTIFICATIONS, "at most $MAX_TARGETS notify targets")
            if (targets.any { !SERVICE.matcher(it).matches() }) refuse(INVALID_NOTIFICATIONS, "a target must be a notify service name")
            if (targets.toSet().size != targets.size || events.toSet().size != events.size) {
                refuse(INVALID_NOTIFICATIONS, "a target or an event must not repeat")
            }
            val url = when (val value = json.opt("url")) {
                null, JSONObject.NULL -> null
                is String -> value.takeIf { it.startsWith("/") && !it.startsWith("//") }
                    ?: refuse(INVALID_NOTIFICATIONS, "url must be a Home Assistant path")
                else -> refuse(INVALID_NOTIFICATIONS, "url must be a path or null")
            }
            HaNotificationSettings(
                targets = targets,
                events = canonicalEvents(events),
                url = url,
                available = available(json.opt("available"))
            )
        } catch (refusal: HaSettingsFormatException) {
            if (strict) throw refusal else null
        }
    }

    /** Events in the order the contract lists them, the ones this app does not know after them. */
    internal fun canonicalEvents(events: Collection<String>): List<String> =
        NotificationEvent.entries.map { it.wire }.filter { it in events } +
            events.filter { NotificationEvent.of(it) == null }.distinct()

    /** The read-only phones: each row with a service name, named after its phone (or the service). */
    private fun available(raw: Any?): List<HaNotifyService> {
        val list = raw as? JSONArray ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val row = list.opt(index) as? JSONObject ?: return@mapNotNull null
            val service = (row.opt("service") as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            HaNotifyService(service, (row.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: service)
        }.distinctBy { it.service }
    }

    private fun textList(raw: Any?, what: String): List<String> {
        val list = raw as? JSONArray ?: refuse(INVALID_NOTIFICATIONS, "$what must be a list")
        return (0 until list.length()).map { index ->
            list.opt(index) as? String ?: refuse(INVALID_NOTIFICATIONS, "$what must hold text")
        }
    }

    private const val INVALID_NOTIFICATIONS = "invalid_notifications"

    /**
     * The read-only `fiscal_included`: the components named in it that this app knows. Lenient on
     * purpose -- it is a fact about the price, not a value this app edits, so a shape it does not
     * understand reads as nothing included rather than refusing the whole record.
     */
    private fun fiscalIncluded(raw: Any?): Set<HaAreaOverrideComponent> {
        val list = raw as? JSONArray ?: return emptySet()
        return (0 until list.length()).mapNotNull { HaAreaOverrideComponent.of(list.opt(it)) }.toSet()
    }

    private fun override(raw: Any?, index: Int, exact: Boolean): HaAreaOverride {
        val json = raw as? JSONObject ?: refuse("invalid_area", "override $index must be an object")
        requireKeys(json, OVERRIDE_KEYS, "override $index", exact)
        return HaAreaOverride(
            areaId = text(json.opt("area_id"), "an override's area_id", "invalid_area")
                .takeIf { name -> name.isNotEmpty() }
                ?: refuse("invalid_area", "an override needs an area id"),
            vat = fiscal(json.opt("vat"), "vat", exact),
            tax = fiscal(json.opt("tax"), "tax", exact),
            transfer = fiscal(json.opt("transfer"), "transfer", exact)
        )
    }

    /** One fiscal component, with all three states kept apart. */
    private fun fiscal(raw: Any?, what: String, exact: Boolean): HaFiscalValue {
        val json = raw as? JSONObject ?: refuse("invalid_fiscal", "$what must be an object")
        requireKeys(json, FISCAL_KEYS, what, exact)
        return HaFiscalValue(
            enabled = boolean(json.opt("enabled"), "$what.enabled", "invalid_fiscal"),
            value = nullable(json.opt("value")) { value ->
                number(value, "$what.value", "invalid_fiscal").takeIf { it >= 0 }
                    ?: refuse("invalid_fiscal", "$what.value must not be negative")
            }
        )
    }

    private fun target(raw: Any?, exact: Boolean): HaTargetIntent {
        val json = raw as? JSONObject ?: refuse("invalid_target", "target must be an object")
        requireKeys(json, TARGET_KEYS, "target", exact)
        return HaTargetIntent(
            vehicleId = nullable(json.opt("vehicle_id")) { value ->
                text(value, "vehicle_id", "invalid_target").takeIf { it.isNotEmpty() }
                    ?: refuse("invalid_target", "vehicle_id must be a non-empty string or absent")
            },
            targetPercent = nullable(json.opt("target_percent")) { value ->
                double(value, "target_percent", "invalid_target").takeIf { it in 0.0..MAX_TARGET_PERCENT }
                    ?: refuse("invalid_target", "target_percent must be between 0 and 100")
            }
        )
    }

    // reading

    /**
     * An object's shape: every required key present, and nothing interpreted. A request body ([exact])
     * also has no unknown key; a response keeps the contract's way of growing, so an added field is
     * ignored and never refuses the answer.
     */
    private fun requireKeys(json: JSONObject, keys: Set<String>, what: String, exact: Boolean, code: String? = null) {
        val present = json.keys().asSequence().toSet()
        val missing = keys - present
        if (missing.isNotEmpty()) refuse(code ?: "missing_field", "$what is missing ${missing.sorted()}")
        if (!exact) return
        val unknown = present - keys - OPTIONAL_KEYS
        if (unknown.isNotEmpty()) refuse(code ?: "unknown_field", "$what has unknown fields ${unknown.sorted()}")
    }

    private fun <T> nullable(raw: Any?, read: (Any) -> T): T? =
        if (raw == null || raw === JSONObject.NULL) null else read(raw)

    /** A field only when it really is a JSON number, and finite. */
    private fun number(raw: Any?, what: String, code: String): Double {
        val value = raw as? Number ?: refuse(code, "$what must be a number")
        val number = value.toDouble()
        if (!number.isFinite()) refuse(code, "$what must be finite")
        return number
    }

    private fun double(raw: Any?, what: String, code: String): Double = number(raw, what, code)

    /** A number that must also be a whole one, inside the contract's own range — never clamped. */
    private fun whole(raw: Any?, what: String, code: String, min: Int, max: Int): Int {
        val number = number(raw, what, code)
        if (number != Math.floor(number) || number < min || number > max) {
            refuse(code, "$what must be a whole number between $min and $max")
        }
        return number.toInt()
    }

    /** A number that must be strictly positive: zero energy is not an amount of energy. */
    private fun positive(raw: Any?, what: String, code: String): Double =
        number(raw, what, code).takeIf { it > 0 } ?: refuse(code, "$what must be greater than zero")

    private fun boolean(raw: Any?, what: String, code: String): Boolean =
        raw as? Boolean ?: refuse(code, "$what must be a boolean")

    private fun text(raw: Any?, what: String, code: String): String =
        raw as? String ?: refuse(code, "$what must be a string")

    private fun enum(raw: Any?, allowed: List<String>, code: String): String =
        (raw as? String)?.takeIf { it in allowed } ?: refuse(code, "$raw is not one of $allowed")

    /** A calendar date, `YYYY-MM-DD`, exactly as the contract writes it. */
    private fun departureDate(raw: Any): LocalDate {
        val value = raw as? String ?: refuse("invalid_departure", "departure_date must be a date string")
        if (!ISO_DATE.matcher(value).matches()) refuse("invalid_departure", "departure_date must be YYYY-MM-DD")
        return try {
            LocalDate.parse(value)
        } catch (failure: DateTimeParseException) {
            refuse("invalid_departure", "departure_date is not a calendar date")
        }
    }

    /** A list of distinct ISO weekdays, 1 (Monday) to 7 (Sunday), at least one, read ascending. */
    private fun weekdays(raw: Any): List<Int> {
        val list = raw as? JSONArray ?: refuse("invalid_departure", "departure_weekdays must be a list")
        val days = (0 until list.length()).map { index ->
            whole(list.opt(index), "a departure weekday", "invalid_departure", 1, 7)
        }
        if (days.isEmpty() || days.toSet().size != days.size) {
            refuse("invalid_departure", "departure_weekdays must name each chosen day once, and at least one")
        }
        return days.sorted()
    }

    private fun wallTime(raw: Any?): String {
        val value = raw as? String ?: refuse("invalid_departure", "departure_time must be a wall time string")
        if (!WALL_TIME.matcher(value).matches()) {
            refuse("invalid_departure", "departure_time must be HH:MM")
        }
        return value
    }

    // writing

    /** The complete canonical value: the body keys plus the record's own `revision`. */
    fun encode(settings: HaPlanningSettings): JSONObject = document(settings, withRevision = true)

    /** A replacement body: the same value without `revision`. */
    fun encodeBody(settings: HaPlanningSettings): JSONObject = document(settings, withRevision = false)

    private fun document(settings: HaPlanningSettings, withRevision: Boolean): JSONObject =
        JSONObject().apply {
            if (withRevision) put("revision", settings.revision)
            putNullable("area_id", settings.areaId)
            put("overrides", JSONArray().apply {
                settings.overrides.sortedBy { it.areaId }.forEach { item -> put(encodedOverride(item)) }
            })
            putNullable("phases", settings.phases)
            putNullable("amps", settings.amps)
            put("requested_kwh", settings.requestedKwh)
            // Stated only when Home Assistant stated it, and then always sent back as it stands.
            settings.fillToLimit?.let { put(FILL_TO_LIMIT, it) }
            put("max_periods", settings.maxPeriods ?: JSONObject.NULL)
            put("departure_enabled", settings.departureEnabled)
            put("departure_time", settings.departureTime)
            putNullable("departure_date", settings.departureDate?.toString())
            put("departure_weekdays", JSONArray(settings.departureWeekdays.sorted()))
            put("strategy", settings.strategy.wire)
            put("driver", settings.driver.wire)
            put("target", JSONObject().apply {
                putNullable("vehicle_id", settings.target.vehicleId)
                putNullable("target_percent", settings.target.targetPercent)
            })
            // The stored copy keeps the read-only fact; a replacement body never carries it.
            if (withRevision && settings.fiscalIncluded.isNotEmpty()) {
                put(FISCAL_INCLUDED, JSONArray(HaAreaOverrideComponent.entries.filter { it in settings.fiscalIncluded }.map { it.wire }))
            }
            // Stated only when Home Assistant stated them, and then always sent back as they stand.
            settings.identification?.let { identification ->
                put(IDENTIFY_MODE, identification.mode.wire)
                put(VEHICLE_IDS, identification.vehicleIds?.let { JSONArray(it) } ?: JSONObject.NULL)
            }
            // The camera and its AI task are an administrator's choice in Home Assistant: the stored copy
            // keeps them as read, and a replacement leaves them out (Home Assistant then keeps them).
            settings.camera?.takeIf { withRevision }?.let { choice ->
                put(IDENTIFY_CAMERA, choice.camera?.let { camera ->
                    JSONObject().apply {
                        put("camera_entity_id", camera.cameraEntityId)
                        putNullable("ai_task_entity_id", camera.aiTaskEntityId)
                        putNullable("frame", camera.frame?.let { frame ->
                            JSONObject().put("x", frame.x).put("y", frame.y).put("w", frame.w).put("h", frame.h)
                        })
                    }
                } ?: JSONObject.NULL)
            }
            // Stated only when Home Assistant stated it; the phones that exist are the stored copy's alone.
            settings.notifications?.let { choice ->
                put(NOTIFICATIONS, JSONObject().apply {
                    put("targets", JSONArray(choice.targets))
                    put("events", JSONArray(choice.events))
                    putNullable("url", choice.url)
                    if (withRevision) {
                        put("available", JSONArray().apply {
                            choice.available.forEach { phone ->
                                put(JSONObject().put("service", phone.service).put("name", phone.name))
                            }
                        })
                    }
                })
            }
        }

    private fun encodedOverride(override: HaAreaOverride): JSONObject = JSONObject().apply {
        put("area_id", override.areaId)
        put("vat", encodedFiscal(override.vat))
        put("tax", encodedFiscal(override.tax))
        put("transfer", encodedFiscal(override.transfer))
    }

    private fun encodedFiscal(fiscal: HaFiscalValue): JSONObject = JSONObject().apply {
        put("enabled", fiscal.enabled)
        putNullable("value", fiscal.value)
    }

    /** The contract's own way of saying "no value". */
    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun refuse(code: String, message: String): Nothing =
        throw HaSettingsFormatException(code, message)
}
