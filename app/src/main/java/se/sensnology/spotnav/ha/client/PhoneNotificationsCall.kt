package se.sensnology.spotnav.ha.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Home Assistant holds for this phone's notifications (SpotNav 3.0, webhook `phone_notifications`): whether
 * anything is sent to it ([on]), which events ([events], wire ids, also one this app cannot name) and which chargers it
 * follows ([chargers], `null` = every charger), with the installation's chargers, its loads and every event in order.
 */
internal data class PhoneNotifications(
    val phoneId: String,
    val name: String,
    val on: Boolean,
    val events: List<String>,
    val chargers: List<String>?,
    /** The Home Assistant app on the same phone, by its name; `null` with none. */
    val companionName: String?,
    /** Home Assistant holds a wake-up reference for this phone. */
    val instant: Boolean,
    val installationChargers: List<Charger>,
    val loads: Loads,
    val allEvents: List<String>
) {
    data class Charger(val id: String, val name: String)

    data class Loads(val chargers: Int, val pool: Boolean, val spa: Boolean, val sauna: Boolean)

    /** Whether this phone follows the charger [chargerId] (Home Assistant's entry id); one not known by id is followed. */
    fun follows(chargerId: String?): Boolean = chargers == null || chargerId == null || chargerId in chargers

    fun wants(event: String): Boolean = on && event in events
}

/**
 * The webhook action `phone_notifications` (on a site's webhook, or a charger's for a pairing before 3.0): this phone's
 * own entry, read (no `set`) or written (`set: {on | events | chargers}`), with the relay's wake-up reference
 * ([Ref.Set], `null` clears it) and what this app shows itself (`shows`). A Home Assistant before 3.0.0b5 answers
 * "Unsupported action" ([Outcome.NotSupported]): the app keeps its own choice and `push_register` there.
 *
 * The answer is read strictly: every key as its version states it and no other, or [Outcome.Failed].
 */
internal object PhoneNotificationsCall {
    const val ACTION = "phone_notifications"
    const val API_VERSION = 1
    const val INVALID = "invalid_phone_notifications"
    private const val UNSUPPORTED_ACTION = "Unsupported action"

    /** The pool's season hint, a phone event beside the charger's eight. */
    const val POOL_SEASON = "pool_season"

    /** What the app says it shows for a spa's or a sauna's "not ready in time" (a `shows` word, not an event). */
    const val HEAT_AT_RISK = "heat_at_risk"

    /** Whether to hand over the wake-up reference: [Keep] leaves `push_ref` out, [Set] sends it (`null` clears it). */
    sealed interface Ref {
        data object Keep : Ref
        data class Set(val ref: String?) : Ref
    }

    /** Which chargers to follow: every one (also one added later), or [Only] those (maybe none). */
    sealed interface Chargers {
        data object All : Chargers
        data class Only(val ids: List<String>) : Chargers
    }

    /** One write of this phone's own entry; a `null` field is left as it is. */
    data class Change(val on: Boolean? = null, val events: List<String>? = null, val chargers: Chargers? = null) {
        val empty: Boolean get() = on == null && events == null && chargers == null
    }

    fun payload(
        appId: String,
        name: String? = null,
        ref: Ref = Ref.Keep,
        shows: List<String>? = null,
        change: Change? = null,
        model: String? = null
    ): JSONObject =
        JSONObject().apply {
            put("version", 1)
            put("action", ACTION)
            put("api_version", API_VERSION)
            put("app_id", appId)
            if (name != null) put("name", name)
            if (model != null) put("model", model)
            if (ref is Ref.Set) put("push_ref", ref.ref ?: JSONObject.NULL)
            if (shows != null) put("shows", JSONArray(shows))
            if (change != null && !change.empty) {
                put("set", JSONObject().apply {
                    change.on?.let { put("on", it) }
                    change.events?.let { put("events", JSONArray(it)) }
                    when (val chargers = change.chargers) {
                        null -> Unit
                        Chargers.All -> put("chargers", JSONObject.NULL)
                        is Chargers.Only -> put("chargers", JSONArray(chargers.ids))
                    }
                })
            }
        }

    sealed interface Outcome {
        data class Read(val value: PhoneNotifications) : Outcome

        /** A Home Assistant without the action (before 3.0.0b5), or of another version: keep the app's own choice. */
        data object NotSupported : Outcome

        /** Refused as a body that cannot be stored (`invalid_phone_notifications`): nothing was written. */
        data object Refused : Outcome

        /** No answer, another refusal ([code]), or an answer that is not this version's shape. */
        data class Failed(val code: String?) : Outcome
    }

    fun answer(status: Int?, body: String?): Outcome {
        if (status == null) return Outcome.Failed(null)
        val head = WriteEnvelope.head(body) ?: return Outcome.Failed(null)
        if (!head.ok) {
            return when (head.error) {
                UNSUPPORTED_ACTION, WriteEnvelope.UNSUPPORTED_VERSION -> Outcome.NotSupported
                INVALID -> Outcome.Refused
                else -> Outcome.Failed(head.error)
            }
        }
        if (status !in 200..299) return Outcome.Failed(head.error)
        return decode(head.json)?.let { Outcome.Read(it) } ?: Outcome.Failed(null)
    }

    private val ANSWER_KEYS = setOf("ok", "action", "api_version", "phone", "chargers", "loads", "events")
    private val PHONE_KEYS = setOf("id", "name", "on", "events", "chargers", "companion", "instant")
    private val COMPANION_KEYS = setOf("name")
    private val CHARGER_KEYS = setOf("id", "name")
    private val LOADS_KEYS = setOf("chargers", "pool", "spa", "sauna")

    private class Malformed : IllegalArgumentException()

    private fun bad(): Nothing = throw Malformed()

    private fun exact(json: JSONObject, keys: Set<String>): JSONObject {
        if (json.keys().asSequence().toSet() != keys) bad()
        return json
    }

    private fun obj(raw: Any?): JSONObject = raw as? JSONObject ?: bad()

    private fun text(raw: Any?): String = raw as? String ?: bad()

    private fun flag(raw: Any?): Boolean = raw as? Boolean ?: bad()

    private fun texts(raw: Any?): List<String> {
        val array = raw as? JSONArray ?: bad()
        return (0 until array.length()).map { text(array.opt(it)) }
    }

    private fun count(raw: Any?): Int {
        if (raw !is Int && raw !is Long) bad()
        val number = (raw as Number).toLong()
        if (number < 0 || number > Int.MAX_VALUE) bad()
        return number.toInt()
    }

    /** The success answer, every key as version 1 states it and no other; `null` for anything else. */
    fun decode(json: JSONObject): PhoneNotifications? = try {
        exact(json, ANSWER_KEYS)
        if (json.opt("ok") != true || json.opt("action") != ACTION) bad()
        if (count(json.opt("api_version")) != API_VERSION) bad()
        val phone = exact(obj(json.opt("phone")), PHONE_KEYS)
        val companion = phone.opt("companion").let { raw ->
            if (raw === JSONObject.NULL) null else text(exact(obj(raw), COMPANION_KEYS).opt("name"))
        }
        val chargers = phone.opt("chargers").let { raw -> if (raw === JSONObject.NULL) null else texts(raw) }
        val list = (json.opt("chargers") as? JSONArray ?: bad()).let { array ->
            (0 until array.length()).map { index ->
                val charger = exact(obj(array.opt(index)), CHARGER_KEYS)
                PhoneNotifications.Charger(text(charger.opt("id")), text(charger.opt("name")))
            }
        }
        val loads = exact(obj(json.opt("loads")), LOADS_KEYS)
        PhoneNotifications(
            phoneId = text(phone.opt("id")).ifEmpty { bad() },
            name = text(phone.opt("name")),
            on = flag(phone.opt("on")),
            events = texts(phone.opt("events")),
            chargers = chargers,
            companionName = companion,
            instant = flag(phone.opt("instant")),
            installationChargers = list,
            loads = PhoneNotifications.Loads(
                chargers = count(loads.opt("chargers")),
                pool = flag(loads.opt("pool")),
                spa = flag(loads.opt("spa")),
                sauna = flag(loads.opt("sauna"))
            ),
            allEvents = texts(json.opt("events"))
        )
    } catch (malformed: Malformed) {
        null
    }

    /** [value] as the answer it was read from, to keep it between two reads. */
    fun encode(value: PhoneNotifications): JSONObject = JSONObject().apply {
        put("ok", true)
        put("action", ACTION)
        put("api_version", API_VERSION)
        put("phone", JSONObject().apply {
            put("id", value.phoneId)
            put("name", value.name)
            put("on", value.on)
            put("events", JSONArray(value.events))
            put("chargers", value.chargers?.let { JSONArray(it) } ?: JSONObject.NULL)
            put("companion", value.companionName?.let { JSONObject().put("name", it) } ?: JSONObject.NULL)
            put("instant", value.instant)
        })
        put("chargers", JSONArray().apply { value.installationChargers.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        put("loads", JSONObject().apply {
            put("chargers", value.loads.chargers)
            put("pool", value.loads.pool)
            put("spa", value.loads.spa)
            put("sauna", value.loads.sauna)
        })
        put("events", JSONArray(value.allEvents))
    }
}
