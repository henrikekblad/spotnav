package se.sensnology.spotnav.ha.client

import org.json.JSONObject

/**
 * Why Home Assistant refused a Start or Stop, read from the webhook's own answer
 * (`{"ok": false, "error": "<code>"}`), so the screen can say it in a person's words.
 */
internal object CommandRefusal {
    /** A Start with no car plugged in (Home Assistant 1.11): nothing was sent to the charger. */
    const val VEHICLE_NOT_CONNECTED = "vehicle_not_connected"

    /** The stable code of a refused command, or `null` when [failure] is no readable refusal. */
    fun code(failure: Throwable?): String? {
        val refused = failure as? WebhookHttpStatusException ?: return null
        val body = runCatching { JSONObject(refused.bodyText) }.getOrNull() ?: return null
        if (body.optBoolean("ok", true)) return null
        return (body.opt("error") as? String)?.takeIf { it.isNotBlank() }
    }
}
