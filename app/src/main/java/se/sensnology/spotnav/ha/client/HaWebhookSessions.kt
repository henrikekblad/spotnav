package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import se.sensnology.spotnav.ha.sessions.SessionsCodec
import se.sensnology.spotnav.ha.sessions.SessionsCsv
import se.sensnology.spotnav.ha.sessions.SessionsMonth
import java.time.YearMonth

/** How a read of the charge history ended. */
internal sealed interface SessionsOutcome<out T> {
    data class Loaded<T>(val value: T) : SessionsOutcome<T>

    /** The integration does not answer this action, or speaks another version of it. */
    data object Unsupported : SessionsOutcome<Nothing>

    /** Anything else, including no answer at all or one that is not charge history. */
    data object Failed : SessionsOutcome<Nothing>
}

/**
 * The webhook's `sessions` action (`api_version` 1): one month of a charger's charge history, or
 * that month as a CSV text. A read, so nothing here changes anything.
 */
internal object SessionsRead {
    const val API_VERSION = 1
    private const val UNSUPPORTED_ACTION = "Unsupported action"

    /** The request: [month] is `YYYY-MM`, or `null` for the current month; [csv] asks for the CSV text. */
    fun payload(month: YearMonth?, csv: Boolean = false): JSONObject = JSONObject().apply {
        put("version", 1)
        put("action", "sessions")
        put("api_version", API_VERSION)
        month?.let { put("month", it.toString()) }
        if (csv) put("format", "csv")
    }

    /** The answer to a month request. */
    fun month(status: Int?, body: String?): SessionsOutcome<SessionsMonth> =
        answer(status, body) { SessionsCodec.parseMonth(it) }

    /** The answer to a CSV request. */
    fun csv(status: Int?, body: String?): SessionsOutcome<SessionsCsv> =
        answer(status, body) { SessionsCodec.parseCsv(it) }

    private fun <T> answer(status: Int?, body: String?, read: (JSONObject) -> T): SessionsOutcome<T> {
        if (status == null || body.isNullOrBlank()) return SessionsOutcome.Failed
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return SessionsOutcome.Failed
        if (json.opt("ok") != true) {
            return when (json.opt("error")) {
                WriteEnvelope.UNSUPPORTED_VERSION, UNSUPPORTED_ACTION -> SessionsOutcome.Unsupported
                else -> SessionsOutcome.Failed
            }
        }
        return runCatching { read(json) }.fold({ SessionsOutcome.Loaded(it) }, { SessionsOutcome.Failed })
    }
}
