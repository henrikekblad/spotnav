package se.sensnology.spotnav.ui.charging

import se.sensnology.spotnav.ha.client.WebhookHttpStatusException
import java.io.IOException

/**
 * Whether a failed dashboard read earns a line of its own on the charger card. No contact at all (the
 * network, or Home Assistant still starting behind its proxy: 502, 503, 504) does not: the screen's
 * banner already says there is no contact, in words. Anything else Home Assistant answered does.
 */
internal object ReadFailureLine {
    private val STARTING = setOf(502, 503, 504)

    fun shown(failure: Throwable?): Boolean = when (failure) {
        null -> false
        is IOException -> false
        is WebhookHttpStatusException -> failure.status !in STARTING
        else -> true
    }
}
