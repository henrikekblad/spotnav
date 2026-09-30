package se.sensnology.spotnav.app

import org.json.JSONObject

/**
 * org.json's `opt*` methods return a type-specific fallback (`""`, `0`, `false`) for both an absent
 * key and a present-but-null one, which makes it impossible to tell "the server didn't send this"
 * from "the server sent null".
 */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

/** A field only when it really is a string, trimmed and non-blank. */
internal fun JSONObject.strictText(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

/** A field only when it really is a JSON boolean. */
internal fun JSONObject.strictBoolean(key: String): Boolean? = opt(key) as? Boolean

/** A field only when it really is a JSON number, as a whole number, and positive. */
internal fun JSONObject.strictPositiveInt(key: String): Int? =
    (opt(key) as? Number)?.toInt()?.takeIf { it > 0 }
