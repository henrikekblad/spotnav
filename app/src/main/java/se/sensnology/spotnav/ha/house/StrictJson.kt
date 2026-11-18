package se.sensnology.spotnav.ha.house

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** A site document that is not what its version promises: a required key missing or of another type. */
internal class MalformedDocument(message: String) : IllegalArgumentException(message)

/**
 * The rule the site documents (house, heat) are read by: a key the app reads must be there and of its type, or the
 * whole document is unreadable, never half-shown; a key the app does not know is not looked at, so Home Assistant can
 * add fields without a new version. Absent and `null` are different things: `null` is a value a key may have, absent
 * is a broken promise (except where a reader says the key came later).
 */
internal object StrictJson {
    fun bad(what: String): Nothing = throw MalformedDocument(what)

    fun obj(raw: Any?, what: String = "object"): JSONObject = raw as? JSONObject ?: bad(what)

    fun objOrNull(raw: Any?, what: String = "object"): JSONObject? = if (raw === JSONObject.NULL) null else obj(raw, what)

    fun list(raw: Any?, what: String = "list"): List<Any?> {
        val array = raw as? JSONArray ?: bad(what)
        return (0 until array.length()).map { array.opt(it) }
    }

    fun text(raw: Any?, what: String = "text"): String = raw as? String ?: bad(what)

    fun textOrNull(raw: Any?, what: String = "text"): String? = if (raw === JSONObject.NULL) null else text(raw, what)

    fun number(raw: Any?, what: String = "number"): Double =
        (raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: bad(what)

    fun numberOrNull(raw: Any?, what: String = "number"): Double? = if (raw === JSONObject.NULL) null else number(raw, what)

    /** A whole number that is not negative. */
    fun count(raw: Any?, what: String = "count"): Int {
        val value = number(raw, what)
        if (value < 0 || value != Math.floor(value) || value > Int.MAX_VALUE) bad(what)
        return value.toInt()
    }

    fun flag(raw: Any?, what: String = "flag"): Boolean = raw as? Boolean ?: bad(what)

    /** An ISO instant with its offset, as Home Assistant writes them. */
    fun instant(raw: Any?, what: String = "instant"): Instant =
        runCatching { OffsetDateTime.parse(text(raw, what)).toInstant() }.getOrElse { bad(what) }

    fun instantOrNull(raw: Any?, what: String = "instant"): Instant? = if (raw === JSONObject.NULL) null else instant(raw, what)

    fun day(raw: Any?, what: String = "date"): LocalDate =
        runCatching { LocalDate.parse(text(raw, what)) }.getOrElse { bad(what) }

    fun dayOrNull(raw: Any?, what: String = "date"): LocalDate? = if (raw === JSONObject.NULL) null else day(raw, what)

    /** A key a later Home Assistant added: absent reads as [fallback], present must be its shape. */
    fun <T> additive(json: JSONObject, key: String, fallback: T, read: (Any?) -> T): T =
        if (json.has(key)) read(json.opt(key)) else fallback
}

/** What a site read came to. */
internal sealed interface SiteRead<out T> {
    data class Read<T>(val value: T) : SiteRead<T>

    /** An answer of another version than this app reads. */
    data object Unsupported : SiteRead<Nothing>

    /** No answer, a refusal ([code] is Home Assistant's stable code) or a document that is not its version's shape. */
    data class Failed(val code: String?) : SiteRead<Nothing>
}
