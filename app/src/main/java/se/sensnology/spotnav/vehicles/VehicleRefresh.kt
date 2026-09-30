package se.sensnology.spotnav.vehicles

import org.json.JSONObject
import se.sensnology.spotnav.app.strictBoolean
import se.sensnology.spotnav.app.strictPositiveInt
import se.sensnology.spotnav.chargers.ChargerCapabilities

/** Asking Home Assistant to re-read one vehicle's own entities, from the vehicle card. */
object VehicleRefresh {
    /**
     * Whether the card offers the re-read control at all: **only on a literal `true`**. See the
     * class doc.
     */
    fun offered(capabilities: ChargerCapabilities?): Boolean = capabilities?.refreshVehicle == true

    /** What one answer from the integration means. */
    sealed interface Answer {
        /** 200: the re-read has happened, so a `status` fetch now sees it. */
        data object Refreshed : Answer

        /** 429: well-formed, and this vehicle was refreshed under a minute ago. */
        data class TooSoon(val retryAfterS: Int?) : Answer

        /**
         * Anything else: 400 (the vehicle is not one this instance reports, and the integration
         * deliberately does not say which of several causes it was), another status, or no HTTP
         * answer at all.
         */
        data object Failed : Answer
    }

    /**
     * What the card has to *say* about an answer, if anything: a refusal with how long it lasts, or
     * a failure. A success says nothing.
     */
    data class Message(val kind: Kind, val retryAfterS: Int?)

    /** What there is to say. */
    enum class Kind { TOO_SOON, FAILED }

    /**
     * What the card's control is, for a caller that only knows how to draw: whether it is there,
     * and whether it can be pressed. The spin is the caller's own in-flight flag, which it already
     * holds.
     */
    data class Control(val visible: Boolean, val enabled: Boolean)

    /**
     * The control's state, from the three things that shape it: whether the capability allows it,
     * whether there is a selected vehicle to be about, and whether a request is in flight.
     */
    fun control(offered: Boolean, hasSelectedVehicle: Boolean, inFlight: Boolean): Control = Control(
        visible = offered,
        enabled = offered && hasSelectedVehicle && !inFlight
    )

    /** What [answer] has to be said about, or `null` when it speaks for itself. */
    fun message(answer: Answer?): Message? = when (answer) {
        null, Answer.Refreshed -> null
        is Answer.TooSoon -> Message(Kind.TOO_SOON, answer.retryAfterS)
        Answer.Failed -> Message(Kind.FAILED, null)
    }

    /**
     * What one answer to the exchange means. [status] is `null` when no HTTP answer arrived at all,
     * and [bodyText] is the raw body, parsed here rather than by the transport so the reading of it
     * is one tested decision.
     */
    fun answer(status: Int?, bodyText: String?): Answer {
        val body = bodyText?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
        return when (status) {
            200 -> if (body?.strictBoolean("ok") == true) Answer.Refreshed else Answer.Failed
            429 -> Answer.TooSoon(body?.strictPositiveInt("retry_after_s"))
            else -> Answer.Failed
        }
    }
}
