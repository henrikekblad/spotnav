package se.sensnology.spotnav.ha.client

/**
 * A charger's webhook that Home Assistant no longer has. Home Assistant answers a POST to a webhook id
 * nobody registered with HTTP 200 and an *empty* body, which is nothing any SpotNav action answers
 * with: every one of them answers a JSON object. One empty answer may still be a proxy's hiccup, so
 * it is only *suspected* until [confirm] says otherwise.
 */
internal object WebhookGone {
    /** What one request to the charger's webhook came back with, as far as this decision cares. */
    enum class Answer {
        /** 2xx with nothing in the body: what an unknown webhook id gets. */
        EMPTY,

        /** Anything with a body, or any status Home Assistant chose: the webhook is there. */
        ANSWERED,

        /** No HTTP answer at all: offline, refused, timed out. Says nothing about the webhook. */
        NO_ANSWER
    }

    /** How long to wait before asking again after the first empty answer. */
    const val RETRY_WAIT_MS = 2_000L

    /**
     * Whether an empty answer means the webhook is gone: after [wait], [retry] asks the same webhook
     * once more (a read that changes nothing), and only a second empty answer counts. Then [probe]
     * asks the instance's pairing webhook, which is always there while SpotNav runs in it: an
     * instance that answers that one is reachable and running SpotNav, so it is this phone's webhook
     * that is missing -- not the network, and not the integration.
     */
    fun confirm(retry: () -> Answer, probe: () -> Boolean, wait: () -> Unit = { Thread.sleep(RETRY_WAIT_MS) }): Boolean {
        wait()
        if (retry() != Answer.EMPTY) return false
        return probe()
    }

    /** Whether [failure] (a read's or a write's) is a webhook that is confirmed gone. */
    fun isGone(failure: Throwable?): Boolean = failure is WebhookGoneException
}

/**
 * An empty answer from a charger's webhook that was asked again, and the instance's pairing webhook
 * answered: SpotNav in that Home Assistant no longer knows this webhook id. The message never carries
 * the id.
 */
internal class WebhookGoneException : IllegalStateException("Home Assistant no longer recognises this phone")

/**
 * An empty answer that could not be confirmed as a webhook that is gone (the second ask answered, or
 * the instance's pairing webhook did not): an ordinary Home Assistant error, worded without a JSON
 * parser's message.
 */
internal class EmptyWebhookAnswerException : IllegalStateException("empty answer")
