package se.sensnology.spotnav.ha.client

import java.net.URI

data class HomeAssistantSettings(
    val baseUrl: String = "",
    val webhookId: String = ""
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && webhookId.isNotBlank()

    fun webhookUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return "$base/api/webhook/${webhookId.trim()}"
    }

    companion object {
        fun isAllowedBaseUrl(value: String): Boolean {
            val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
            val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
            if (uri.scheme.equals("https", ignoreCase = true)) return true
            if (!uri.scheme.equals("http", ignoreCase = true)) return false
            if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan") || '.' !in host) return true
            val ipv4 = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { part -> part in 0..255 } }
            if (ipv4.size == 4) return ipv4[0] == 10 || ipv4[0] == 127 ||
                (ipv4[0] == 172 && ipv4[1] in 16..31) ||
                (ipv4[0] == 192 && ipv4[1] == 168) ||
                (ipv4[0] == 169 && ipv4[1] == 254) ||
                (ipv4[0] == 100 && ipv4[1] in 64..127)
            return host.startsWith("fc") || host.startsWith("fd") || host.matches(Regex("fe[89ab].*"))
        }

        /** Home Assistant's own port, tried first when a typed address names none. */
        const val DEFAULT_PORT = 8123

        /**
         * The addresses worth trying for what a person typed, most likely first, each one allowed by
         * [isAllowedBaseUrl]. A bare host or IP gets http and https, on Home Assistant's port before the
         * scheme's own; a typed scheme is kept; a typed port is kept.
         */
        fun addressCandidates(typed: String): List<String> {
            val text = typed.trim().trimEnd('/')
            if (text.isEmpty()) return emptyList()
            val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(text)
            val schemes = if (hasScheme) listOf(text.substringBefore("://").lowercase()) else listOf("http", "https")
            val rest = if (hasScheme) text.substringAfter("://") else text
            val authority = rest.substringBefore('/')
            val path = rest.substring(authority.length)
            val hasPort = Regex(":\\d+$").containsMatchIn(authority) && !authority.endsWith("]")
            val candidates = if (hasPort) {
                schemes.map { "$it://$authority$path" }
            } else {
                schemes.map { "$it://$authority:$DEFAULT_PORT$path" } + schemes.reversed().map { "$it://$authority$path" }
            }
            return candidates.distinct().filter { isAllowedBaseUrl(it) }
        }
    }
}
