package se.sensnology.spotnav.vehicles

import org.json.JSONObject
import se.sensnology.spotnav.app.strictBoolean
import se.sensnology.spotnav.app.strictPositiveInt
import se.sensnology.spotnav.chargers.ChargerCapabilities

/** Setting the car's own charge limit, from the vehicle card. */
object ChargeLimit {
    /**
     * Whether the card offers the limit control at all: a literal capability **and** a live limit
     * to write to: the integration refuses a write with no readable limit.
     */
    fun offered(capabilities: ChargerCapabilities?, vehicle: VehicleStatus?): Boolean =
        capabilities?.setChargeLimit == true && vehicle?.let { VehicleFacts.chargeLimit(it) } != null

    /** What one answer from the integration means. */
    sealed interface Answer {
        /** 200: Home Assistant has written the value. Not "the car has it". */
        data object Set : Answer

        /** 429: well-formed, and this vehicle's limit was written under a minute ago. */
        data class TooSoon(val retryAfterS: Int?) : Answer

        /**
         * Anything else: 400 (the vehicle is not one this instance reports, or the value is outside
         * what the limit accepts — and the integration deliberately does not say which), another
         * status, or no HTTP answer at all.
         */
        data object Failed : Answer
    }

    /**
     * What the card has to *say* about an answer, if anything: a refusal with how long it lasts, or
     * a failure.
     */
    data class Message(val kind: Kind, val retryAfterS: Int?)

    /** What there is to say. */
    enum class Kind { TOO_SOON, FAILED }

    /**
     * What the limit row is, for a caller that only knows how to draw: whether it opens a control
     * at all, and whether it can be pressed.
     */
    data class Control(val visible: Boolean, val enabled: Boolean)

    /**
     * The control's state, from the two things that shape it: whether [offered] allows it, and
     * whether a write is in flight.
     */
    fun control(offered: Boolean, inFlight: Boolean): Control =
        Control(visible = offered, enabled = offered && !inFlight)

    /** What [answer] has to be said about, or `null` when it speaks for itself. */
    fun message(answer: Answer?): Message? = when (answer) {
        null, Answer.Set -> null
        is Answer.TooSoon -> Message(Kind.TOO_SOON, answer.retryAfterS)
        Answer.Failed -> Message(Kind.FAILED, null)
    }

    /** What one answer to the exchange means. */
    fun answer(status: Int?, bodyText: String?): Answer {
        val body = bodyText?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
        return when (status) {
            200 -> if (body?.strictBoolean("ok") == true) Answer.Set else Answer.Failed
            429 -> Answer.TooSoon(body?.strictPositiveInt("retry_after_s"))
            else -> Answer.Failed
        }
    }
}

/**
 * The percents the car's own charge limit can be written to, as the vehicle row states them
 * (`charge_limit_range`): [min]..[max] in steps of [step], the limit entity's own.
 */
data class ChargeLimitRange(val min: Double, val max: Double, val step: Double) {
    companion object {
        /** `{"min", "max", "step"}` with `0 < min < max <= 100` and a positive step, else `null` (unknown). */
        fun parse(raw: Any?): ChargeLimitRange? {
            val json = raw as? JSONObject ?: return null
            fun number(key: String): Double? =
                (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }
            val min = number("min") ?: return null
            val max = number("max") ?: return null
            val step = number("step") ?: return null
            return if (min < max) ChargeLimitRange(min, max, step) else null
        }
    }
}
