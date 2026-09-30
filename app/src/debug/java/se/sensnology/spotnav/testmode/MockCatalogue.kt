package se.sensnology.spotnav.testmode

import org.json.JSONObject
import se.sensnology.spotnav.app.strictText
import se.sensnology.spotnav.ha.client.HomeAssistantSettings

/** The mock Home Assistant's catalogue — what `tools/fake_ha.py` answers with. */
object MockCatalogue {
    /** The only `service` value this app will believe. */
    const val SERVICE = "spotnav-mock"

    /** What a debug build broadcasts to find the mock, byte for byte. */
    const val DISCOVERY_PROBE = "SPOTNAV-MOCK-DISCOVER"

    /** The UDP port the mock listens on. */
    const val DISCOVERY_PORT = 8099

    /** The HTTP path that serves the same catalogue, for a typed-in address. */
    const val CATALOGUE_PATH = "/spotnav-mock/scenarios"

    /** One scenario a mock server can be. [why] says which screen it is for. */
    data class Scenario(val webhookId: String, val name: String, val why: String)

    /** One mock server, and the scenarios it offers. */
    data class Server(val baseUrl: String, val scenarios: List<Scenario>)

    /**
     * A discovery reply, if it is ours and usable: the service name matches, the payload carries a
     * `base_url` that passes [isAllowedBaseUrl], and the scenarios that survive parsing are
     * offered.
     */
    fun serverFromReply(payload: String, isAllowedBaseUrl: (String) -> Boolean): Server? {
        val json = asJsonObject(payload) ?: return null
        if (json.strictText("service") != SERVICE) return null
        val baseUrl = json.strictText("base_url") ?: return null
        if (!isAllowedBaseUrl(baseUrl)) return null
        return Server(baseUrl, scenarios(json))
    }

    /**
     * The catalogue body `GET [CATALOGUE_PATH]` returns, read against the address the user typed
     * rather than any `base_url` inside it: the reply's own copy is derived from a `Host` header
     * and is worth nothing here.
     */
    fun scenariosFromCatalogue(payload: String, baseUrl: String, isAllowedBaseUrl: (String) -> Boolean): List<Scenario>? {
        if (!isAllowedBaseUrl(baseUrl)) return null
        val json = asJsonObject(payload) ?: return null
        if (json.strictText("service") != SERVICE) return null
        return scenarios(json)
    }

    /** The address a typed-in server must be asked for its catalogue. */
    fun catalogueUrl(baseUrl: String): String = baseUrl.trimEnd('/') + CATALOGUE_PATH

    /**
     * Anything JSON *object*-shaped, and nothing else: a body that is an array, a number, a bare
     * string or not JSON at all is not this catalogue, and an exception is not a way to find that
     * out.
     */
    private fun asJsonObject(payload: String): JSONObject? =
        runCatching { JSONObject(payload) }.getOrNull()

    /** The scenarios in [json], each of which must at least carry an address. */
    private fun scenarios(json: JSONObject): List<Scenario> {
        val array = json.optJSONArray("scenarios") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val scenario = array.optJSONObject(index) ?: return@mapNotNull null
            val webhookId = scenario.strictText("webhook_id").orEmpty()
            if (webhookId.isEmpty()) return@mapNotNull null
            Scenario(
                webhookId = webhookId,
                name = scenario.strictText("name") ?: webhookId,
                why = scenario.strictText("why").orEmpty()
            )
        }
    }
}
