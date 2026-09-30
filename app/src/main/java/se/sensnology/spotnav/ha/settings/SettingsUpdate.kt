package se.sensnology.spotnav.ha.settings

import org.json.JSONObject

/** One `settings` answer from the charger's own webhook, as a typed outcome. */
object SettingsUpdate {
    /** The one conflict code the contract defines; a 409 with anything else is malformed. */
    const val REVISION_CONFLICT = "revision_conflict"

    /** Nothing was written and the old record stands. */
    const val NOT_COMMITTED = "spotnav_settings_not_committed"

    /** The replacement is durable and the reconcile after it was not. */
    const val RECONCILE_FAILED = "spotnav_settings_reconcile_failed"

    /** The four statuses the settings action defines. Anything else is [Outcome.Unavailable]. */
    private val CONTRACT_STATUSES = setOf(200, 400, 409, 500)

    sealed interface Outcome {
        data class Updated(val settings: HaPlanningSettings) : Outcome

        data class Invalid(val code: String, val current: HaPlanningSettings?) : Outcome

        data class Conflict(val current: HaPlanningSettings) : Outcome

        data class NotCommitted(val current: HaPlanningSettings) : Outcome

        data class CommittedButReconcileFailed(val committed: HaPlanningSettings) : Outcome

        data object Malformed : Outcome

        data object Unavailable : Outcome

        /** The record this outcome says currently stands, if it carries one. */
        val record: HaPlanningSettings?
            get() = when (this) {
                is Updated -> settings
                is Conflict -> current
                is NotCommitted -> current
                is CommittedButReconcileFailed -> committed
                else -> null
            }
    }

    /** The settings contract's own version: the only one this app reads. */
    private const val API_VERSION = 1

    /** The action the webhook routes this contract on; anything else is another contract's answer. */
    const val SETTINGS_ACTION = "settings"

    /** The envelope's exact keys: the settings contract's own five. */
    private val ENVELOPE_KEYS = setOf("api_version", "ok", "error", "settings", "pause")

    /** What one answer means. [status] is `null` when no HTTP answer arrived at all. */
    fun answer(status: Int?, bodyText: String?): Outcome {
        if (status == null || status !in CONTRACT_STATUSES) return Outcome.Unavailable
        val body = bodyText?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
            ?: return Outcome.Malformed

        // The shape first: the five keys, none missing and none extra, and an `action` (when there
        // is one) naming this action.
        val keys = body.keys().asSequence().toSet() - "action"
        if (keys != ENVELOPE_KEYS) return Outcome.Malformed
        if (body.has("action") && body.opt("action") != SETTINGS_ACTION) return Outcome.Malformed

        // The version decides whether the value may be read at all: only the one this app
        // implements is.
        val version = (body.opt("api_version") as? Number)?.toDouble() ?: return Outcome.Malformed
        if (version != API_VERSION.toDouble()) return Outcome.Malformed

        // `error` is present by shape; it is either JSON null or a non-empty string, never a blank
        // one and never another type -- `{"ok": false, "error".
        val errorRaw = body.opt("error")
        if (errorRaw !== JSONObject.NULL && errorRaw !is String) return Outcome.Malformed
        if (errorRaw is String && errorRaw.isBlank()) return Outcome.Malformed
        val code = errorRaw as? String

        val ok = body.opt("ok") as? Boolean ?: return Outcome.Malformed
        val record = try {
            readRecord(body)
        } catch (refusal: HaSettingsFormatException) {
            // A record this app cannot read is not a record to act on, and certainly not one to
            // cache.
            return Outcome.Malformed
        }

        return when (status) {
            200 -> if (ok && code == null && record != null) Outcome.Updated(record) else Outcome.Malformed
            400 -> if (!ok && code != null) Outcome.Invalid(code, record) else Outcome.Malformed
            409 -> if (!ok && code == REVISION_CONFLICT && record != null) {
                Outcome.Conflict(record)
            } else {
                Outcome.Malformed
            }
            else -> when {
                ok -> Outcome.Malformed
                code == NOT_COMMITTED && record != null -> Outcome.NotCommitted(record)
                code == RECONCILE_FAILED && record != null -> Outcome.CommittedButReconcileFailed(record)
                else -> Outcome.Malformed
            }
        }
    }

    /** The envelope's `settings`, or `null` when the key is genuinely absent or `null`. */
    private fun readRecord(body: JSONObject): HaPlanningSettings? {
        val raw = if (body.has("settings")) body.opt("settings") else null
        if (raw == null || raw === JSONObject.NULL) return null
        val json = raw as? JSONObject ?: throw HaSettingsFormatException("unknown_field", "settings must be an object")
        return HaSettingsCodec.parseResponse(json)
    }
}
