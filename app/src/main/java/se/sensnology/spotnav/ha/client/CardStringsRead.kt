package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import se.sensnology.spotnav.app.strictBoolean
import se.sensnology.spotnav.app.strictText

/**
 * The card's words in one language as Home Assistant serves them (webhook `get_strings`, SpotNav 3.0): the card's own
 * built language file, so a wording fixed in Home Assistant reaches the app at the next connection. Pure: the client
 * hands over the status, the body and whether the request failed. What the app does with the words is
 * [se.sensnology.spotnav.ui.common.CardText]'s.
 */
internal object CardStringsRead {
    sealed interface Outcome {
        /** The words of [language] (the one Home Assistant served: English for a language it has none of), [hash] theirs. */
        data class Words(val language: String, val hash: String, val words: Map<String, String>) : Outcome

        /** The words held under [hash] are still the current ones. */
        data class Unchanged(val hash: String) : Outcome

        /** A Home Assistant without `get_strings` (400 "Unsupported action"), or any other refusal: keep what is held. */
        data object Unsupported : Outcome

        /** No answer, or one to try again later (the home not loaded, a server error). */
        data object Later : Outcome
    }

    const val ACTION = "get_strings"

    fun payload(language: String, knownHash: String?): JSONObject = JSONObject()
        .put("version", 1).put("action", ACTION).put("api_version", 1).put("language", language)
        .apply { if (knownHash != null) put("known_hash", knownHash) }

    fun answer(status: Int?, body: String?, failure: Throwable?): Outcome {
        if (failure != null || status == null) return Outcome.Later
        if (status in 500..599) return Outcome.Later
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Outcome.Unsupported
        if (status != 200 || json.strictBoolean("ok") != true) return Outcome.Unsupported
        val hash = json.strictText("hash") ?: return Outcome.Unsupported
        if (json.strictBoolean("unchanged") == true) return Outcome.Unchanged(hash)
        val language = json.strictText("language") ?: return Outcome.Unsupported
        val strings = json.optJSONObject("strings") ?: return Outcome.Unsupported
        val words = LinkedHashMap<String, String>(strings.length())
        for (key in strings.keys()) (strings.opt(key) as? String)?.let { words[key] = it }
        return Outcome.Words(language, hash, words)
    }
}
