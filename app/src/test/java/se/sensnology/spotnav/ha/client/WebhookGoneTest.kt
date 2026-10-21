package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/** An empty answer from a charger's webhook: when it means the webhook is gone, and when it does not. */
class WebhookGoneTest {
    private fun confirm(retry: WebhookGone.Answer, instance: Boolean = true): Pair<Boolean, Int> {
        var probes = 0
        val gone = WebhookGone.confirm(retry = { retry }, probe = { probes += 1; instance }, wait = {})
        return gone to probes
    }

    @Test fun anEmptyAnswerTwiceFromAnInstanceThatAnswersIsAWebhookThatIsGone() {
        assertEquals(true to 1, confirm(WebhookGone.Answer.EMPTY))
    }

    @Test fun anEmptyAnswerOnceThenAnAnswerIsNotGone() {
        // A proxy's hiccup: the second ask answered, so the instance is not even asked.
        assertEquals(false to 0, confirm(WebhookGone.Answer.ANSWERED))
    }

    @Test fun noAnswerOnTheSecondAskIsNotGone() {
        assertEquals(false to 0, confirm(WebhookGone.Answer.NO_ANSWER))
    }

    @Test fun anInstanceThatDoesNotAnswerItsPairingWebhookIsNotGone() {
        // SpotNav not running there (or Home Assistant still starting): not this phone's webhook.
        assertEquals(false to 1, confirm(WebhookGone.Answer.EMPTY, instance = false))
    }

    @Test fun theSecondAskWaitsFirst() {
        val order = mutableListOf<String>()
        WebhookGone.confirm(
            retry = { order += "retry"; WebhookGone.Answer.EMPTY },
            probe = { order += "probe"; true },
            wait = { order += "wait" }
        )
        assertEquals(listOf("wait", "retry", "probe"), order)
    }

    @Test fun onlyAConfirmedGoneWebhookCountsAsGone() {
        assertTrue(WebhookGone.isGone(WebhookGoneException()))
        assertFalse(WebhookGone.isGone(EmptyWebhookAnswerException()))
        assertFalse(WebhookGone.isGone(IOException("offline")))
        assertFalse(WebhookGone.isGone(null))
    }

    // ---- Over HTTP: the client's own reads, against a server that answers like Home Assistant.

    private val instanceAnswers = HomeAssistantClient.instanceAnswers
    private val retryWait = HomeAssistantClient.retryWaitMs

    @After fun restore() {
        HomeAssistantClient.instanceAnswers = instanceAnswers
        HomeAssistantClient.retryWaitMs = retryWait
    }

    /**
     * A server answering each request with the next of [answers] (a body; "" is Home Assistant's empty
     * 200 for a webhook it does not have), and the request bodies it received.
     */
    private fun <T> withAnswers(vararg answers: String, call: (HomeAssistantSettings) -> T): Pair<Result<T>, List<JSONObject>> {
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = ServerSocket(0, answers.size, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            for (body in answers) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) break
                            head.append(b.toChar())
                        }
                        val length = Regex("(?i)content-length: *(\\d+)").find(head)!!.groupValues[1].toInt()
                        received.add(String(input.readNBytes(length), Charsets.UTF_8))
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray() + bytes
                        )
                    }
                }
            }
        }
        worker.isDaemon = true
        worker.start()
        HomeAssistantClient.retryWaitMs = 0
        try {
            val result = runCatching { call(HomeAssistantSettings("http://127.0.0.1:${server.localPort}", "hook")) }
            return result to received.map { JSONObject(it) }
        } finally {
            server.close()
        }
    }

    @Test fun aDashboardReadAnsweredEmptyTwiceThrowsGoneAfterAskingTheInstance() {
        var asked = 0
        HomeAssistantClient.instanceAnswers = { asked += 1; true }
        val (result, sent) = withAnswers("", "") { HomeAssistantClient.dashboard(it) }

        assertTrue(result.exceptionOrNull() is WebhookGoneException)
        assertEquals(2, sent.size)
        assertEquals(1, asked)
    }

    @Test fun anEmptyAnswerFollowedByAnAnswerIsAnOrdinaryErrorNotGone() {
        HomeAssistantClient.instanceAnswers = { error("not asked when the second ask answered") }
        val (result, sent) = withAnswers("", "{}") { HomeAssistantClient.dashboard(it) }

        assertTrue(result.exceptionOrNull() is EmptyWebhookAnswerException)
        assertEquals(2, sent.size)
    }

    @Test fun aWriteAnsweredEmptyIsAskedAgainWithAReadNeverTheWriteItself() {
        HomeAssistantClient.instanceAnswers = { true }
        val (_, sent) = withAnswers("", "") { HomeAssistantClient.send(it, HomeAssistantCommand("start")) }

        assertEquals(listOf("start", "dashboard"), sent.map { it.getString("action") })
    }

    @Test fun anEmptyAnswerTwiceFromAnInstanceWithoutSpotNavIsNotGone() {
        HomeAssistantClient.instanceAnswers = { false }
        val (result, _) = withAnswers("", "") { HomeAssistantClient.dashboard(it) }

        assertTrue(result.exceptionOrNull() is EmptyWebhookAnswerException)
    }

    @Test fun anInstanceThatIsOfflineIsNeverGone() {
        HomeAssistantClient.instanceAnswers = { error("never asked when nothing answered") }
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val failure = runCatching { HomeAssistantClient.dashboard(HomeAssistantSettings("http://127.0.0.1:$closed", "hook")) }
            .exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(WebhookGone.isGone(failure))
    }
}
