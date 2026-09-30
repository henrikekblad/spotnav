package se.sensnology.spotnav.ha.pairing

import se.sensnology.spotnav.ha.client.HomeAssistantSettings
import java.net.HttpURLConnection
import java.net.URL

/** The three calls of the pairing handshake, over HTTP. */
internal object HomeAssistantPairingClient {
    /**
     * Ask to be paired and return the request id to poll with, or `null` when Home Assistant
     * refused or answered something unreadable. The address is checked first: it also decides where
     * the webhook ids in an approval would be sent.
     */
    fun request(baseUrl: String, device: String, code: String): String? {
        if (!HomeAssistantSettings.isAllowedBaseUrl(baseUrl)) return null
        val body = PairingProtocol.requestPayload(device, code)
        return PairingProtocol.requestId(post(baseUrl, body).orEmpty())
    }

    /**
     * Ask until there is an answer, a refusal, or the protocol's five minutes are up. Returns the
     * reason the loop stopped, or `null` when [isCancelled] asked it to.
     */
    fun awaitApproval(
        baseUrl: String,
        requestId: String,
        isCancelled: () -> Boolean = { false }
    ): PairingStop? {
        val startedAt = System.currentTimeMillis()
        var failingSince: Long? = null
        while (true) {
            if (isCancelled()) return null
            val elapsed = System.currentTimeMillis() - startedAt
            val poll = PairingProtocol.poll(post(baseUrl, PairingProtocol.pollPayload(requestId))) {
                HomeAssistantSettings.isAllowedBaseUrl(it)
            }
            val now = System.currentTimeMillis()
            if (poll is PairingPoll.Unreachable) {
                if (failingSince == null) failingSince = now
            } else {
                failingSince = null
            }
            val failingFor = failingSince?.let { now - it } ?: 0L
            when (val step = PairingProtocol.next(now - startedAt, poll, failingFor)) {
                is PairingStep.Stop -> return step.reason
                is PairingStep.KeepPolling -> {
                    if (waitOrCancel(step.delayMillis, isCancelled)) return null
                }
            }
        }
    }

    /** Sleep, unless the caller cancelled part-way through. `true` when cancelled. */
    private fun waitOrCancel(delayMillis: Long, isCancelled: () -> Boolean): Boolean {
        val step = 100L
        var slept = 0L
        while (slept < delayMillis) {
            if (isCancelled()) return true
            val nap = minOf(step, delayMillis - slept)
            runCatching { Thread.sleep(nap) }
            slept += nap
        }
        return false
    }

    /**
     * One POST, returning the response body as text, or `null` when there was no usable response:
     * an exception, a server error or an empty body.
     */
    private fun post(baseUrl: String, body: String): String? {
        val connection = URL(baseUrl.trimEnd('/') + "/api/webhook/" + PairingProtocol.WEBHOOK_ID)
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status >= 500) return null
            (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }?.takeIf { it.isNotBlank() }
        } catch (failure: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
