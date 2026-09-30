package se.sensnology.spotnav.ha.pairing

import org.json.JSONObject
import se.sensnology.spotnav.app.strictBoolean
import se.sensnology.spotnav.app.strictText
import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import java.security.SecureRandom

/** One charger an approved pairing handed over, with the secret to control it. */
data class PairedCharger(val id: String, val name: String, val webhookId: String)

/** What one `poll` answer means. */
sealed interface PairingPoll {
    /** Nothing decided yet: keep asking. */
    data object Pending : PairingPoll

    /**
     * A person approved it in Home Assistant. [baseUrl] has already passed
     * [HomeAssistantSettings.isAllowedBaseUrl]. [chargers] may legitimately be empty -- an instance
     * with no chargers is a state, not a failure.
     */
    data class Approved(val baseUrl: String, val chargers: List<PairedCharger>) : PairingPoll

    /** A person declined it. */
    data object Denied : PairingPoll

    /** Nobody answered in time, on Home Assistant's side. */
    data object Expired : PairingPoll

    /**
     * No answer at all: a timeout, a dropped connection, a server error or an empty body. Unlike
     * [Unusable] this says nothing about what Home Assistant thinks, so the loop asks again.
     */
    data object Unreachable : PairingPoll

    /**
     * Not an answer this app can act on: not JSON, not an object, an unknown status, an approved
     * answer with no usable address, or one whose fields have the wrong types.
     */
    data object Unusable : PairingPoll
}

/** What the pairing loop does after one answer. */
sealed interface PairingStep {
    /** Ask again after [delayMillis]. */
    data class KeepPolling(val delayMillis: Long) : PairingStep

    /** Stop asking, for this reason. */
    data class Stop(val reason: PairingStop) : PairingStep
}

/** Why the pairing loop stopped. */
sealed interface PairingStop {
    data class Approved(val baseUrl: String, val chargers: List<PairedCharger>) : PairingStop
    data object Denied : PairingStop
    data object Expired : PairingStop
    /** Five minutes of asking, and nobody approved. */
    data object TimedOut : PairingStop
    /** Polls kept failing for [PairingProtocol.UNREACHABLE_LIMIT_MS] in a row. */
    data object Unreachable : PairingStop
    data object Unusable : PairingStop
}

/**
 * The one handshake this app has with Home Assistant: a code a person compares, and an approval
 * that happens in Home Assistant.
 */
object PairingProtocol {
    /** The fixed, public webhook id the whole handshake is posted to. */
    const val WEBHOOK_ID = "spotnav_pairing"

    /**
     * How many digits the code has. Six, so it is short enough to compare and long enough that
     * guessing one is a million-to-one against a request that has to be approved by a person within
     * five minutes anyway.
     */
    const val CODE_LENGTH = 6

    /** How long to wait between polls. */
    const val POLL_INTERVAL_MS = 2_000L

    /** How long polls may fail back to back before the pairing is given up. */
    const val UNREACHABLE_LIMIT_MS = 60_000L

    /** How long to keep asking before giving up and saying so. */
    const val POLL_TIMEOUT_MS = 5 * 60 * 1_000L

    /** The `request` body: who is asking, and the code they are showing. */
    fun requestPayload(device: String, code: String): String = JSONObject().apply {
        put("version", 1)
        put("action", "request")
        put("device", device)
        put("code", code)
    }.toString()

    /** The `poll` body: which request is being asked about. */
    fun pollPayload(requestId: String): String = JSONObject().apply {
        put("version", 1)
        put("action", "poll")
        put("request_id", requestId)
    }.toString()

    /** A fresh code: exactly [CODE_LENGTH] digits, from [SecureRandom]. */
    fun newCode(random: SecureRandom = SecureRandom()): String =
        (0 until CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }

    /**
     * The `request_id` from a `request` answer, or `null` when the answer is not one this app can
     * poll with: not a JSON object, `ok` not literally `true`, or no usable `request_id`.
     */
    fun requestId(payload: String): String? {
        val json = asJsonObject(payload) ?: return null
        if (json.strictBoolean("ok") != true) return null
        return json.strictText("request_id")
    }

    /**
     * What a `poll` answer means, with every field read as genuinely what it claims to be. A `null`
     * [payload] is no answer at all ([PairingPoll.Unreachable]).
     */
    fun poll(payload: String?, isAllowedBaseUrl: (String) -> Boolean): PairingPoll {
        if (payload == null) return PairingPoll.Unreachable
        val json = asJsonObject(payload) ?: return PairingPoll.Unusable
        return when (json.strictText("status")) {
            "pending" -> PairingPoll.Pending
            "denied" -> PairingPoll.Denied
            "expired" -> PairingPoll.Expired
            "approved" -> approved(json, isAllowedBaseUrl)
            else -> PairingPoll.Unusable
        }
    }

    private fun approved(json: JSONObject, isAllowedBaseUrl: (String) -> Boolean): PairingPoll {
        val baseUrl = json.strictText("url") ?: return PairingPoll.Unusable
        if (!isAllowedBaseUrl(baseUrl)) return PairingPoll.Unusable
        val array = json.opt("chargers") as? org.json.JSONArray
        val chargers = array?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                charger((list.opt(index) as? JSONObject) ?: return@mapNotNull null)
            }
        }.orEmpty()
        return PairingPoll.Approved(baseUrl, chargers)
    }

    /**
     * One element of an approved answer's `chargers`, or `null` when it is not a charger this app
     * can hold: `id` and `webhook` are both required, since without either there is nothing to
     * address and no way to control it.
     */
    private fun charger(json: JSONObject): PairedCharger? {
        val id = json.strictText("id") ?: return null
        val webhookId = json.strictText("webhook") ?: return null
        return PairedCharger(id = id, name = json.strictText("name") ?: id, webhookId = webhookId)
    }

    /**
     * What to do after a `poll` answer that took [elapsedMillis] so far. [failingForMillis] is how
     * long the polls have been failing without a break, counting this one.
     */
    fun next(elapsedMillis: Long, poll: PairingPoll, failingForMillis: Long = 0L): PairingStep {
        if (poll is PairingPoll.Unreachable) {
            if (failingForMillis >= UNREACHABLE_LIMIT_MS) return PairingStep.Stop(PairingStop.Unreachable)
        } else if (poll !is PairingPoll.Pending) {
            return PairingStep.Stop(stopReason(poll))
        }
        if (timedOut(elapsedMillis)) return PairingStep.Stop(PairingStop.TimedOut)
        return PairingStep.KeepPolling(POLL_INTERVAL_MS)
    }

    /** Whether the pairing has been running for too long to keep asking. */
    fun timedOut(elapsedMillis: Long): Boolean = elapsedMillis >= POLL_TIMEOUT_MS

    private fun stopReason(poll: PairingPoll): PairingStop = when (poll) {
        is PairingPoll.Approved -> PairingStop.Approved(poll.baseUrl, poll.chargers)
        PairingPoll.Denied -> PairingStop.Denied
        PairingPoll.Expired -> PairingStop.Expired
        PairingPoll.Pending -> error("pending is not a reason to stop")
        PairingPoll.Unusable -> PairingStop.Unusable
        PairingPoll.Unreachable -> error("unreachable is not decided here")
    }

    /** Anything JSON *object*-shaped, and nothing else. */
    private fun asJsonObject(payload: String): JSONObject? =
        runCatching { JSONObject(payload) }.getOrNull()
}
