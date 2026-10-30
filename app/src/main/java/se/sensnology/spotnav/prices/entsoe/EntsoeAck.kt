package se.sensnology.spotnav.prices.entsoe

import se.sensnology.spotnav.prices.SafeXml

/**
 * What an ENTSO-E acknowledgement means. Code 999 is generic -- the platform answers it both for "there
 * is no document for that day" and for other complaints -- so the text beside it decides, and is then
 * reduced to one of these labels. A label and the numeric code are all that ever reach a message: the
 * text is response body, and a body can echo the request, key and all.
 */
internal enum class AckKind(val label: String) {
    /** A recognized "no matching data found": the request was understood and there is no document yet. */
    NO_DATA("no matching data"),

    /** A credential or permission complaint, which no retry fixes. */
    AUTH("authentication or permission problem"),

    /** The platform asking for less traffic. */
    RATE_LIMIT("rate limited"),

    /** A request the platform could not make sense of. */
    BAD_REQUEST("request rejected"),

    /** Everything else, including a 999 whose text is not recognized: a failure, never "not published". */
    UNKNOWN("unrecognized acknowledgement")
}

/** The platform's other envelope, `Acknowledgement_MarketDocument`: its reasons as (code, text). */
internal class EntsoeAck(private val reasons: List<Pair<String, String>>) {
    /**
     * The meaning, from the code, the text and the status it arrived with. The precedence is the relay's
     * (`entsoe.go` `kind`): authentication over rate limit over bad request; "no data" only when every
     * reason with a text says no matching data and the status is one such an answer arrives with.
     */
    fun kind(status: Int): AckKind {
        if (reasonCode().trim() != NO_DATA_CODE) return AckKind.UNKNOWN
        val text = normalize(reasons.joinToString(" ") { it.second })
        return when {
            containsAny(text, AUTH_PHRASES) -> AckKind.AUTH
            containsAny(text, RATE_LIMIT_PHRASES) -> AckKind.RATE_LIMIT
            containsAny(text, BAD_REQUEST_PHRASES) -> AckKind.BAD_REQUEST
            noDataOnly() && (status == 200 || status == 400) -> AckKind.NO_DATA
            else -> AckKind.UNKNOWN
        }
    }

    /** The first reason's code, for a message; `none` when no reason states one. */
    fun reasonCode(): String = reasons.map { it.first.trim() }.firstOrNull { it.isNotEmpty() } ?: "none"

    private fun noDataOnly(): Boolean {
        var said = false
        for ((_, raw) in reasons) {
            val text = normalize(raw)
            if (text.isEmpty()) continue
            if (!containsAny(text, NO_DATA_PHRASES)) return false
            said = true
        }
        return said
    }

    companion object {
        private const val NO_DATA_CODE = "999"

        // The phrases the relay recognizes, in normalized text. Deliberately few and specific: a list that
        // guessed would classify a real failure as "no data", the one outcome that stops anybody looking.
        private val NO_DATA_PHRASES = listOf("no matching data")
        private val AUTH_PHRASES = listOf(
            "securitytoken", "security token", "token is not valid", "invalid token",
            "authentication", "authenticated", "not authorized", "unauthorized",
            "permission", "access denied", "not allowed"
        )
        private val RATE_LIMIT_PHRASES = listOf("rate limit", "too many requests", "quota")
        private val BAD_REQUEST_PHRASES = listOf(
            "malformed", "not a valid", "invalid parameter", "missing parameter",
            "not supported", "unsupported", "out of range", "outside the range"
        )

        private val WHITESPACE = Regex("""\s+""")

        private fun normalize(text: String): String = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            .joinToString(" ").lowercase()

        private fun containsAny(text: String, phrases: List<String>): Boolean = phrases.any { it in text }

        /** The acknowledgement in [body], or `null` when the body is not one. */
        fun parse(body: String): EntsoeAck? {
            val root = SafeXml.root(body) ?: return null
            if (SafeXml.localName(root) != "Acknowledgement_MarketDocument") return null
            val reasons = SafeXml.children(root, "Reason").map { reason ->
                (SafeXml.childText(reason, "code") ?: "") to (SafeXml.childText(reason, "text") ?: "")
            }
            return EntsoeAck(reasons)
        }
    }
}
