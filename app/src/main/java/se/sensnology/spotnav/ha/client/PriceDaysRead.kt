package se.sensnology.spotnav.ha.client

import org.json.JSONObject
import se.sensnology.spotnav.prices.DayParse
import se.sensnology.spotnav.prices.PriceMarket
import se.sensnology.spotnav.prices.RelayAreasParser
import se.sensnology.spotnav.prices.RelayDayParser
import java.time.LocalDate

/**
 * One `price_days` answer as the app reads it: the charger's [area] as Home Assistant prices it (its
 * market calendar, currency and publication come from the provider Home Assistant uses now), and the
 * market-day [files] it holds, each a `"v":1` document that [RelayDayParser] accepted for that area.
 *
 * `profile` is not read: the app plans locally only while unpaired (design 2.1).
 */
internal data class PriceDaysRead(
    val area: PriceMarket,
    /** The provider's key (`relay`, `nordpool`, ...), or `null` when the answer did not state one. */
    val providerKey: String?,
    /** Market date to its document's text, in date order. */
    val files: Map<LocalDate, String>
)

/** What one `price_days` request came to. */
internal sealed interface PriceDaysAnswer {
    /** A readable answer, and the [body] it was read from (the store keeps that text whole). */
    data class Answered(val read: PriceDaysRead, val body: String) : PriceDaysAnswer

    /** Home Assistant refused with a stable code (`spotnav_no_prices`, `spotnav_no_price_area`, ...). */
    data class Refused(val code: String?) : PriceDaysAnswer

    /** No usable answer: unreachable, a status that is not a refusal, an unreadable body, a webhook gone. */
    data object Failed : PriceDaysAnswer
}

/** The decoder of the webhook's `price_days` answer (`docs/api.md`, "Prices for the app"). */
internal object PriceDaysDecoder {
    const val ACTION = "price_days"
    const val VERSION = 1

    /**
     * The answer in [body], or `null` when it is not one this app can use: not `ok`, another version, or
     * an area the v2 area parser refuses. One file that does not validate is dropped, never the answer.
     * Unknown keys are ignored.
     */
    fun decode(body: String): PriceDaysRead? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.opt("ok") != true) return null
        if ((json.opt("v") as? Int) != VERSION) return null
        val entry = json.optJSONObject("area") ?: return null
        val area = RelayAreasParser.parseArea(entry)?.let(PriceMarket::of) ?: return null
        val files = sortedMapOf<LocalDate, String>()
        json.optJSONObject("files")?.let { held ->
            for (key in held.keys()) {
                val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                val document = held.optJSONObject(key)?.toString() ?: continue
                val parsed = RelayDayParser.parse(document, area.id, area.marketTz, area.currency, key)
                if (parsed is DayParse.Ok) files[date] = document
            }
        }
        val providerKey = json.optJSONObject("provider")?.opt("key") as? String
        return PriceDaysRead(area, providerKey?.takeIf { it.isNotBlank() }, files)
    }

    /** What a request's (status, body) came to; `null` for both is no HTTP answer at all. */
    fun answer(status: Int?, body: String?): PriceDaysAnswer {
        if (status == null || body == null) return PriceDaysAnswer.Failed
        if (status in 200..299) {
            return decode(body)?.let { PriceDaysAnswer.Answered(it, body) } ?: PriceDaysAnswer.Failed
        }
        if (status == 400) {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return PriceDaysAnswer.Failed
            if (json.opt("ok") == false && json.opt("action") == ACTION) {
                return PriceDaysAnswer.Refused(json.opt("error") as? String)
            }
        }
        return PriceDaysAnswer.Failed
    }

    /** The request body: the action, its version and the reads every request names. */
    fun payload(): JSONObject = JSONObject().apply {
        put("version", 1)
        WebhookReads.put(this)
        put("action", ACTION)
        put("api_version", VERSION)
    }
}
