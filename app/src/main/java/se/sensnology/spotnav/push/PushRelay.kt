package se.sensnology.spotnav.push

import org.json.JSONObject
import se.sensnology.spotnav.prices.RelayHttp

/**
 * SpotNav Relay's `POST /v1/push/register`: the push token goes in, an opaque `push_ref` comes out
 * (the token encrypted with the relay's own key; the relay stores nothing). Home Assistant is only
 * ever given the reference.
 */
internal object PushRelay {
    const val REGISTER_URL = "https://spotnav.sensnology.se/v1/push/register"

    sealed interface Outcome {
        data class Registered(val pushRef: String) : Outcome

        /** The relay has no Firebase credentials (503 `push_disabled`). */
        data object Disabled : Outcome

        /** Too many registrations from here (429). */
        data object RateLimited : Outcome

        /** No answer, a refusal or an answer this app cannot read. */
        data object Failed : Outcome
    }

    fun payload(token: String): String = JSONObject().put("v", 1).put("fcm_token", token).toString()

    fun answer(status: Int?, body: String?): Outcome {
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        return when (status) {
            200 -> {
                val ref = json?.opt("push_ref") as? String
                if (json?.opt("v") == 1 && !ref.isNullOrBlank()) Outcome.Registered(ref) else Outcome.Failed
            }
            429 -> Outcome.RateLimited
            503 -> if (json?.opt("error") == "push_disabled") Outcome.Disabled else Outcome.Failed
            else -> Outcome.Failed
        }
    }

    /** Register [token] at the relay. Blocks; never throws. */
    fun register(token: String): Outcome {
        val (status, body) = RelayHttp.post(REGISTER_URL, payload(token))
        return answer(status, body)
    }
}
